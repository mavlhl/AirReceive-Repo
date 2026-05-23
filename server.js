const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const fs = require('fs');

const MAX_BATCH_FILES = 20;
const MAX_BATCH_BYTES = 100 * 1024 * 1024; // 100 MB
const FILE_TTL_MS = 5 * 60 * 1000;

const app = express();
const server = http.createServer(app);

const PORT = process.env.PORT || 8080;
const UPLOAD_DIR = path.join('/tmp', 'airreceive_uploads');

// Ensure upload directory exists
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

// Memory map of active file transfers: fileId -> { id, name, path, size, mimeType, timestamp }
const fileMap = new Map();
// batchId -> { fileIds: string[], createdAt: number }
const batchMap = new Map();

// deviceId -> { ws, role: 'phone'|'receiver', displayName, connectedAt }
const deviceRegistry = new Map();

function getSocketsByRole(role) {
  const sockets = [];
  for (const entry of deviceRegistry.values()) {
    if (entry.role === role && entry.ws.readyState === WebSocket.OPEN) {
      sockets.push(entry.ws);
    }
  }
  return sockets;
}

function countByRole(role) {
  let n = 0;
  for (const entry of deviceRegistry.values()) {
    if (entry.role === role && entry.ws.readyState === WebSocket.OPEN) n++;
  }
  return n;
}

function broadcastToSockets(sockets, notification) {
  for (const socket of sockets) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(notification);
    }
  }
}

function notifyDevice(deviceId, notification) {
  const entry = deviceRegistry.get(deviceId);
  if (!entry || entry.ws.readyState !== WebSocket.OPEN) {
    return false;
  }
  entry.ws.send(notification);
  return true;
}

function relayToTargets(target, targetDeviceId, notification) {
  const role = target === 'receiver' ? 'receiver' : 'phone';
  if (targetDeviceId) {
    const entry = deviceRegistry.get(targetDeviceId);
    if (!entry || entry.role !== role) {
      return false;
    }
    return notifyDevice(targetDeviceId, notification);
  }
  const sockets = getSocketsByRole(role);
  if (sockets.length === 0) {
    return false;
  }
  broadcastToSockets(sockets, notification);
  return true;
}

function listDevices(roleFilter) {
  const receivers = [];
  const phones = [];
  for (const [id, entry] of deviceRegistry.entries()) {
    if (entry.ws.readyState !== WebSocket.OPEN) continue;
    const item = {
      id,
      displayName: entry.displayName,
      connectedAt: entry.connectedAt
    };
    if (entry.role === 'receiver') receivers.push(item);
    else if (entry.role === 'phone') phones.push(item);
  }
  if (roleFilter === 'receiver') return { receivers, phones: [] };
  if (roleFilter === 'phone') return { receivers: [], phones };
  return { receivers, phones };
}

function completeRegistration(ws, role, displayName, reconnectDeviceId) {
  if (ws.deviceId) {
    deviceRegistry.delete(ws.deviceId);
  }
  const deviceId = reconnectDeviceId || uuidv4();
  const name = (displayName || '').trim() || (role === 'phone' ? 'Android Phone' : 'Browser');
  ws.deviceId = deviceId;
  ws.deviceRole = role;
  ws.isRegistered = true;
  deviceRegistry.set(deviceId, {
    ws,
    role,
    displayName: name,
    connectedAt: Date.now()
  });
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'REGISTERED', deviceId, displayName: name }));
  }
  console.log(`[WebSocket] Registered ${role} "${name}" (${deviceId})`);
  return deviceId;
}

function unregisterSocket(ws) {
  if (ws.deviceId) {
    deviceRegistry.delete(ws.deviceId);
    console.log(`[WebSocket] Unregistered ${ws.deviceId}`);
  }
}

function setupDeviceSocket(ws, role, label) {
  ws.deviceRole = role;
  ws.isRegistered = false;
  let registerTimeout = setTimeout(() => {
    if (!ws.isRegistered) {
      completeRegistration(ws, role, role === 'phone' ? 'Android Phone' : 'Browser');
    }
  }, 5000);

  ws.on('message', (raw) => {
    try {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'REGISTER') {
        clearTimeout(registerTimeout);
        completeRegistration(
          ws,
          role,
          msg.displayName,
          msg.deviceId || null
        );
      }
    } catch (e) {
      console.warn(`[WebSocket] Invalid message from ${label}:`, e.message);
    }
  });

  ws.on('close', () => {
    clearTimeout(registerTimeout);
    unregisterSocket(ws);
    console.log(`[WebSocket] ${label} disconnected (${countByRole(role)} ${role} remaining).`);
  });

  ws.on('error', (err) => {
    clearTimeout(registerTimeout);
    console.error(`[WebSocket] ${label} error:`, err);
    unregisterSocket(ws);
  });

  console.log(`[WebSocket] ${label} connected (awaiting REGISTER).`);
}

function deleteFileEntry(fileId) {
  const info = fileMap.get(fileId);
  if (!info) return;
  try {
    if (fs.existsSync(info.path)) {
      fs.unlinkSync(info.path);
    }
  } catch (err) {
    console.error(`[Clean Error] Failed to delete ${info.path}`, err);
  }
  fileMap.delete(fileId);
}

function deleteBatch(batchId) {
  const batch = batchMap.get(batchId);
  if (!batch) return;
  for (const fileId of batch.fileIds) {
    deleteFileEntry(fileId);
  }
  batchMap.delete(batchId);
}

function registerUploadedFile(file) {
  const fileId = file.filename.split('.')[0];
  const fileInfo = {
    id: fileId,
    name: file.originalname,
    path: file.path,
    size: file.size,
    mimeType: file.mimetype || 'image/jpeg',
    timestamp: Date.now()
  };
  fileMap.set(fileId, fileInfo);
  return fileInfo;
}

// Multer storage setup
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, UPLOAD_DIR);
  },
  filename: (req, file, cb) => {
    const fileId = uuidv4();
    const extension = path.extname(file.originalname);
    cb(null, fileId + extension);
  }
});
const upload = multer({ storage: storage });

// Periodic cleanup of files and batches older than 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [id, info] of fileMap.entries()) {
    if (now - info.timestamp > FILE_TTL_MS) {
      console.log(`[Clean] Expired file id ${id} (${info.name}) deleted.`);
      deleteFileEntry(id);
    }
  }
  for (const [batchId, batch] of batchMap.entries()) {
    if (now - batch.createdAt > FILE_TTL_MS) {
      console.log(`[Clean] Expired batch ${batchId} deleted.`);
      deleteBatch(batchId);
    }
  }
}, 60 * 1000);

// Static assets (e.g. Buy Me a Coffee QR)
app.use('/docs', express.static(path.join(__dirname, 'docs')));

// List online registered devices
app.get('/api/devices', (req, res) => {
  const role = req.query.role;
  const lists = listDevices(role);
  res.json(lists);
});

// Status route for webpages to see connected clients
app.get('/api/status', (req, res) => {
  const phoneCount = countByRole('phone');
  const receiverCount = countByRole('receiver');
  res.json({
    phoneConnected: phoneCount > 0,
    receiverConnected: receiverCount > 0,
    connectionsCount: phoneCount,
    receiverCount: receiverCount,
    devices: { receivers: receiverCount, phones: phoneCount }
  });
});

// Upload route
app.post('/upload', upload.single('file'), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No file provided' });
  }

  const fileInfo = registerUploadedFile(req.file);
  const fileId = fileInfo.id;
  const target = (req.body.target === 'receiver') ? 'receiver' : 'phone';
  const targetDeviceId = (req.body.targetDeviceId || '').trim() || null;
  console.log(`[Upload] File received: ${fileInfo.name} (${fileInfo.size} bytes). ID: ${fileId}, target: ${target}, targetDeviceId: ${targetDeviceId || 'broadcast'}`);

  const notification = JSON.stringify({
    type: 'NOTIFY_UPLOAD',
    id: fileId,
    name: fileInfo.name,
    size: fileInfo.size,
    mimeType: fileInfo.mimeType
  });

  const relayed = relayToTargets(target, targetDeviceId, notification);
  const phoneRelayed = target === 'phone' && relayed;
  const receiverRelayed = target === 'receiver' && relayed;

  if (!relayed) {
    const msg = targetDeviceId
      ? 'Target device is offline or not found.'
      : (target === 'receiver' ? 'No receiver browsers connected.' : 'No phones connected.');
    console.warn(`[Broadcast] ${msg}`);
    if (targetDeviceId) {
      return res.status(404).json({
        success: false,
        error: msg,
        target,
        targetDeviceId,
        phoneRelayed: false,
        receiverRelayed: false
      });
    }
  } else {
    console.log(`[Broadcast] Notified ${target}${targetDeviceId ? ' device ' + targetDeviceId : ' (all)'}.`);
  }

  res.json({
    success: true,
    fileId: fileId,
    name: fileInfo.name,
    target: target,
    targetDeviceId: targetDeviceId,
    phoneRelayed: phoneRelayed,
    receiverRelayed: receiverRelayed
  });
});

// Batch upload (Android -> iPhone)
app.post('/upload/batch', upload.array('files', MAX_BATCH_FILES), (req, res) => {
  const files = req.files;
  if (!files || files.length === 0) {
    return res.status(400).json({ error: 'No files provided' });
  }

  const totalBytes = files.reduce((sum, f) => sum + f.size, 0);
  if (totalBytes > MAX_BATCH_BYTES) {
    for (const f of files) {
      try {
        if (fs.existsSync(f.path)) fs.unlinkSync(f.path);
      } catch (e) { /* ignore */ }
    }
    return res.status(413).json({
      error: `Batch too large. Maximum total size is ${MAX_BATCH_BYTES / (1024 * 1024)} MB.`
    });
  }

  const target = (req.body.target === 'receiver') ? 'receiver' : 'phone';
  const targetDeviceId = (req.body.targetDeviceId || '').trim() || null;
  const batchId = uuidv4();
  const fileIds = [];
  const fileMeta = [];

  for (const file of files) {
    const fileInfo = registerUploadedFile(file);
    fileIds.push(fileInfo.id);
    fileMeta.push({
      id: fileInfo.id,
      name: fileInfo.name,
      size: fileInfo.size,
      mimeType: fileInfo.mimeType
    });
  }

  batchMap.set(batchId, { fileIds, createdAt: Date.now() });
  console.log(`[Batch] ${files.length} file(s), ${totalBytes} bytes, batchId=${batchId}, target=${target}, targetDeviceId=${targetDeviceId || 'broadcast'}`);

  let phoneRelayed = false;
  let receiverRelayed = false;

  if (target === 'receiver') {
    const notification = JSON.stringify({
      type: 'NOTIFY_BATCH',
      batchId,
      files: fileMeta,
      count: fileMeta.length
    });
    receiverRelayed = relayToTargets('receiver', targetDeviceId, notification);
    if (!receiverRelayed) {
      const msg = targetDeviceId
        ? 'Target device is offline or not found.'
        : 'No receiver browsers connected for batch.';
      console.warn(`[Broadcast] ${msg}`);
      if (targetDeviceId) {
        deleteBatch(batchId);
        return res.status(404).json({
          success: false,
          error: msg,
          batchId,
          target,
          targetDeviceId,
          phoneRelayed: false,
          receiverRelayed: false
        });
      }
    } else {
      console.log(`[Broadcast] NOTIFY_BATCH sent to receiver${targetDeviceId ? ' ' + targetDeviceId : '(s)'}.`);
    }
  } else {
    let anyRelayed = false;
    for (const meta of fileMeta) {
      const notification = JSON.stringify({
        type: 'NOTIFY_UPLOAD',
        id: meta.id,
        name: meta.name,
        size: meta.size,
        mimeType: meta.mimeType,
        batchId
      });
      if (relayToTargets('phone', targetDeviceId, notification)) {
        anyRelayed = true;
      }
    }
    phoneRelayed = anyRelayed;
    if (!phoneRelayed) {
      const msg = targetDeviceId
        ? 'Target device is offline or not found.'
        : 'No phones connected for batch.';
      console.warn(`[Broadcast] ${msg}`);
      if (targetDeviceId) {
        deleteBatch(batchId);
        return res.status(404).json({
          success: false,
          error: msg,
          batchId,
          target,
          targetDeviceId,
          phoneRelayed: false,
          receiverRelayed: false
        });
      }
    }
  }

  res.json({
    success: true,
    batchId,
    count: fileMeta.length,
    target,
    targetDeviceId,
    phoneRelayed,
    receiverRelayed
  });
});

// Delete batch after iPhone saves to Photos (Share sheet flow)
app.delete('/batch/:batchId', (req, res) => {
  const batchId = req.params.batchId;
  if (!batchMap.has(batchId)) {
    return res.status(404).json({ error: 'Batch expired or not found.' });
  }
  console.log(`[Batch] Client acknowledged save; deleting batch ${batchId}`);
  deleteBatch(batchId);
  res.json({ success: true });
});

// Download route — ?keep=1 skips delete (used for iPhone batch thumbnails + share)
app.get('/download/:id', (req, res) => {
  const fileId = req.params.id;

  const info = fileMap.get(fileId);

  if (!info) {
    return res.status(404).send('File expired or not found.');
  }

  if (!fs.existsSync(info.path)) {
    fileMap.delete(fileId);
    return res.status(404).send('File physical payload not found.');
  }

  const keep = req.query.keep === '1';
  console.log(`[Download] Client is downloading file ${info.name}... (keep=${keep})`);
  res.download(info.path, info.name, (err) => {
    if (err) {
      console.error(`[Download Error] Failed streaming file ${info.name}:`, err);
    } else if (!keep) {
      console.log(`[Download Success] Completed download of ${info.name}. Deleting from gateway.`);
      deleteFileEntry(fileId);
    }
  });
});

function macThemeBootScript() {
  return `(function(){try{var k='airreceive-theme';var t=localStorage.getItem(k);var d=t? t==='dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;document.documentElement.setAttribute('data-theme',d?'dark':'light');window.__toggleAirReceiveTheme=function(){var next=document.documentElement.getAttribute('data-theme')==='dark'?'light':'dark';document.documentElement.setAttribute('data-theme',next);localStorage.setItem(k,next);var btn=document.getElementById('theme-toggle-btn');if(btn)btn.textContent=next==='dark'?'☀️':'🌙';fetch('http://127.0.0.1:7427/ingest/e9f47ee5-3ad4-471b-aaa3-2e53e96becd0',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'c78bc5'},body:JSON.stringify({sessionId:'c78bc5',location:'server.js:theme',message:'theme toggled',hypothesisId:'W',data:{theme:next},timestamp:Date.now()})}).catch(function(){});};}catch(e){}})();`;
}

function macSiteHeaderHtml(activeNav) {
  return `
    <header class="site-header">
      <div class="site-brand">
        <span class="site-logo" aria-hidden="true">◉</span>
        <div>
          <div class="site-title">AirReceive</div>
          <div class="site-subtitle">Support Maverick for a virtual cookie!</div>
        </div>
      </div>
      <button type="button" id="theme-toggle-btn" class="theme-toggle" onclick="window.__toggleAirReceiveTheme()">☀️</button>
    </header>
    ${gatewayNavHtml(activeNav)}
  `;
}

function macDesignCss(accent = '#007aff') {
  return `
    :root, [data-theme="dark"] {
      color-scheme: dark light;
      --mac-window: #000000;
      --mac-content: #1c1c1e;
      --mac-secondary: #2c2c2e;
      --mac-tertiary: #3a3a3c;
      --mac-separator: rgba(84, 84, 88, 0.65);
      --mac-label: #ffffff;
      --mac-label-secondary: rgba(235, 235, 245, 0.6);
      --mac-blue: #007aff;
      --mac-green: #30d158;
      --mac-red: #ff453a;
      --mac-orange: #ff9f0a;
      --mac-glass: rgba(44, 44, 46, 0.72);
      --mac-radius: 12px;
      --mac-radius-lg: 16px;
      --primary: ${accent};
      --bg-color: var(--mac-content);
      --card-bg: var(--mac-glass);
      --border-color: rgba(255, 255, 255, 0.08);
      --text-main: var(--mac-label);
      --text-muted: var(--mac-label-secondary);
    }
    [data-theme="light"] {
      --mac-window: #ffffff;
      --mac-content: #f2f2f7;
      --mac-secondary: #ffffff;
      --mac-tertiary: #e5e5ea;
      --mac-label: #000000;
      --mac-label-secondary: rgba(60, 60, 67, 0.6);
      --mac-glass: rgba(255, 255, 255, 0.82);
      --border-color: rgba(0, 0, 0, 0.08);
      --text-main: var(--mac-label);
      --text-muted: var(--mac-label-secondary);
    }
    body {
      margin: 0;
      background: var(--mac-window);
      color: var(--text-main);
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: stretch;
    }
    .page-shell { width: 100%; max-width: 640px; margin: 0 auto; padding: 16px 16px 32px; box-sizing: border-box; }
    .site-header {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 12px; padding: 8px 4px;
    }
    .site-brand { display: flex; align-items: center; gap: 10px; }
    .site-logo {
      width: 32px; height: 32px; border-radius: 8px; background: var(--mac-blue); color: #fff;
      display: inline-flex; align-items: center; justify-content: center; font-size: 18px;
    }
    .site-title { font-size: 17px; font-weight: 600; color: var(--text-main); }
    .site-subtitle { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
    .theme-toggle {
      border: 1px solid var(--border-color); background: var(--mac-secondary);
      border-radius: 8px; padding: 8px 10px; cursor: pointer; font-size: 16px;
    }
    .container { width: 100%; max-width: 560px; margin: 0 auto; }
    .card {
      background: var(--card-bg);
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
      border: 1px solid var(--border-color);
      border-radius: var(--mac-radius-lg);
      padding: 32px;
      text-align: center;
    }
    h1 { font-size: 22px; font-weight: 600; margin: 0 0 8px; letter-spacing: -0.3px; }
    .tagline { color: var(--text-muted); font-size: 13px; margin-bottom: 20px; line-height: 1.45; }
    .gateway-nav {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 4px;
      justify-content: center;
      margin-bottom: 24px;
      padding: 4px;
      background: var(--mac-secondary);
      border-radius: var(--mac-radius);
    }
    .gateway-nav-link {
      padding: 8px 12px;
      border-radius: 8px;
      border: none;
      color: var(--text-muted);
      text-decoration: none;
      font-size: 12px;
      font-weight: 600;
    }
    .gateway-nav-link:hover { color: var(--primary); }
    .gateway-nav-link.active {
      color: var(--primary);
      background: var(--mac-tertiary);
    }
    code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
  `;
}

function gatewayNavHtml(activeNav) {
  const items = [
    { key: 'home', href: '/', label: 'Home' },
    { key: 'android', href: '/to-android', label: 'Send to Android' },
    { key: 'send', href: '/send', label: 'Send to device' },
    { key: 'receive', href: '/receive', label: 'Receive' },
    { key: 'support', href: '/support', label: 'Support' }
  ];
  return '<nav class="gateway-nav">' + items.map((item) => {
    const cls = item.key === activeNav ? 'gateway-nav-link active' : 'gateway-nav-link';
    return '<a class="' + cls + '" href="' + item.href + '">' + item.label + '</a>';
  }).join('') + '</nav>';
}

function gatewayPageHtml({ title, activeNav, accent = '#007aff', extraCss = '', bodyHtml }) {
  return `<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${title}</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss(accent)}
    ${extraCss}
  </style>
</head>
<body>
  <div class="page-shell">
    ${macSiteHeaderHtml(activeNav)}
    <div class="container">
      ${bodyHtml}
    </div>
  </div>
</body>
</html>`;
}

function macStandaloneChrome(activeNav, accent) {
  return `<script>${macThemeBootScript()}</script>
<style>${macDesignCss(accent)}</style>
<div class="page-shell">${macSiteHeaderHtml(activeNav)}<div class="container">`;
}

function macStandaloneChromeEnd() {
  return `</div></div>`;
}

// Hub — pick an action (no upload on this page)
app.get('/', (req, res) => {
  res.send(gatewayPageHtml({
    title: 'AirReceive Gateway',
    activeNav: 'home',
    accent: '#007aff',
    extraCss: `
    .hub-card {
      display: block;
      text-align: left;
      padding: 16px 18px;
      margin-bottom: 10px;
      border-radius: var(--mac-radius);
      border: 1px solid var(--border-color);
      background: var(--mac-glass);
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
      color: var(--text-main);
      text-decoration: none;
      transition: background 0.15s, border-color 0.15s;
    }
    .hub-card:hover { border-color: var(--primary); background: var(--mac-tertiary); }
    .hub-card strong { display: block; font-size: 15px; margin-bottom: 4px; color: var(--text-main); font-weight: 600; }
    .hub-card span { font-size: 13px; color: var(--text-muted); line-height: 1.4; }
    .hub-card::after { content: "›"; float: right; color: var(--text-muted); font-size: 18px; }
    .hub-status { font-size: 12px; color: var(--text-muted); margin-top: 16px; }
    `,
    bodyHtml: `
    <div class="card" style="text-align:center;">
      <h1>AirReceive Gateway</h1>
      <p class="tagline">Choose what you want to do</p>
      <a class="hub-card" href="/to-android">
        <strong>Send to Android</strong>
        <span>Upload a photo from this browser to your Android phone running AirReceive.</span>
      </a>
      <a class="hub-card" href="/send">
        <strong>Send to PC or phone</strong>
        <span>Pick an online receiver and send up to 20 files (images, PDF, etc.).</span>
      </a>
      <a class="hub-card" href="/receive">
        <strong>Receive files</strong>
        <span>Stay on this page to receive files sent from another device or Android.</span>
      </a>
      <a class="hub-card" href="/support">
        <strong>Support Maverick</strong>
        <span>Thank you for supporting AirReceive — Buy Me a Coffee and QR code.</span>
      </a>
      <p class="hub-status" id="hubStatus">Checking gateway status...</p>
    </div>
    <script>
      fetch('/api/status').then(r => r.json()).then((d) => {
        const el = document.getElementById('hubStatus');
        el.textContent = 'Android apps: ' + (d.devices?.phones ?? d.connectionsCount ?? 0) +
          ' online · Receivers: ' + (d.devices?.receivers ?? d.receiverCount ?? 0) + ' online';
      }).catch(() => {
        document.getElementById('hubStatus').textContent = 'Could not load status.';
      });
    </script>
    `
  }));
});

const BMC_URL = 'https://buymeacoffee.com/mavlhl';

app.get('/support', (req, res) => {
  res.send(gatewayPageHtml({
    title: 'AirReceive — Support Maverick',
    activeNav: 'support',
    accent: '#ff9f0a',
    extraCss: `
    .bmc-qr-wrap {
      display: inline-block;
      padding: 12px;
      background: #fff;
      border-radius: 16px;
      margin: 16px 0;
    }
    .bmc-qr { display: block; width: 250px; height: 250px; }
    .bmc-btn {
      display: inline-block;
      margin-top: 8px;
      padding: 14px 28px;
      border-radius: 10px;
      background: #ff9f0a;
      color: #1c1c1e;
      font-weight: 800;
      font-size: 15px;
      text-decoration: none;
      transition: opacity 0.15s;
    }
    .bmc-btn:hover { opacity: 0.9; }
    .bmc-note { font-size: 13px; color: var(--text-muted); margin-top: 12px; line-height: 1.5; }
    `,
    bodyHtml: `
    <div class="card">
      <h1>Thank you for supporting Maverick</h1>
      <p class="tagline">Your support helps keep AirReceive updated and free to use.</p>
      <p class="bmc-note">Scan the QR code with your phone camera, or tap the button to open Buy Me a Coffee in your browser.</p>
      <div class="bmc-qr-wrap">
        <img src="/docs/bmc-qr.png" alt="Buy Me a Coffee QR code" width="250" height="250" class="bmc-qr">
      </div>
      <br>
      <a class="bmc-btn" href="${BMC_URL}" target="_blank" rel="noopener noreferrer">Buy Me a Coffee</a>
    </div>
    `
  }));
});

// Send photo to Android (browser upload)
app.get('/to-android', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AirReceive — Send to Android</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#30d158')}

    .card {
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
      padding: 32px;
      text-align: center;
      box-shadow: 0 10px 30px rgba(0,0,0,0.35);
    }

    .logo-container {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background: #30d158;
      box-shadow: 0 0 20px rgba(16, 185, 129, 0.4);
      margin-bottom: 16px;
    }

    .logo-container svg {
      width: 32px;
      height: 32px;
      fill: white;
    }

    h1 {
      font-size: 26px;
      font-weight: 800;
      margin: 0;
      letter-spacing: -0.5px;
      background: linear-gradient(to right, #ffffff, #8b949e);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }

    .tagline {
      color: var(--text-muted);
      font-size: 14px;
      margin-top: 8px;
      margin-bottom: 24px;
    }

    /* Live Badge Indicators */
    .status-badge {
      display: inline-flex;
      align-items: center;
      background-color: rgba(239, 68, 68, 0.1);
      border: 1px solid rgba(239, 68, 68, 0.2);
      color: #fc8181;
      padding: 6px 14px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.5px;
      margin-bottom: 24px;
      text-transform: uppercase;
      transition: all 0.3s ease;
    }

    .status-badge.connected {
      background-color: rgba(16, 185, 129, 0.1);
      border: 1px solid rgba(16, 185, 129, 0.2);
      color: var(--primary);
      box-shadow: 0 0 10px rgba(16, 185, 129, 0.15);
    }

    .status-badge .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background-color: #ff453a;
      margin-right: 8px;
      animation: pulse 1.5s infinite;
    }

    .status-badge.connected .dot {
      background-color: var(--primary);
    }

    @keyframes pulse {
      0% { opacity: 0.3; }
      50% { opacity: 1; }
      100% { opacity: 0.3; }
    }

    /* Drag Drop Area */
    .drop-zone {
      border: 2px dashed var(--border-color);
      background-color: rgba(22, 27, 34, 0.5);
      border-radius: 16px;
      padding: 30px 16px;
      cursor: pointer;
      transition: all 0.25s ease;
      position: relative;
    }

    .drop-zone:hover, .drop-zone.drag-over {
      border-color: var(--primary);
      background-color: rgba(16, 185, 129, 0.04);
    }

    .drop-zone-text {
      font-size: 14px;
      color: var(--text-muted);
    }

    .drop-zone-text strong {
      color: var(--text-main);
      display: block;
      font-size: 15px;
      margin-bottom: 4px;
    }

    .file-input {
      display: none;
    }

    /* Progress and result overlays */
    .progress-container {
      margin-top: 20px;
      display: none;
    }

    .progress-bar-bg {
      height: 6px;
      background-color: var(--border-color);
      border-radius: 10px;
      overflow: hidden;
    }

    .progress-bar {
      height: 100%;
      width: 0%;
      background: linear-gradient(90deg, var(--primary), var(--accent));
      border-radius: 10px;
      transition: width 0.1s ease;
    }

    .progress-text {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 8px;
      display: flex;
      justify-content: space-between;
    }

    .preview-image {
      max-width: 100%;
      max-height: 120px;
      border-radius: 8px;
      margin-top: 12px;
      display: none;
      object-fit: contain;
    }

    /* Alert and success box styling */
    .toast {
      padding: 12px 16px;
      border-radius: 12px;
      font-size: 13px;
      margin-top: 16px;
      display: none;
      animation: fadeInUp 0.3s ease;
    }

    .toast-success {
      background-color: rgba(16, 185, 129, 0.12);
      border: 1px solid rgba(16, 185, 129, 0.25);
      color: #a7f3d0;
    }

    .toast-error {
      background-color: rgba(239, 68, 68, 0.12);
      border: 1px solid rgba(239, 68, 68, 0.25);
      color: #fecaca;
    }

    /* Render Instructions */
    .instructions {
      text-align: left;
      background-color: rgba(30, 41, 59, 0.3);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 16px 20px;
      margin-top: 24px;
      font-size: 13px;
    }

    .instructions-title {
      font-weight: 700;
      color: var(--text-main);
      font-size: 13px;
      margin-bottom: 8px;
      display: flex;
      align-items: center;
    }

    .instructions-title svg {
      margin-right: 6px;
      fill: var(--accent);
      width: 16px;
      height: 16px;
    }

    ol {
      margin: 0;
      padding-left: 18px;
      color: var(--text-muted);
    }

    ol li {
      margin-bottom: 8px;
    }

    ol li:last-child {
      margin-bottom: 0;
    }

    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      background-color: rgba(255,255,255,0.06);
      padding: 2px 6px;
      border-radius: 4px;
      color: var(--accent);
      font-size: 11.5px;
    }

    .footer {
      font-size: 11px;
      color: #484f58;
      margin-top: 32px;
      text-align: center;
    }

    @keyframes fadeInUp {
      from { transform: translateY(10px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    .nav-link {
      display: inline-block;
      margin-bottom: 20px;
      padding: 10px 18px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--accent);
      text-decoration: none;
      font-size: 13px;
      font-weight: 600;
    }

    .nav-link:hover {
      border-color: var(--accent);
      background-color: rgba(56, 189, 248, 0.08);
    }
    .gateway-nav {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: center;
      margin-bottom: 20px;
    }
    .gateway-nav-link {
      padding: 8px 14px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--text-muted);
      text-decoration: none;
      font-size: 12px;
      font-weight: 600;
    }
    .gateway-nav-link:hover { border-color: var(--accent); color: var(--accent); }
    .gateway-nav-link.active {
      border-color: var(--accent);
      color: var(--accent);
      background: rgba(16, 185, 129, 0.1);
    }
    label.device-label { font-size: 12px; font-weight: 600; color: var(--text-muted); display: block; margin-bottom: 6px; text-align: left; }
    .device-list {
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 8px;
      margin-bottom: 16px;
      max-height: 160px;
      overflow-y: auto;
      text-align: left;
    }
    .device-option {
      display: flex;
      align-items: center;
      padding: 10px;
      border-radius: 8px;
      cursor: pointer;
    }
    .device-option:hover { background: rgba(16, 185, 129, 0.08); }
    .device-option input { margin-right: 10px; }
    .device-empty { color: var(--text-muted); font-size: 13px; padding: 12px; text-align: center; }
    .refresh-btn {
      background: transparent;
      border: 1px solid var(--border-color);
      color: var(--accent);
      padding: 6px 12px;
      border-radius: 10px;
      font-size: 12px;
      cursor: pointer;
      margin-bottom: 8px;
    }
    .drop-zone.disabled { opacity: 0.45; pointer-events: none; }
  </style>
</head>
<body>
  <div class="page-shell">
    ${macSiteHeaderHtml('android')}
  <div class="container">
    <div class="card">
      <div class="logo-container">
        <!-- Photo/Image transfer vector icon -->
        <svg viewBox="0 0 24 24">
          <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
        </svg>
      </div>

      <h1>Send to Android</h1>
      <p class="tagline">Upload a photo from this browser to your Android phone</p>

      <div class="status-badge" id="statusBadge">
        <span class="dot"></span>
        <span id="statusText">Checking Connection...</span>
      </div>

      <label class="device-label">Send to Android device</label>
      <button type="button" class="refresh-btn" id="refreshPhonesBtn">Refresh list</button>
      <div class="device-list" id="phoneList">
        <div class="device-empty">Looking for online phones...</div>
      </div>

      <div class="drop-zone disabled" id="dropZone">
        <div class="drop-zone-text">
          <strong>Select a Photo or Drag & Drop</strong>
          <span>tap anywhere to browse JPEG, PNG, or webp</span>
        </div>
        <input type="file" id="fileInput" class="file-input" accept="image/*" />
        <img id="imagePreview" class="preview-image" alt="Upload preview" />
      </div>

      <div class="progress-container" id="progressContainer">
        <div class="progress-bar-bg">
          <div class="progress-bar" id="progressBar"></div>
        </div>
        <div class="progress-text">
          <span id="fileTransferName">uploading...</span>
          <span id="percentText">0%</span>
        </div>
      </div>

      <div class="toast toast-success" id="successToast">
        🎉 Photo successfully transferred to your Android device!
      </div>
      <div class="toast toast-error" id="errorToast">
        ❌ Transfer failed. Please try again.
      </div>

      <div class="instructions">
        <div class="instructions-title">
          <!-- Info icon -->
          <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
          How to connect your phone
        </div>
        <ol>
          <li>Open the <strong>AirReceive</strong> app on your Android Phone.</li>
          <li>Open the <strong>Settings</strong> tab in AirReceive and enable the gateway.</li>
          <li>Paste this full website URL in the text field:</li>
          <li><code><span id="urlPlaceholder">https://your-app.onrender.com</span></code></li>
          <li>Click the checkmark to save. Your status will instantly show <strong>Ready</strong>!</li>
        </ol>
      </div>
    </div>

    <!-- Render free web services notice -->
    <div class="footer">
      Powered by Render Web Services. This proxy automatically digests uploads, notify receiver WebSockets, and purges storage instantly for optimal security.
    </div>
  </div>

  <script>
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const imagePreview = document.getElementById('imagePreview');
    const progressContainer = document.getElementById('progressContainer');
    const progressBar = document.getElementById('progressBar');
    const percentText = document.getElementById('percentText');
    const fileTransferName = document.getElementById('fileTransferName');
    const successToast = document.getElementById('successToast');
    const errorToast = document.getElementById('errorToast');
    const statusBadge = document.getElementById('statusBadge');
    const statusText = document.getElementById('statusText');
    const urlPlaceholder = document.getElementById('urlPlaceholder');
    const phoneListEl = document.getElementById('phoneList');
    const refreshPhonesBtn = document.getElementById('refreshPhonesBtn');

    let selectedPhoneId = null;

    urlPlaceholder.textContent = window.location.origin;

    function updateDropZoneEnabled() {
      dropZone.classList.toggle('disabled', !selectedPhoneId);
    }

    async function refreshPhones() {
      try {
        const res = await fetch('/api/devices?role=phone');
        const data = await res.json();
        const phones = data.phones || [];
        if (phones.length === 0) {
          phoneListEl.innerHTML = '<div class="device-empty">No phones online. Open AirReceive on Android, enable gateway in <strong>Settings</strong>, and keep the app in the foreground.</div>';
          selectedPhoneId = null;
          updateDropZoneEnabled();
          return;
        }
        phoneListEl.innerHTML = '';
        phones.forEach((dev) => {
          const row = document.createElement('label');
          row.className = 'device-option';
          const radio = document.createElement('input');
          radio.type = 'radio';
          radio.name = 'targetPhone';
          radio.value = dev.id;
          if (dev.id === selectedPhoneId) radio.checked = true;
          radio.addEventListener('change', () => {
            selectedPhoneId = dev.id;
            updateDropZoneEnabled();
          });
          const text = document.createElement('span');
          text.textContent = dev.displayName + ' — online';
          row.appendChild(radio);
          row.appendChild(text);
          phoneListEl.appendChild(row);
        });
        if (!selectedPhoneId && phones.length === 1) {
          selectedPhoneId = phones[0].id;
          phoneListEl.querySelector('input').checked = true;
          updateDropZoneEnabled();
        }
      } catch (e) {
        phoneListEl.innerHTML = '<div class="device-empty">Could not load phone list.</div>';
      }
    }

    refreshPhonesBtn.addEventListener('click', refreshPhones);
    setInterval(refreshPhones, 3000);
    refreshPhones();

    dropZone.addEventListener('click', () => {
      if (selectedPhoneId) fileInput.click();
    });

    // File drag effects
    dropZone.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropZone.classList.add('drag-over');
    });

    ['dragleave', 'dragend', 'drop'].forEach(event => {
      dropZone.addEventListener(event, () => dropZone.classList.remove('drag-over'));
    });

    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      if (e.dataTransfer.files.length) {
        handleFileSelect(e.dataTransfer.files[0]);
      }
    });

    fileInput.addEventListener('change', (e) => {
      if (fileInput.files.length) {
        handleFileSelect(fileInput.files[0]);
      }
    });

    function handleFileSelect(file) {
      if (!selectedPhoneId) {
        showError('Select an Android device first.');
        return;
      }
      if (!file.type.startsWith('image/')) {
        showError('Only image files are supported in standard view mode.');
        return;
      }

      // Show preview
      const reader = new FileReader();
      reader.onload = (e) => {
        imagePreview.src = e.target.result;
        imagePreview.style.display = 'block';
      };
      reader.readAsDataURL(file);

      // Start upload automatically
      uploadFile(file);
    }

    function uploadFile(file) {
      hideToasts();
      
      progressContainer.style.display = 'block';
      fileTransferName.textContent = file.name;
      progressBar.style.width = '0%';
      percentText.textContent = '0%';

      const formData = new FormData();
      formData.append('file', file);
      formData.append('target', 'phone');
      formData.append('targetDeviceId', selectedPhoneId);

      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/upload', true);

      // Track progress
      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) {
          const percent = Math.round((e.loaded / e.total) * 100);
          progressBar.style.width = percent + '%';
          percentText.textContent = percent + '%';
        }
      });

      xhr.onload = () => {
        progressContainer.style.display = 'none';
        
        if (xhr.status === 200) {
          try {
            const result = JSON.parse(xhr.responseText);
            if (result.phoneRelayed === false) {
              showError('Upload reached the server, but the phone is not connected. Refresh the device list and try again.');
              return;
            }
          } catch (e) { /* legacy response */ }
          showSuccess();
        } else if (xhr.status === 404) {
          let err = 'Selected phone is offline.';
          try {
            const j = JSON.parse(xhr.responseText);
            if (j.error) err = j.error;
          } catch (e) { /* ignore */ }
          showError(err + ' Refresh the list and pick another device.');
        } else {
          showError('Server rejected file upload: ' + xhr.responseText);
        }
      };

      xhr.onerror = () => {
        progressContainer.style.display = 'none';
        showError('Network transfer failure occurred. Check your server.');
      };

      xhr.send(formData);
    }

    function showSuccess() {
      successToast.style.display = 'block';
      setTimeout(() => {
        successToast.style.display = 'none';
        imagePreview.style.display = 'none';
        imagePreview.src = '';
      }, 6000);
    }

    function showError(msg) {
      errorToast.textContent = msg || '❌ Transfer failed. Please try again.';
      errorToast.style.display = 'block';
      setTimeout(() => {
        errorToast.style.display = 'none';
      }, 5000);
    }

    function hideToasts() {
      successToast.style.display = 'none';
      errorToast.style.display = 'none';
    }

    // Dynamic Connection Status Polling
    function pollStatus() {
      fetch('/api/status')
        .then(res => res.json())
        .then(data => {
          if (data.phoneConnected) {
            statusBadge.classList.add('connected');
            statusText.textContent = 'Ready to Send (Phone Active)';
          } else {
            statusBadge.classList.remove('connected');
            statusText.textContent = 'Phone Offline (App Closed/No URL)';
          }
        })
        .catch(() => {
          statusBadge.classList.remove('connected');
          statusText.textContent = 'Disconnected from gateway';
        });
    }

    // Poll every 3 seconds
    setInterval(pollStatus, 3000);
    pollStatus();
  </script>
  </div>
  </div>
</body>
</html>
  `);
});

const wssPhone = new WebSocket.Server({ noServer: true });
const wssReceiver = new WebSocket.Server({ noServer: true });


// PC / browser send page (pick target device, then upload batch)
app.get('/send', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AirReceive — Send files</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#007aff')}
    .card {
      padding: 32px;
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
    }
    h1 { font-size: 24px; margin: 0 0 8px; text-align: center; }
    .tagline { color: var(--text-muted); font-size: 14px; margin-bottom: 20px; text-align: center; }
    .nav-row { text-align: center; margin-bottom: 20px; }
    .nav-link {
      display: inline-block;
      margin: 4px;
      padding: 8px 14px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--primary);
      text-decoration: none;
      font-size: 12px;
      font-weight: 600;
    }
    label { font-size: 12px; font-weight: 600; color: var(--text-muted); display: block; margin-bottom: 6px; }
    input[type="text"] {
      width: 100%;
      box-sizing: border-box;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background: #1c1c1e;
      color: var(--text-main);
      font-size: 14px;
      margin-bottom: 16px;
    }
    .device-list {
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 8px;
      margin-bottom: 16px;
      max-height: 180px;
      overflow-y: auto;
    }
    .device-option {
      display: flex;
      align-items: center;
      padding: 10px;
      border-radius: 8px;
      cursor: pointer;
    }
    .device-option:hover { background: rgba(56, 189, 248, 0.08); }
    .device-option input { margin-right: 10px; }
    .device-empty { color: var(--text-muted); font-size: 13px; padding: 12px; text-align: center; }
    .drop-zone {
      border: 2px dashed var(--border-color);
      border-radius: 16px;
      padding: 28px;
      text-align: center;
      cursor: pointer;
      margin-bottom: 16px;
    }
    .drop-zone.disabled { opacity: 0.45; pointer-events: none; }
    .drop-zone strong { display: block; margin-bottom: 6px; }
    .drop-zone span { font-size: 12px; color: var(--text-muted); }
    .file-input { display: none; }
    .send-btn {
      width: 100%;
      padding: 14px;
      border: none;
      border-radius: 10px;
      background: #007aff;
      color: #ffffff;
      font-weight: 700;
      font-size: 15px;
      cursor: pointer;
    }
    .send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .progress { display: none; margin-top: 12px; font-size: 13px; color: var(--text-muted); }
    .toast-error {
      display: none;
      margin-top: 12px;
      padding: 12px;
      border-radius: 12px;
      background: rgba(239, 68, 68, 0.12);
      color: #fecaca;
      font-size: 13px;
    }
    .toast-success {
      display: none;
      margin-top: 12px;
      padding: 12px;
      border-radius: 12px;
      background: rgba(16, 185, 129, 0.12);
      color: #a7f3d0;
      font-size: 13px;
    }
    .refresh-btn {
      background: transparent;
      border: 1px solid var(--border-color);
      color: var(--primary);
      padding: 6px 12px;
      border-radius: 10px;
      font-size: 12px;
      cursor: pointer;
      margin-bottom: 8px;
    }
    .gateway-nav {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: center;
      margin-bottom: 20px;
    }
    .gateway-nav-link {
      padding: 8px 14px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--text-muted);
      text-decoration: none;
      font-size: 12px;
      font-weight: 600;
    }
  </style>
</head>
<body>
  <div class="page-shell">
    ${macSiteHeaderHtml('send')}
  <div class="container">
    <div class="card">
      <h1>Send files</h1>
      <p class="tagline">Send images and files to another PC or phone on this gateway</p>

      <label for="senderName">Your name (optional)</label>
      <input type="text" id="senderName" placeholder="e.g. Office Laptop" maxlength="64" />

      <label>Send to device</label>
      <button type="button" class="refresh-btn" id="refreshBtn">Refresh list</button>
      <div class="device-list" id="deviceList">
        <div class="device-empty">Looking for online receivers...</div>
      </div>

      <div class="drop-zone disabled" id="dropZone">
        <strong>Select files to send</strong>
        <span>Up to ${MAX_BATCH_FILES} files, 100 MB total — images, PDF, ZIP, etc.</span>
        <input type="file" id="fileInput" class="file-input" multiple />
      </div>

      <button type="button" class="send-btn" id="sendBtn" disabled>Send to selected device</button>
      <p class="progress" id="progressText"></p>
      <div class="toast-success" id="successToast"></div>
      <div class="toast-error" id="errorToast"></div>
    </div>
  </div>
  <script>
    const MAX_BATCH_FILES = ${MAX_BATCH_FILES};
    const SENDER_NAME_KEY = 'airreceive_sender_name';
    const deviceListEl = document.getElementById('deviceList');
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const sendBtn = document.getElementById('sendBtn');
    const progressText = document.getElementById('progressText');
    const successToast = document.getElementById('successToast');
    const errorToast = document.getElementById('errorToast');
    const senderNameInput = document.getElementById('senderName');
    const refreshBtn = document.getElementById('refreshBtn');

    let selectedDeviceId = null;
    let pendingFiles = [];

    senderNameInput.value = localStorage.getItem(SENDER_NAME_KEY) || '';
    senderNameInput.addEventListener('change', () => {
      localStorage.setItem(SENDER_NAME_KEY, senderNameInput.value.trim());
    });

    function showError(msg) {
      successToast.style.display = 'none';
      errorToast.textContent = msg;
      errorToast.style.display = 'block';
    }
    function showSuccess(msg) {
      errorToast.style.display = 'none';
      successToast.textContent = msg;
      successToast.style.display = 'block';
    }

    function updateSendEnabled() {
      const ok = selectedDeviceId && pendingFiles.length > 0;
      sendBtn.disabled = !ok;
      dropZone.classList.toggle('disabled', !selectedDeviceId);
    }

    async function refreshDevices() {
      try {
        const res = await fetch('/api/devices?role=receiver');
        const data = await res.json();
        const receivers = data.receivers || [];
        if (receivers.length === 0) {
          deviceListEl.innerHTML = '<div class="device-empty">No receivers online. Open <strong>/receive</strong> on the target laptop or phone first.</div>';
          selectedDeviceId = null;
          updateSendEnabled();
          return;
        }
        deviceListEl.innerHTML = '';
        receivers.forEach((dev) => {
          const row = document.createElement('label');
          row.className = 'device-option';
          const radio = document.createElement('input');
          radio.type = 'radio';
          radio.name = 'targetDevice';
          radio.value = dev.id;
          if (dev.id === selectedDeviceId) radio.checked = true;
          radio.addEventListener('change', () => {
            selectedDeviceId = dev.id;
            updateSendEnabled();
          });
          const text = document.createElement('span');
          text.textContent = dev.displayName + ' — online';
          row.appendChild(radio);
          row.appendChild(text);
          deviceListEl.appendChild(row);
        });
        if (!selectedDeviceId && receivers.length === 1) {
          selectedDeviceId = receivers[0].id;
          deviceListEl.querySelector('input').checked = true;
          updateSendEnabled();
        }
      } catch (e) {
        deviceListEl.innerHTML = '<div class="device-empty">Could not load devices.</div>';
      }
    }

    dropZone.addEventListener('click', () => {
      if (selectedDeviceId) fileInput.click();
    });
    fileInput.addEventListener('change', () => {
      if (fileInput.files.length) {
        pendingFiles = Array.from(fileInput.files).slice(0, MAX_BATCH_FILES);
        dropZone.querySelector('strong').textContent = pendingFiles.length + ' file(s) selected';
        updateSendEnabled();
      }
    });

    sendBtn.addEventListener('click', async () => {
      if (!selectedDeviceId || pendingFiles.length === 0) return;
      sendBtn.disabled = true;
      progressText.style.display = 'block';
      progressText.textContent = 'Uploading...';
      const formData = new FormData();
      formData.append('target', 'receiver');
      formData.append('targetDeviceId', selectedDeviceId);
      pendingFiles.forEach((f) => formData.append('files', f));
      try {
        const res = await fetch('/upload/batch', { method: 'POST', body: formData });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          showError(data.error || 'Upload failed. Is the receiver still online?');
          sendBtn.disabled = false;
          return;
        }
        showSuccess('Sent ' + (data.count || pendingFiles.length) + ' file(s) successfully.');
        pendingFiles = [];
        fileInput.value = '';
        dropZone.querySelector('strong').textContent = 'Select files to send';
        updateSendEnabled();
      } catch (e) {
        showError('Network error: ' + (e.message || 'Upload failed'));
        sendBtn.disabled = false;
      }
      progressText.style.display = 'none';
    });

    refreshBtn.addEventListener('click', refreshDevices);
    setInterval(refreshDevices, 3000);
    refreshDevices();
  </script>
  </div>
  </div>
</body>
</html>
  `);
});

// iPhone / Safari receive page
app.get('/receive', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AirReceive — Receive photos</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#007aff')}
    .card {
      padding: 32px;
      text-align: center;
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
    }
    h1 { font-size: 24px; margin: 0 0 8px; }
    .tagline { color: var(--text-muted); font-size: 14px; margin-bottom: 20px; }
    .nav-link {
      display: inline-block;
      margin-bottom: 20px;
      padding: 10px 18px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--primary);
      text-decoration: none;
      font-size: 13px;
      font-weight: 600;
    }
    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: 6px 14px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      margin-bottom: 20px;
      background: rgba(239, 68, 68, 0.1);
      border: 1px solid rgba(239, 68, 68, 0.2);
      color: #fc8181;
    }
    .status-badge.connected {
      background: rgba(56, 189, 248, 0.1);
      border-color: rgba(56, 189, 248, 0.25);
      color: var(--primary);
    }
    .status-badge .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: #ff453a; margin-right: 8px;
    }
    .status-badge.connected .dot { background: var(--primary); }
    .batch-wrap {
      display: none;
      margin-top: 20px;
      padding: 16px;
      border-radius: 16px;
      background: rgba(22, 27, 34, 0.8);
      border: 1px solid var(--border-color);
      text-align: left;
    }
    .batch-wrap.visible { display: block; }
    .batch-title {
      font-size: 14px;
      font-weight: 700;
      color: var(--text-main);
      margin-bottom: 12px;
    }
    .thumb-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 8px;
      margin-bottom: 16px;
    }
    .thumb-grid img {
      width: 100%;
      aspect-ratio: 1;
      object-fit: cover;
      border-radius: 8px;
      background: #1c1c1e;
    }
    .action-buttons { margin-top: 4px; }
    .save-all-btn, .download-all-btn {
      display: block;
      width: 100%;
      padding: 14px 20px;
      border-radius: 10px;
      border: none;
      font-weight: 700;
      font-size: 15px;
      text-align: center;
      box-sizing: border-box;
      cursor: pointer;
    }
    .save-all-btn {
      background: #007aff;
      color: #ffffff;
    }
    .download-all-btn {
      margin-top: 10px;
      background: transparent;
      border: 1px solid var(--primary);
      color: var(--primary);
    }
    .save-all-btn:disabled, .download-all-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .btn-subtitle {
      font-size: 11px;
      color: var(--text-muted);
      margin: 6px 0 0;
      line-height: 1.35;
      text-align: center;
    }
    .save-hint {
      font-size: 11px;
      color: var(--text-muted);
      margin-top: 10px;
      line-height: 1.4;
    }
    .thumb-dl {
      display: block;
      margin-top: 6px;
      font-size: 11px;
      font-weight: 600;
      color: var(--primary);
      text-align: center;
      text-decoration: none;
    }
    .toast-success {
      display: none;
      margin-top: 16px;
      padding: 12px;
      border-radius: 12px;
      background: rgba(16, 185, 129, 0.12);
      color: #a7f3d0;
      font-size: 13px;
    }
    .thumb-item {
      cursor: pointer;
      border-radius: 8px;
      overflow: hidden;
    }
    .thumb-item img {
      display: block;
      width: 100%;
      aspect-ratio: 1;
      object-fit: cover;
    }
    .instructions {
      text-align: left;
      margin-top: 24px;
      padding: 16px 20px;
      border-radius: 12px;
      border: 1px solid var(--border-color);
      font-size: 13px;
      color: var(--text-muted);
    }
    .instructions strong { color: var(--text-main); }
    .toast-error {
      display: none;
      margin-top: 16px;
      padding: 12px;
      border-radius: 12px;
      background: rgba(239, 68, 68, 0.12);
      color: #fecaca;
      font-size: 13px;
    }
    .waiting {
      color: var(--text-muted);
      font-size: 14px;
      margin-top: 12px;
    }
    .nav-row { margin-bottom: 16px; }
    .device-identity {
      font-size: 12px;
      color: var(--text-muted);
      margin: 0 0 12px;
    }
    .file-row {
      grid-column: 1 / -1;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background: rgba(13, 17, 23, 0.6);
      margin-bottom: 8px;
    }
    .file-row-name {
      font-size: 13px;
      font-weight: 600;
      word-break: break-all;
      text-align: left;
    }
    .file-row-meta { font-size: 11px; color: var(--text-muted); }
    .visibility-banner {
      display: none;
      margin: 12px 0;
      padding: 10px 14px;
      border-radius: 10px;
      background: rgba(251, 191, 36, 0.12);
      border: 1px solid rgba(251, 191, 36, 0.35);
      color: #fcd34d;
      font-size: 12px;
      line-height: 1.4;
      text-align: left;
    }
    .visibility-banner.visible { display: block; }
    .utility-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: center;
      margin: 12px 0;
    }
    .utility-btn {
      padding: 8px 14px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background: transparent;
      color: var(--primary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    .utility-btn:disabled { opacity: 0.5; cursor: not-allowed; }
  </style>
</head>
<body>
  <div class="page-shell">
    ${macSiteHeaderHtml('receive')}
  <div class="container">
    <div class="card">
      <h1>Receive photos</h1>
      <p class="tagline">Receive on iPhone, PC, or any browser — keep this tab open while sending</p>

      <div class="status-badge" id="statusBadge">
        <span class="dot"></span>
        <span id="statusText">Connecting...</span>
      </div>
      <p class="device-identity" id="deviceIdentity" style="display:none;">You are: <strong id="myDeviceName"></strong></p>

      <div class="visibility-banner" id="visibilityBanner">
        This tab is in the background — transfers may be missed. Return here to stay connected.
      </div>

      <div class="utility-row">
        <button type="button" class="utility-btn" id="wakeLockBtn">Keep screen awake</button>
      </div>

      <p class="waiting" id="waitingText">Waiting for files...</p>

      <div class="batch-wrap" id="batchWrap">
        <div class="batch-title" id="batchTitle">Received photos</div>
        <div class="thumb-grid" id="thumbGrid"></div>
        <div class="action-buttons">
          <button type="button" class="save-all-btn" id="saveAllBtn" disabled>Save all to Photos</button>
          <p class="btn-subtitle">iPhone / iPad — opens Share sheet; choose <strong>Save Images</strong> or <strong>Add to Photos</strong> (Safari recommended)</p>
          <button type="button" class="download-all-btn" id="downloadAllBtn" disabled>Download all files</button>
          <p class="btn-subtitle" id="downloadHint">PC / Mac — use Save to folder (Chrome/Edge) or download files one by one</p>
          <button type="button" class="download-all-btn" id="saveFolderBtn" disabled style="display:none; margin-top:8px;">Save all to folder</button>
        </div>
        <p class="save-hint" id="nonImageHint" style="display:none;">This batch includes non-image files — use Download all (Save to Photos is for images only).</p>
        <p class="save-hint" id="fallbackHint" style="display:none;">On iPhone, tap a thumbnail to save one photo at a time via Share. If batch share fails, try individual thumbnails.</p>
      </div>

      <div class="toast-success" id="successToast"></div>
      <div class="toast-error" id="errorToast"></div>

      <div class="instructions">
        <strong>How to use</strong>
        <ol>
          <li>Keep this tab open (Safari on iPhone, or Chrome / Edge / Firefox on PC).</li>
          <li>On Android AirReceive, enable the gateway (free hosted or your URL): <code id="originCode"></code></li>
          <li>Sender opens <strong>/send</strong> (PC) or Android app, picks your device name, then sends files.</li>
          <li><strong>iPhone:</strong> tap <strong>Save all to Photos</strong> and confirm on the Share sheet.</li>
          <li><strong>PC:</strong> tap <strong>Download all images</strong> and check your Downloads folder.</li>
        </ol>
      </div>
    </div>
  </div>
  <script src="https://cdn.jsdelivr.net/npm/heic2any@0.0.4/dist/heic2any.min.js"></script>
  <script>
    const statusBadge = document.getElementById('statusBadge');
    const statusText = document.getElementById('statusText');
    const batchWrap = document.getElementById('batchWrap');
    const batchTitle = document.getElementById('batchTitle');
    const thumbGrid = document.getElementById('thumbGrid');
    const saveAllBtn = document.getElementById('saveAllBtn');
    const downloadAllBtn = document.getElementById('downloadAllBtn');
    const successToast = document.getElementById('successToast');
    const errorToast = document.getElementById('errorToast');
    const waitingText = document.getElementById('waitingText');
    const fallbackHint = document.getElementById('fallbackHint');
    const nonImageHint = document.getElementById('nonImageHint');
    const deviceIdentity = document.getElementById('deviceIdentity');
    const myDeviceNameEl = document.getElementById('myDeviceName');
    const visibilityBanner = document.getElementById('visibilityBanner');
    const wakeLockBtn = document.getElementById('wakeLockBtn');
    const saveFolderBtn = document.getElementById('saveFolderBtn');
    const downloadHint = document.getElementById('downloadHint');
    document.getElementById('originCode').textContent = window.location.origin;

    const DEVICE_NAME_KEY = 'airreceive_device_name';
    const DEVICE_ID_KEY = 'airreceive_device_id';

    let wakeLock = null;
    let pingInterval = null;

    if ('showDirectoryPicker' in window) {
      saveFolderBtn.style.display = 'block';
      downloadHint.textContent = 'PC / Mac — Save to folder picks one directory (recommended on Chrome/Edge)';
    }

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        visibilityBanner.classList.add('visible');
      } else {
        visibilityBanner.classList.remove('visible');
        if (ws && ws.readyState !== WebSocket.OPEN) {
          connect();
        }
      }
    });

    wakeLockBtn.addEventListener('click', async () => {
      try {
        if (wakeLock) {
          await wakeLock.release();
          wakeLock = null;
          wakeLockBtn.textContent = 'Keep screen awake';
          return;
        }
        if ('wakeLock' in navigator) {
          wakeLock = await navigator.wakeLock.request('screen');
          wakeLockBtn.textContent = 'Release wake lock';
          wakeLock.addEventListener('release', () => {
            wakeLock = null;
            wakeLockBtn.textContent = 'Keep screen awake';
          });
        } else {
          showError('Wake Lock not supported in this browser.');
        }
      } catch (e) {
        showError('Could not enable wake lock: ' + (e.message || 'denied'));
      }
    });

    async function previewBlob(blob, name, type) {
      const lower = (name || '').toLowerCase();
      const isHeic = (type && (type.includes('heic') || type.includes('heif'))) ||
        lower.endsWith('.heic') || lower.endsWith('.heif');
      if (isHeic && typeof heic2any === 'function') {
        try {
          const converted = await heic2any({ blob, toType: 'image/jpeg', quality: 0.85 });
          const out = Array.isArray(converted) ? converted[0] : converted;
          return { blob: out, type: 'image/jpeg', name: name.replace(/\\.heic$/i, '.jpg').replace(/\\.heif$/i, '.jpg') };
        } catch (e) {
          console.warn('HEIC decode failed', e);
        }
      }
      return { blob, type, name };
    }

    function getDeviceName() {
      let name = localStorage.getItem(DEVICE_NAME_KEY);
      if (!name) {
        name = prompt('Enter a name for this device (shown to senders):', 'My Laptop') || 'My Laptop';
        localStorage.setItem(DEVICE_NAME_KEY, name.trim());
      }
      return name.trim();
    }

    let ws = null;
    let reconnectTimer = null;
    let currentBatchId = null;
    let cachedBatchFiles = [];

    function wsUrl() {
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return proto + '//' + window.location.host + '/ws/receiver';
    }

    function setConnected(connected) {
      if (connected) {
        statusBadge.classList.add('connected');
        statusText.textContent = 'Ready to Receive';
      } else {
        statusBadge.classList.remove('connected');
        statusText.textContent = 'Disconnected — Reconnecting...';
      }
    }

    function showError(msg) {
      successToast.style.display = 'none';
      errorToast.textContent = msg;
      errorToast.style.display = 'block';
      setTimeout(() => { errorToast.style.display = 'none'; }, 6000);
    }

    function showSuccess(msg) {
      errorToast.style.display = 'none';
      successToast.textContent = msg;
      successToast.style.display = 'block';
    }

    function isIOS() {
      return /iPhone|iPad|iPod/i.test(navigator.userAgent);
    }

    function usePerThumbDownload() {
      return !isIOS();
    }

    function isImageMime(type, name) {
      if (type && type.startsWith('image/')) return true;
      const lower = (name || '').toLowerCase();
      return /\\.(jpe?g|png|gif|webp|heic|heif|bmp|svg)$/.test(lower);
    }

    function formatBytes(n) {
      if (n < 1024) return n + ' B';
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
      return (n / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function updateBatchActions() {
      const ready = cachedBatchFiles.length > 0;
      const allImages = ready && cachedBatchFiles.every((e) => isImageMime(e.type, e.name));
      saveAllBtn.disabled = !ready || !allImages;
      downloadAllBtn.disabled = !ready;
      saveFolderBtn.disabled = !ready;
      nonImageHint.style.display = ready && !allImages ? 'block' : 'none';
    }

    function mimeFromName(name) {
      const lower = (name || '').toLowerCase();
      if (lower.endsWith('.png')) return 'image/png';
      if (lower.endsWith('.webp')) return 'image/webp';
      if (lower.endsWith('.gif')) return 'image/gif';
      if (lower.endsWith('.heic') || lower.endsWith('.heif')) return 'image/heic';
      return 'image/jpeg';
    }

    async function cleanupBatch() {
      if (!currentBatchId) return;
      try {
        await fetch('/batch/' + currentBatchId, { method: 'DELETE' });
      } catch (e) {
        console.warn('Batch cleanup failed', e);
      }
      currentBatchId = null;
      cachedBatchFiles = [];
    }

    async function shareOneFile(entry) {
      const file = new File([entry.blob], entry.name, { type: entry.blob.type || mimeFromName(entry.name) });
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file] });
        return true;
      }
      return false;
    }

    async function saveAllToPhotos() {
      if (cachedBatchFiles.length === 0) {
        showError('No photos loaded yet.');
        return;
      }
      if (!cachedBatchFiles.every((e) => isImageMime(e.type, e.name))) {
        showError('This batch contains non-image files. Use Download all instead.');
        return;
      }
      saveAllBtn.disabled = true;
      try {
        const shareFiles = cachedBatchFiles.map((entry) =>
          new File([entry.blob], entry.name, { type: entry.blob.type || mimeFromName(entry.name) })
        );
        if (navigator.canShare && navigator.canShare({ files: shareFiles })) {
          await navigator.share({ files: shareFiles });
          await cleanupBatch();
          showSuccess('Done — photos sent to the Share sheet. If you chose Save Images, check your Photos app.');
          saveAllBtn.textContent = 'Saved';
          return;
        }
        // Fallback: share one at a time
        fallbackHint.style.display = 'block';
        let saved = 0;
        for (const entry of cachedBatchFiles) {
          const ok = await shareOneFile(entry);
          if (ok) saved++;
        }
        if (saved > 0) {
          await cleanupBatch();
          showSuccess('Shared ' + saved + ' photo(s). Use Save Images on each sheet if prompted.');
          saveAllBtn.textContent = 'Saved';
        } else {
          showError('Could not open Share sheet. Use Safari on iPhone, or tap a thumbnail to save one photo at a time.');
          saveAllBtn.disabled = false;
        }
      } catch (e) {
        if (e.name === 'AbortError') {
          showError('Share cancelled.');
        } else {
          showError('Save failed: ' + (e.message || 'Unknown error'));
        }
        saveAllBtn.disabled = false;
      }
    }

    async function saveAllToFolder() {
      if (cachedBatchFiles.length === 0) {
        showError('No files loaded yet.');
        return;
      }
      if (!('showDirectoryPicker' in window)) {
        showError('Save to folder requires Chrome or Edge on desktop.');
        return;
      }
      saveFolderBtn.disabled = true;
      try {
        const dirHandle = await window.showDirectoryPicker();
        const count = cachedBatchFiles.length;
        for (const entry of cachedBatchFiles) {
          const fileHandle = await dirHandle.getFileHandle(entry.name, { create: true });
          const writable = await fileHandle.createWritable();
          await writable.write(entry.blob);
          await writable.close();
        }
        await cleanupBatch();
        showSuccess('Saved ' + count + ' file(s) to the selected folder.');
        saveFolderBtn.textContent = 'Saved';
      } catch (e) {
        if (e.name !== 'AbortError') {
          showError('Save to folder failed: ' + (e.message || 'Unknown error'));
        }
        saveFolderBtn.disabled = false;
      }
    }

    async function downloadAllImages() {
      if (cachedBatchFiles.length === 0) {
        showError('No photos loaded yet.');
        return;
      }
      const count = cachedBatchFiles.length;
      downloadAllBtn.disabled = true;
      try {
        for (let i = 0; i < cachedBatchFiles.length; i++) {
          const entry = cachedBatchFiles[i];
          const url = URL.createObjectURL(entry.blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = entry.name || ('photo-' + (i + 1) + '.jpg');
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
          if (i < cachedBatchFiles.length - 1) {
            await new Promise((resolve) => setTimeout(resolve, 300));
          }
        }
        await cleanupBatch();
        showSuccess('Downloaded ' + count + ' file(s). Check your Downloads folder.');
        downloadAllBtn.textContent = 'Downloaded';
      } catch (e) {
        showError('Download failed: ' + (e.message || 'Unknown error'));
        downloadAllBtn.disabled = false;
      }
    }

    saveAllBtn.addEventListener('click', saveAllToPhotos);
    downloadAllBtn.addEventListener('click', downloadAllImages);
    saveFolderBtn.addEventListener('click', saveAllToFolder);

    function connect() {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (ws) {
        try { ws.close(); } catch (e) { /* ignore */ }
        ws = null;
      }

      ws = new WebSocket(wsUrl());

      ws.onopen = () => {
        setConnected(true);
        const reg = {
          type: 'REGISTER',
          displayName: getDeviceName(),
          deviceId: localStorage.getItem(DEVICE_ID_KEY) || undefined
        };
        ws.send(JSON.stringify(reg));
        if (pingInterval) clearInterval(pingInterval);
        pingInterval = setInterval(() => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'PING' }));
          }
        }, 25000);
      };

      ws.onclose = () => {
        setConnected(false);
        if (pingInterval) {
          clearInterval(pingInterval);
          pingInterval = null;
        }
        if (!document.hidden) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      };

      ws.onerror = () => setConnected(false);

      async function loadBatchThumbnails(files) {
        thumbGrid.innerHTML = '';
        cachedBatchFiles = [];
        for (const file of files) {
          try {
            const res = await fetch('/download/' + file.id + '?keep=1');
            if (!res.ok) continue;
            let blob = await res.blob();
            const name = file.name || 'photo.jpg';
            let type = file.mimeType || blob.type || mimeFromName(name);
            const preview = await previewBlob(blob, name, type);
            blob = preview.blob;
            type = preview.type;
            const displayName = preview.name || name;
            const entry = { name: displayName, blob, type, size: file.size || blob.size };
            cachedBatchFiles.push(entry);

            if (!isImageMime(type, displayName)) {
              const row = document.createElement('div');
              row.className = 'file-row';
              const info = document.createElement('div');
              const nameEl = document.createElement('div');
              nameEl.className = 'file-row-name';
              nameEl.textContent = displayName;
              const meta = document.createElement('div');
              meta.className = 'file-row-meta';
              meta.textContent = formatBytes(entry.size);
              info.appendChild(nameEl);
              info.appendChild(meta);
              const dl = document.createElement('a');
              dl.className = 'thumb-dl';
              dl.textContent = 'Download';
              dl.href = URL.createObjectURL(blob);
              dl.download = displayName;
              row.appendChild(info);
              row.appendChild(dl);
              thumbGrid.appendChild(row);
              continue;
            }

            const wrap = document.createElement('div');
            wrap.className = 'thumb-item';
            const img = document.createElement('img');
            img.src = URL.createObjectURL(blob);
            img.alt = displayName;
            wrap.appendChild(img);

            if (usePerThumbDownload()) {
              wrap.title = 'Download ' + displayName;
              const dl = document.createElement('a');
              dl.className = 'thumb-dl';
              dl.textContent = 'Download';
              dl.href = URL.createObjectURL(blob);
              dl.download = displayName;
              dl.addEventListener('click', (e) => e.stopPropagation());
              wrap.appendChild(dl);
            } else {
              wrap.title = 'Tap to save this photo';
              wrap.addEventListener('click', async () => {
                try {
                  const ok = await shareOneFile({ name: displayName, blob, type });
                  if (ok) showSuccess('Use Save Images on the Share sheet for ' + displayName);
                  else showError('Share not supported. Try Safari.');
                } catch (e) {
                  if (e.name !== 'AbortError') showError(e.message || 'Share failed');
                }
              });
            }
            thumbGrid.appendChild(wrap);
          } catch (e) {
            console.warn('Thumbnail failed for', file.id, e);
          }
        }
        updateBatchActions();
      }

      async function handleBatch(msg) {
        waitingText.style.display = 'none';
        successToast.style.display = 'none';
        currentBatchId = msg.batchId;
        const count = msg.count || (msg.files && msg.files.length) || 0;
        batchTitle.textContent = count + ' file' + (count === 1 ? '' : 's') + ' ready';
        saveAllBtn.textContent = 'Save all ' + count + ' photos to Photos';
        downloadAllBtn.textContent = 'Download all ' + count + ' files';
        saveAllBtn.disabled = true;
        downloadAllBtn.disabled = true;
        batchWrap.classList.add('visible');
        if (msg.files && msg.files.length) {
          await loadBatchThumbnails(msg.files);
        }
      }

      ws.onmessage = async (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'REGISTERED') {
            localStorage.setItem(DEVICE_ID_KEY, msg.deviceId);
            localStorage.setItem(DEVICE_NAME_KEY, msg.displayName);
            deviceIdentity.style.display = 'block';
            myDeviceNameEl.textContent = msg.displayName;
            return;
          }
          if (msg.type === 'NOTIFY_BATCH') {
            await handleBatch(msg);
            return;
          }
          if (msg.type === 'NOTIFY_UPLOAD') {
            await handleBatch({
              type: 'NOTIFY_BATCH',
              batchId: msg.batchId || msg.id,
              count: 1,
              files: [{ id: msg.id, name: msg.name, size: msg.size, mimeType: msg.mimeType }]
            });
          }
        } catch (e) {
          showError('Failed to receive photos: ' + e.message);
        }
      };
    }

    connect();
  </script>
  </div>
  </div>
</body>
</html>
  `);
});

// Handle WebSocket upgrades
server.on('upgrade', (request, socket, head) => {
  const pathname = new URL(request.url, `http://${request.headers.host}`).pathname;

  if (pathname === '/ws/phone') {
    wssPhone.handleUpgrade(request, socket, head, (ws) => {
      setupDeviceSocket(ws, 'phone', 'Phone app');
    });
  } else if (pathname === '/ws/receiver') {
    wssReceiver.handleUpgrade(request, socket, head, (ws) => {
      setupDeviceSocket(ws, 'receiver', 'Receiver browser');
    });
  } else {
    socket.destroy();
  }
});

// Start the server
server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Gateway] Server active on port ${PORT}`);
  console.log(`[Gateway] Connect AirReceive client to: http://localhost:${PORT}`);
});
