const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const fs = require('fs');

const MAX_BATCH_FILES = 50;
const MAX_BATCH_BYTES = 100 * 1024 * 1024; // 100 MB
const FILE_TTL_MS = 5 * 60 * 1000;
const TRANSFER_SESSION_TTL_MS = 5 * 60 * 1000;
const CHAT_TTL_MS = 24 * 60 * 60 * 1000;
const CHAT_MAX_TEXT_LEN = 2000;
const GLOBAL_CHAT_PEER_ID = '__global__';
const GLOBAL_CHAT_MAX_MESSAGES = 200;

const app = express();
app.use(express.json());
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

// deviceId -> { ws, role, displayName, connectedAt, passwordProtection }
const deviceRegistry = new Map();
// sessionId -> transfer session
const transferSessions = new Map();
// messageId -> { messageId, fromDeviceId, toDeviceId, fromDisplayName, text, sentAt }
const chatMessages = new Map();
// deviceId -> messageId[]
const pendingInbox = new Map();
// ordered global room message ids (recent history for newcomers)
const globalChatLog = [];

function generatePin() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function getDeviceEntry(targetDeviceId) {
  if (!targetDeviceId) return null;
  const entry = deviceRegistry.get(targetDeviceId);
  if (!entry || entry.ws.readyState !== WebSocket.OPEN) return null;
  return entry;
}

function isPasswordProtectionRequired(targetDeviceId) {
  const entry = getDeviceEntry(targetDeviceId);
  return entry ? !!entry.passwordProtection : false;
}

function createTransferRequest(targetDeviceId, senderLabel, senderDeviceId) {
  const target = (targetDeviceId || '').trim() || null;
  const sender = (senderDeviceId || '').trim() || null;
  if (target && sender && target === sender) {
    return { error: 'You cannot send files to your own device.', status: 400 };
  }

  const entry = target ? getDeviceEntry(target) : null;
  if (target && !entry) {
    return { error: 'Target device is offline or not found.', status: 404 };
  }

  const sessionId = uuidv4();
  const expiresAt = Date.now() + TRANSFER_SESSION_TTL_MS;
  const passwordRequired = entry ? !!entry.passwordProtection : false;

  if (!passwordRequired) {
    const uploadToken = uuidv4();
    transferSessions.set(sessionId, {
      sessionId,
      targetDeviceId,
      status: 'approved',
      uploadToken,
      pin: null,
      expiresAt,
      senderLabel: senderLabel || null
    });
    return { passwordRequired: false, sessionId, uploadToken };
  }

  const pin = generatePin();
  transferSessions.set(sessionId, {
    sessionId,
    targetDeviceId,
    status: 'pending',
    uploadToken: null,
    pin,
    expiresAt,
    senderLabel: senderLabel || null
  });

  notifyDevice(target, JSON.stringify({
    type: 'AUTH_REQUIRED',
    sessionId,
    senderLabel: senderLabel || 'A sender'
  }));

  console.log(`[Auth] PIN session ${sessionId} for device ${targetDeviceId}`);
  return { passwordRequired: true, sessionId, pin };
}

function verifyTransferPin(sessionId, pin, targetDeviceId) {
  const session = transferSessions.get(sessionId);
  if (!session) {
    return { ok: false, status: 404, error: 'Session not found.' };
  }
  if (Date.now() > session.expiresAt) {
    transferSessions.delete(sessionId);
    return { ok: false, status: 410, error: 'Session expired.' };
  }
  if (session.targetDeviceId && targetDeviceId && session.targetDeviceId !== targetDeviceId) {
    return { ok: false, status: 403, error: 'Wrong receiver for this session.' };
  }
  if (String(pin).trim() !== String(session.pin)) {
    return { ok: false, status: 401, error: 'Incorrect code.' };
  }
  session.status = 'approved';
  session.uploadToken = uuidv4();
  return { ok: true, status: 'approved', uploadToken: session.uploadToken };
}

function getTransferSessionStatus(sessionId) {
  const session = transferSessions.get(sessionId);
  if (!session) return { status: 'expired' };
  if (Date.now() > session.expiresAt) {
    transferSessions.delete(sessionId);
    return { status: 'expired' };
  }
  if (session.status === 'approved') {
    return { status: 'approved', uploadToken: session.uploadToken };
  }
  return { status: 'pending' };
}

function getSenderDeviceId(req) {
  return String(req.body?.senderDeviceId || req.headers['x-sender-device-id'] || '').trim();
}

function rejectSelfTransfer(targetDeviceId, senderDeviceId) {
  const target = (targetDeviceId || '').trim();
  const sender = (senderDeviceId || '').trim();
  if (target && sender && target === sender) {
    return 'You cannot send files to your own device.';
  }
  return null;
}

function getTransferAuthFromRequest(req) {
  return {
    sessionId: String(req.body?.sessionId || req.headers['x-session-id'] || '').trim(),
    uploadToken: String(req.body?.uploadToken || req.headers['x-upload-token'] || '').trim()
  };
}

function validateUploadToken(sessionId, uploadToken, targetDeviceId) {
  if (!isPasswordProtectionRequired(targetDeviceId)) {
    return { ok: true };
  }
  const sid = (sessionId || '').trim();
  const token = (uploadToken || '').trim();
  if (!sid || !token) {
    return { ok: false, error: 'Transfer password required. Request authorization first.' };
  }
  const session = transferSessions.get(sid);
  if (!session) {
    return { ok: false, error: 'Session expired or not found.' };
  }
  if (Date.now() > session.expiresAt) {
    transferSessions.delete(sid);
    return { ok: false, error: 'Session expired.' };
  }
  if (session.status !== 'approved' || session.uploadToken !== token) {
    return { ok: false, error: 'Invalid or unapproved transfer session.' };
  }
  if (session.targetDeviceId && targetDeviceId && session.targetDeviceId !== targetDeviceId) {
    return { ok: false, error: 'Session target mismatch.' };
  }
  return { ok: true };
}

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

function formatGlobalChatMessagePayload(msg) {
  return {
    type: 'GLOBAL_CHAT_MESSAGE',
    messageId: msg.messageId,
    fromDeviceId: msg.fromDeviceId,
    fromDisplayName: msg.fromDisplayName,
    text: msg.text,
    sentAt: msg.sentAt
  };
}

function listGlobalChatMessages() {
  const messages = [];
  for (const messageId of globalChatLog) {
    const msg = chatMessages.get(messageId);
    if (!msg) continue;
    messages.push({
      messageId: msg.messageId,
      fromDeviceId: msg.fromDeviceId,
      fromDisplayName: msg.fromDisplayName,
      text: msg.text,
      sentAt: msg.sentAt
    });
  }
  return messages;
}

function broadcastGlobalChat(chatMsg) {
  const payload = JSON.stringify(formatGlobalChatMessagePayload(chatMsg));
  for (const entry of deviceRegistry.values()) {
    if (entry.ws.readyState === WebSocket.OPEN) {
      entry.ws.send(payload);
    }
  }
}

function storeGlobalChatMessage(fromDeviceId, fromDisplayName, text) {
  const messageId = uuidv4();
  const sentAt = Date.now();
  const chatMsg = {
    messageId,
    fromDeviceId,
    toDeviceId: GLOBAL_CHAT_PEER_ID,
    fromDisplayName,
    text,
    sentAt,
    isGlobal: true
  };
  chatMessages.set(messageId, chatMsg);
  globalChatLog.push(messageId);
  while (globalChatLog.length > GLOBAL_CHAT_MAX_MESSAGES) {
    const oldId = globalChatLog.shift();
    if (oldId) chatMessages.delete(oldId);
  }
  return chatMsg;
}

function handleGlobalChatSend(ws, msg) {
  if (!ws.isRegistered || !ws.deviceId) {
    ws.send(JSON.stringify({ type: 'GLOBAL_CHAT_ERROR', error: 'Not registered.' }));
    return;
  }
  const text = String(msg.text || '').trim();
  const clientMessageId = msg.clientMessageId || null;
  if (!text) {
    ws.send(JSON.stringify({ type: 'GLOBAL_CHAT_ERROR', error: 'Message text cannot be empty.', clientMessageId }));
    return;
  }
  if (text.length > CHAT_MAX_TEXT_LEN) {
    ws.send(JSON.stringify({
      type: 'GLOBAL_CHAT_ERROR',
      error: `Message is too long (max ${CHAT_MAX_TEXT_LEN} characters).`,
      clientMessageId
    }));
    return;
  }
  const senderEntry = deviceRegistry.get(ws.deviceId);
  const fromDisplayName = senderEntry?.displayName || 'Unknown';
  const chatMsg = storeGlobalChatMessage(ws.deviceId, fromDisplayName, text);
  broadcastGlobalChat(chatMsg);
  ws.send(JSON.stringify({
    type: 'GLOBAL_CHAT_SENT',
    clientMessageId,
    messageId: chatMsg.messageId,
    status: 'delivered'
  }));
}

function formatChatMessagePayload(msg) {
  return {
    type: 'CHAT_MESSAGE',
    messageId: msg.messageId,
    fromDeviceId: msg.fromDeviceId,
    fromDisplayName: msg.fromDisplayName,
    text: msg.text,
    sentAt: msg.sentAt
  };
}

function purgeExpiredChatMessages() {
  const now = Date.now();
  for (const [messageId, msg] of chatMessages.entries()) {
    if (now - msg.sentAt > CHAT_TTL_MS) {
      chatMessages.delete(messageId);
    }
  }
  for (let i = globalChatLog.length - 1; i >= 0; i--) {
    const messageId = globalChatLog[i];
    if (!chatMessages.has(messageId)) {
      globalChatLog.splice(i, 1);
    }
  }
  for (const [deviceId, inbox] of pendingInbox.entries()) {
    const kept = inbox.filter((id) => chatMessages.has(id));
    if (kept.length === 0) {
      pendingInbox.delete(deviceId);
    } else if (kept.length !== inbox.length) {
      pendingInbox.set(deviceId, kept);
    }
  }
}

function queueChatForDevice(deviceId, messageId) {
  let inbox = pendingInbox.get(deviceId);
  if (!inbox) {
    inbox = [];
    pendingInbox.set(deviceId, inbox);
  }
  inbox.push(messageId);
}

function flushPendingInbox(deviceId) {
  const inbox = pendingInbox.get(deviceId);
  if (!inbox || inbox.length === 0) return;
  const remaining = [];
  for (const messageId of inbox) {
    const msg = chatMessages.get(messageId);
    if (!msg) continue;
    const delivered = notifyDevice(deviceId, JSON.stringify(formatChatMessagePayload(msg)));
    if (!delivered) remaining.push(messageId);
  }
  if (remaining.length > 0) {
    pendingInbox.set(deviceId, remaining);
  } else {
    pendingInbox.delete(deviceId);
  }
}

function listChatPeers(excludeDeviceId) {
  const exclude = (excludeDeviceId || '').trim();
  const peers = [];
  for (const [id, entry] of deviceRegistry.entries()) {
    if (entry.ws.readyState !== WebSocket.OPEN) continue;
    if (exclude && id === exclude) continue;
    peers.push({
      id,
      displayName: entry.displayName,
      role: entry.role,
      roleLabel: entry.role === 'phone' ? 'Android' : 'Browser',
      connectedAt: entry.connectedAt
    });
  }
  peers.sort((a, b) => a.displayName.localeCompare(b.displayName));
  return peers;
}

function handleChatSend(ws, msg) {
  if (!ws.isRegistered || !ws.deviceId) {
    ws.send(JSON.stringify({ type: 'CHAT_ERROR', error: 'Not registered.' }));
    return;
  }
  const toDeviceId = String(msg.toDeviceId || '').trim();
  const text = String(msg.text || '').trim();
  const clientMessageId = msg.clientMessageId || null;

  if (!toDeviceId) {
    ws.send(JSON.stringify({ type: 'CHAT_ERROR', error: 'Recipient device is required.', clientMessageId }));
    return;
  }
  if (toDeviceId === ws.deviceId) {
    ws.send(JSON.stringify({ type: 'CHAT_ERROR', error: 'You cannot message your own device.', clientMessageId }));
    return;
  }
  if (!text) {
    ws.send(JSON.stringify({ type: 'CHAT_ERROR', error: 'Message text cannot be empty.', clientMessageId }));
    return;
  }
  if (text.length > CHAT_MAX_TEXT_LEN) {
    ws.send(JSON.stringify({
      type: 'CHAT_ERROR',
      error: `Message is too long (max ${CHAT_MAX_TEXT_LEN} characters).`,
      clientMessageId
    }));
    return;
  }

  const senderEntry = deviceRegistry.get(ws.deviceId);
  const fromDisplayName = senderEntry?.displayName || 'Unknown';
  const messageId = uuidv4();
  const sentAt = Date.now();
  const chatMsg = {
    messageId,
    fromDeviceId: ws.deviceId,
    toDeviceId,
    fromDisplayName,
    text,
    sentAt
  };
  chatMessages.set(messageId, chatMsg);

  const delivered = notifyDevice(toDeviceId, JSON.stringify(formatChatMessagePayload(chatMsg)));
  if (!delivered) {
    queueChatForDevice(toDeviceId, messageId);
  }

  ws.send(JSON.stringify({
    type: 'CHAT_SENT',
    clientMessageId,
    messageId,
    status: delivered ? 'delivered' : 'queued'
  }));
}

function listDevices(roleFilter, excludeDeviceId) {
  const exclude = (excludeDeviceId || '').trim();
  const receivers = [];
  const phones = [];
  for (const [id, entry] of deviceRegistry.entries()) {
    if (entry.ws.readyState !== WebSocket.OPEN) continue;
    if (exclude && id === exclude) continue;
    const item = {
      id,
      displayName: entry.displayName,
      connectedAt: entry.connectedAt,
      passwordProtection: !!entry.passwordProtection
    };
    if (entry.role === 'receiver') receivers.push(item);
    else if (entry.role === 'phone') phones.push(item);
  }
  if (roleFilter === 'receiver') return { receivers, phones: [] };
  if (roleFilter === 'phone') return { receivers: [], phones };
  return { receivers, phones };
}

function completeRegistration(ws, role, displayName, reconnectDeviceId, passwordProtection = false) {
  if (ws.deviceId) {
    deviceRegistry.delete(ws.deviceId);
  }
  const deviceId = reconnectDeviceId || uuidv4();
  const name = (displayName || '').trim() || (role === 'phone' ? 'Android Phone' : 'Browser');
  ws.deviceId = deviceId;
  ws.deviceRole = role;
  ws.isRegistered = true;
  ws.passwordProtection = !!passwordProtection;
  deviceRegistry.set(deviceId, {
    ws,
    role,
    displayName: name,
    connectedAt: Date.now(),
    passwordProtection: !!passwordProtection
  });
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'REGISTERED',
      deviceId,
      displayName: name,
      passwordProtection: !!passwordProtection
    }));
  }
  console.log(`[WebSocket] Registered ${role} "${name}" (${deviceId})`);
  flushPendingInbox(deviceId);
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
          msg.deviceId || null,
          msg.passwordProtection
        );
      } else if (msg.type === 'SET_PASSWORD_PROTECTION' && ws.isRegistered && ws.deviceId) {
        const entry = deviceRegistry.get(ws.deviceId);
        if (entry) {
          entry.passwordProtection = !!msg.passwordProtection;
          ws.passwordProtection = entry.passwordProtection;
          console.log(`[WebSocket] ${ws.deviceId} passwordProtection=${entry.passwordProtection}`);
        }
      } else if (msg.type === 'CHAT_SEND' && ws.isRegistered) {
        handleChatSend(ws, msg);
      } else if (msg.type === 'GLOBAL_CHAT_SEND' && ws.isRegistered) {
        handleGlobalChatSend(ws, msg);
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
  for (const [sessionId, session] of transferSessions.entries()) {
    if (now > session.expiresAt) {
      transferSessions.delete(sessionId);
    }
  }
  purgeExpiredChatMessages();
}, 60 * 1000);

// Static assets (e.g. Buy Me a Coffee QR)
app.use('/docs', express.static(path.join(__dirname, 'docs')));

// List online registered devices
app.get('/api/devices', (req, res) => {
  const role = req.query.role;
  const exclude = (req.query.exclude || '').trim() || null;
  const lists = listDevices(role, exclude);
  res.json(lists);
});

app.get('/api/chat/peers', (req, res) => {
  const exclude = (req.query.exclude || '').trim() || null;
  res.json({ peers: listChatPeers(exclude) });
});

app.get('/api/chat/global', (req, res) => {
  res.json({ messages: listGlobalChatMessages() });
});

app.get('/api/chat/pending/:deviceId', (req, res) => {
  const deviceId = (req.params.deviceId || '').trim();
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }
  const inbox = pendingInbox.get(deviceId) || [];
  const messages = [];
  for (const messageId of inbox) {
    const msg = chatMessages.get(messageId);
    if (!msg) continue;
    messages.push({
      messageId: msg.messageId,
      fromDeviceId: msg.fromDeviceId,
      fromDisplayName: msg.fromDisplayName,
      text: msg.text,
      sentAt: msg.sentAt
    });
  }
  pendingInbox.delete(deviceId);
  res.json({ messages });
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

// Transfer password protection
app.post('/api/transfer/request', (req, res) => {
  const targetDeviceId = (req.body.targetDeviceId || '').trim() || null;
  const senderLabel = (req.body.senderLabel || '').trim() || null;
  const senderDeviceId = (req.body.senderDeviceId || '').trim() || null;
  const result = createTransferRequest(targetDeviceId, senderLabel, senderDeviceId);
  if (result.error) {
    return res.status(result.status || 400).json({ error: result.error });
  }
  res.json(result);
});

app.post('/api/transfer/verify', (req, res) => {
  const sessionId = (req.body.sessionId || '').trim();
  const pin = req.body.pin;
  const targetDeviceId = (req.body.targetDeviceId || '').trim() || null;
  if (!sessionId || pin === undefined || pin === null) {
    return res.status(400).json({ error: 'sessionId and pin are required.' });
  }
  const result = verifyTransferPin(sessionId, pin, targetDeviceId);
  if (!result.ok) {
    return res.status(result.status || 400).json({ error: result.error });
  }
  res.json({ status: result.status, uploadToken: result.uploadToken });
});

app.get('/api/transfer/pending/:deviceId', (req, res) => {
  const deviceId = (req.params.deviceId || '').trim();
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId is required.' });
  }
  const pending = [];
  for (const [sessionId, session] of transferSessions.entries()) {
    if (
      session.status === 'pending' &&
      session.targetDeviceId === deviceId &&
      Date.now() <= session.expiresAt
    ) {
      pending.push({
        sessionId,
        senderLabel: session.senderLabel || 'A sender'
      });
    }
  }
  res.json({ pending });
});

app.get('/api/transfer/:sessionId', (req, res) => {
  res.json(getTransferSessionStatus(req.params.sessionId));
});

// Upload route
app.post('/upload', upload.single('file'), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No file provided' });
  }

  const target = (req.body.target === 'receiver') ? 'receiver' : 'phone';
  const targetDeviceId = (req.body.targetDeviceId || '').trim() || null;
  const selfError = rejectSelfTransfer(targetDeviceId, getSenderDeviceId(req));
  if (selfError) {
    try { if (fs.existsSync(req.file.path)) fs.unlinkSync(req.file.path); } catch (e) { /* ignore */ }
    return res.status(400).json({ error: selfError });
  }
  const transferAuth = getTransferAuthFromRequest(req);
  const authCheck = validateUploadToken(transferAuth.sessionId, transferAuth.uploadToken, targetDeviceId);
  if (!authCheck.ok) {
    try { if (fs.existsSync(req.file.path)) fs.unlinkSync(req.file.path); } catch (e) { /* ignore */ }
    return res.status(403).json({ error: authCheck.error });
  }

  const fileInfo = registerUploadedFile(req.file);
  const fileId = fileInfo.id;
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
  const selfError = rejectSelfTransfer(targetDeviceId, getSenderDeviceId(req));
  if (selfError) {
    for (const f of files) {
      try { if (fs.existsSync(f.path)) fs.unlinkSync(f.path); } catch (e) { /* ignore */ }
    }
    return res.status(400).json({ error: selfError });
  }
  const transferAuth = getTransferAuthFromRequest(req);
  const authCheck = validateUploadToken(transferAuth.sessionId, transferAuth.uploadToken, targetDeviceId);
  if (!authCheck.ok) {
    for (const f of files) {
      try { if (fs.existsSync(f.path)) fs.unlinkSync(f.path); } catch (e) { /* ignore */ }
    }
    return res.status(403).json({ error: authCheck.error });
  }

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

function gatewayBatchUtilsJs() {
  return `
    const MAX_BATCH_FILES = ${MAX_BATCH_FILES};
    const MAX_BATCH_BYTES = ${MAX_BATCH_BYTES};
    function chunkFiles(files) {
      const chunks = [];
      let current = [];
      let currentBytes = 0;
      for (const file of files) {
        const wouldExceed = current.length > 0 && (
          current.length >= MAX_BATCH_FILES ||
          (file.size > 0 && currentBytes + file.size > MAX_BATCH_BYTES)
        );
        if (wouldExceed) {
          chunks.push(current);
          current = [];
          currentBytes = 0;
        }
        current.push(file);
        if (file.size > 0) currentBytes += file.size;
      }
      if (current.length) chunks.push(current);
      return chunks;
    }
  `;
}

function transferAuthClientJs() {
  return `
    const SENDER_DEVICE_ID_KEY = 'airreceive_device_id';
    function getSenderDeviceId() {
      try { return localStorage.getItem(SENDER_DEVICE_ID_KEY) || ''; } catch (e) { return ''; }
    }
    async function requestTransferAuth(targetDeviceId, senderLabel) {
      const senderDeviceId = getSenderDeviceId();
      const res = await fetch('/api/transfer/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetDeviceId, senderLabel, senderDeviceId: senderDeviceId || undefined })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || 'Authorization request failed');
      return data;
    }
    async function pollUntilTransferApproved(sessionId, onWaiting) {
      const deadline = Date.now() + 5 * 60 * 1000;
      while (Date.now() < deadline) {
        const res = await fetch('/api/transfer/' + encodeURIComponent(sessionId));
        const data = await res.json().catch(() => ({}));
        if (data.status === 'approved' && data.uploadToken) {
          return { sessionId, uploadToken: data.uploadToken };
        }
        if (data.status === 'expired') {
          throw new Error('Transfer code expired. Try again.');
        }
        if (onWaiting) onWaiting();
        await new Promise((r) => setTimeout(r, 1500));
      }
      throw new Error('Timed out waiting for receiver to enter the code.');
    }
    async function ensureTransferAuth(targetDeviceId, senderLabel, onPinShown, onWaiting) {
      const auth = await requestTransferAuth(targetDeviceId, senderLabel);
      if (!auth.passwordRequired) {
        return { sessionId: auth.sessionId, uploadToken: auth.uploadToken };
      }
      if (onPinShown) onPinShown(auth.pin, auth.sessionId);
      return pollUntilTransferApproved(auth.sessionId, onWaiting);
    }
  `;
}

function airReceiveLogoSvg(sizeClass = 'airreceive-logo') {
  return `<svg class="${sizeClass}" viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <path fill="#007AFF" d="M54,20 A34,34 0 1,1 53.9,20 Z"/>
    <path fill="none" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round" stroke-opacity="0.35" d="M34,42 A24,24 0 0,1 74,42"/>
    <path fill="none" stroke="#FFFFFF" stroke-width="3.5" stroke-linecap="round" d="M40,48 A18,18 0 0,1 68,48"/>
    <path fill="#FFFFFF" d="M54,52 L54,72 L68,72 L68,68 L58,68 L58,52 Z"/>
    <path fill="#FFFFFF" fill-opacity="0.9" d="M46,56 L46,64 L50,64 L50,56 Z"/>
  </svg>`;
}

function airReceiveFaviconLink() {
  return '<link rel="icon" type="image/svg+xml" href="/docs/airreceive-logo.svg"><link rel="apple-touch-icon" href="/docs/airreceive-logo.svg">';
}

function macThemeBootScript() {
  return `(function(){try{var k='airreceive-theme';var t=localStorage.getItem(k);var d=t? t==='dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;document.documentElement.setAttribute('data-theme',d?'dark':'light');var btn=document.getElementById('theme-toggle-btn');if(btn)btn.textContent=d?'☀️':'🌙';window.__toggleAirReceiveTheme=function(){var next=document.documentElement.getAttribute('data-theme')==='dark'?'light':'dark';document.documentElement.setAttribute('data-theme',next);localStorage.setItem(k,next);if(btn)btn.textContent=next==='dark'?'☀️':'🌙';};}catch(e){}})();`;
}

function macSiteHeaderHtml(activeNav) {
  return `
    <div class="site-chrome">
      <header class="site-header">
        <div class="site-brand">
          ${airReceiveLogoSvg('site-logo-svg')}
          <div>
            <div class="site-title">AirReceive</div>
            <div class="site-subtitle">Support Maverick for a virtual cookie!</div>
          </div>
        </div>
        <button type="button" id="theme-toggle-btn" class="theme-toggle" onclick="window.__toggleAirReceiveTheme()" aria-label="Toggle light or dark theme">☀️</button>
      </header>
      ${gatewayNavHtml(activeNav)}
    </div>
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
      --mac-glass: rgba(44, 44, 46, 0.88);
      --mac-input-bg: #2c2c2e;
      --mac-elevated: rgba(58, 58, 60, 0.9);
      --mac-shadow: rgba(0, 0, 0, 0.35);
      --mac-hover: rgba(255, 255, 255, 0.06);
      --toast-error-bg: rgba(255, 69, 58, 0.15);
      --toast-error-text: #ff9f9a;
      --toast-success-bg: rgba(48, 209, 88, 0.15);
      --toast-success-text: #7ddea0;
      --toast-warn-bg: rgba(255, 159, 10, 0.15);
      --toast-warn-text: #ffc56d;
      --mac-radius: 12px;
      --mac-radius-lg: 16px;
      --primary: ${accent};
      --accent: ${accent};
      --bg-color: var(--mac-content);
      --card-bg: var(--mac-glass);
      --border-color: rgba(255, 255, 255, 0.1);
      --text-main: var(--mac-label);
      --text-muted: var(--mac-label-secondary);
      --btn-on-primary: #ffffff;
    }
    [data-theme="light"] {
      --mac-window: #ffffff;
      --mac-content: #f2f2f7;
      --mac-secondary: #ffffff;
      --mac-tertiary: #e5e5ea;
      --mac-label: #000000;
      --mac-label-secondary: rgba(60, 60, 67, 0.65);
      --mac-glass: rgba(255, 255, 255, 0.94);
      --mac-input-bg: #ffffff;
      --mac-elevated: #ffffff;
      --mac-shadow: rgba(0, 0, 0, 0.08);
      --mac-hover: rgba(0, 0, 0, 0.04);
      --toast-error-bg: rgba(255, 69, 58, 0.12);
      --toast-error-text: #c62828;
      --toast-success-bg: rgba(48, 209, 88, 0.14);
      --toast-success-text: #1b7d3e;
      --toast-warn-bg: rgba(255, 159, 10, 0.14);
      --toast-warn-text: #9a6700;
      --border-color: rgba(0, 0, 0, 0.1);
      --text-main: var(--mac-label);
      --text-muted: var(--mac-label-secondary);
      --btn-on-primary: #ffffff;
    }
    * { box-sizing: border-box; }
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
    .page-shell {
      width: 100%;
      max-width: 720px;
      margin: 0 auto;
      padding: 0 16px 32px;
    }
    .site-chrome {
      position: sticky;
      top: 0;
      z-index: 200;
      width: 100%;
      margin: 0 -16px 20px;
      padding: 12px 16px 10px;
      background: var(--mac-window);
      border-bottom: 1px solid var(--border-color);
    }
    .site-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 10px;
    }
    .site-brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .site-logo-svg, .airreceive-logo {
      flex-shrink: 0;
      width: 36px;
      height: 36px;
      display: block;
    }
    .site-title { font-size: 17px; font-weight: 600; color: var(--text-main); line-height: 1.2; }
    .site-subtitle {
      font-size: 11px; color: var(--text-muted); margin-top: 2px;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .theme-toggle {
      flex-shrink: 0;
      border: 1px solid var(--border-color);
      background: var(--mac-secondary);
      border-radius: 8px;
      padding: 8px 10px;
      cursor: pointer;
      font-size: 16px;
      line-height: 1;
    }
    .gateway-nav {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      width: 100%;
      padding: 4px;
      background: var(--mac-secondary);
      border-radius: var(--mac-radius);
      border: 1px solid var(--border-color);
    }
    .gateway-nav-link {
      flex: 1 1 calc(33.333% - 4px);
      min-width: 88px;
      padding: 8px 6px;
      border-radius: 8px;
      border: none;
      color: var(--text-muted);
      text-decoration: none;
      font-size: 11px;
      font-weight: 600;
      text-align: center;
      line-height: 1.25;
      transition: color 0.15s, background 0.15s;
    }
    @media (min-width: 520px) {
      .gateway-nav-link { flex: 1 1 auto; font-size: 12px; padding: 8px 10px; }
    }
    .gateway-nav-link:hover { color: var(--primary); background: var(--mac-hover); }
    .gateway-nav-link.active {
      color: var(--primary);
      background: var(--mac-tertiary);
      box-shadow: inset 0 0 0 1px var(--border-color);
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
      box-shadow: 0 8px 24px var(--mac-shadow);
      color: var(--text-main);
    }
    h1 { font-size: 22px; font-weight: 600; margin: 0 0 8px; letter-spacing: -0.3px; color: var(--text-main); }
    .tagline { color: var(--text-muted); font-size: 13px; margin-bottom: 20px; line-height: 1.45; }
    input[type="text"], input[type="url"], input:not([type]) {
      width: 100%;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background: var(--mac-input-bg);
      color: var(--text-main);
      font-size: 14px;
    }
    label { font-size: 12px; font-weight: 600; color: var(--text-muted); display: block; margin-bottom: 6px; text-align: left; }
    .btn-primary, .send-btn, .save-all-btn {
      width: 100%;
      padding: 14px;
      border: none;
      border-radius: 10px;
      background: var(--primary);
      color: var(--btn-on-primary);
      font-weight: 700;
      font-size: 15px;
      cursor: pointer;
    }
    .btn-primary:disabled, .send-btn:disabled, .save-all-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-secondary, .download-all-btn, .refresh-btn, .utility-btn {
      padding: 8px 14px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background: transparent;
      color: var(--primary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    .toast-error { background: var(--toast-error-bg); color: var(--toast-error-text); }
    .toast-success { background: var(--toast-success-bg); color: var(--toast-success-text); }
    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: 6px 14px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      background: var(--toast-error-bg);
      border: 1px solid var(--border-color);
      color: var(--toast-error-text);
    }
    .status-badge.connected {
      background: var(--toast-success-bg);
      color: var(--toast-success-text);
    }
    .status-badge .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--mac-red); margin-right: 8px;
    }
    .status-badge.connected .dot { background: var(--primary); }
    .device-list {
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 8px;
      background: var(--mac-input-bg);
      text-align: left;
    }
    .device-option { display: flex; align-items: center; padding: 10px; border-radius: 8px; cursor: pointer; }
    .device-option:hover { background: var(--mac-hover); }
    .device-empty { color: var(--text-muted); font-size: 13px; padding: 12px; text-align: center; }
    .drop-zone {
      border: 2px dashed var(--border-color);
      border-radius: var(--mac-radius-lg);
      padding: 28px;
      text-align: center;
      cursor: pointer;
      background: var(--mac-hover);
    }
    .drop-zone.disabled { opacity: 0.45; pointer-events: none; }
    code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
  `;
}

function floatingChatWidgetCss() {
  return `
    .ar-chat-fab {
      position: fixed;
      bottom: 20px;
      right: 20px;
      z-index: 1000;
      width: 56px;
      height: 56px;
      border-radius: 50%;
      border: none;
      background: #34c759;
      color: #fff;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
      box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
      display: flex;
      align-items: center;
      justify-content: center;
      transition: transform 0.15s, opacity 0.15s;
    }
    .ar-chat-fab:hover { transform: scale(1.05); }
    .ar-chat-fab.hidden { display: none; }
    .ar-chat-fab-badge {
      position: absolute;
      top: -2px;
      right: -2px;
      min-width: 18px;
      height: 18px;
      padding: 0 5px;
      border-radius: 9px;
      background: #ff3b30;
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      line-height: 18px;
      text-align: center;
      display: none;
    }
    .ar-chat-fab-badge.visible { display: block; }
    .ar-chat-panel {
      position: fixed;
      bottom: 20px;
      right: 20px;
      z-index: 1001;
      width: min(380px, calc(100vw - 32px));
      height: min(520px, calc(100vh - 40px));
      display: none;
      flex-direction: column;
      border: 1px solid var(--border-color);
      border-radius: 16px;
      background: var(--card-bg);
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.45);
      overflow: hidden;
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
    }
    .ar-chat-panel.open { display: flex; }
    .ar-chat-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 12px 14px;
      border-bottom: 1px solid var(--border-color);
      background: var(--mac-glass);
    }
    .ar-chat-header-title { font-size: 14px; font-weight: 700; }
    .ar-chat-header-actions { display: flex; gap: 6px; }
    .ar-chat-header-btn {
      border: none;
      background: var(--mac-elevated);
      color: var(--text-main);
      border-radius: 8px;
      padding: 4px 10px;
      font-size: 12px;
      cursor: pointer;
    }
    .ar-chat-name-row {
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-color);
    }
    .ar-chat-name-row input {
      width: 100%;
      margin: 0;
      font-size: 13px;
      padding: 8px 10px;
    }
    .ar-chat-status {
      font-size: 11px;
      color: var(--text-muted);
      padding: 0 12px 8px;
    }
    .ar-chat-status-dot {
      display: inline-block;
      width: 7px;
      height: 7px;
      border-radius: 50%;
      margin-right: 5px;
      background: #ff3b30;
      vertical-align: middle;
    }
    .ar-chat-status-dot.online { background: #34c759; }
    .ar-chat-body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
    .ar-chat-view { flex: 1; display: none; flex-direction: column; min-height: 0; }
    .ar-chat-view.active { display: flex; }
    .ar-chat-view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-color);
      font-size: 12px;
      font-weight: 600;
    }
    .ar-chat-peer-list, .ar-chat-messages {
      flex: 1;
      overflow-y: auto;
      min-height: 0;
    }
    .ar-chat-peer {
      display: block;
      width: 100%;
      text-align: left;
      padding: 10px 12px;
      border: none;
      border-bottom: 1px solid var(--border-color);
      background: transparent;
      color: var(--text-main);
      cursor: pointer;
    }
    .ar-chat-peer:hover { background: var(--mac-tertiary); }
    .ar-chat-peer-name { font-size: 13px; font-weight: 600; }
    .ar-chat-peer-meta { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
    .ar-chat-messages {
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .ar-chat-bubble {
      max-width: 88%;
      padding: 8px 11px;
      border-radius: 14px;
      font-size: 13px;
      line-height: 1.4;
      word-break: break-word;
    }
    .ar-chat-bubble.in {
      align-self: flex-start;
      background: var(--mac-elevated);
      border: 1px solid var(--border-color);
    }
    .ar-chat-bubble.out {
      align-self: flex-end;
      background: rgba(52, 199, 89, 0.2);
      border: 1px solid rgba(52, 199, 89, 0.35);
    }
    .ar-chat-bubble-meta { font-size: 10px; color: var(--text-muted); margin-top: 4px; }
    .ar-chat-empty {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
      text-align: center;
      font-size: 12px;
      color: var(--text-muted);
    }
    .ar-chat-compose {
      display: flex;
      gap: 8px;
      padding: 10px 12px;
      border-top: 1px solid var(--border-color);
    }
    .ar-chat-compose input {
      flex: 1;
      margin: 0;
      font-size: 13px;
      padding: 8px 10px;
    }
    .ar-chat-compose button {
      flex-shrink: 0;
      padding: 8px 14px;
      border-radius: 10px;
      border: none;
      background: #34c759;
      color: #fff;
      font-weight: 600;
      font-size: 13px;
      cursor: pointer;
    }
    .ar-chat-compose button:disabled { opacity: 0.45; cursor: not-allowed; }
    .ar-chat-inline-error {
      display: none;
      margin: 0 12px 8px;
      padding: 8px 10px;
      border-radius: 10px;
      font-size: 12px;
      background: var(--toast-error-bg);
      color: var(--toast-error-text);
    }
    .ar-chat-inline-error.visible { display: block; }
    .ar-chat-mode-tabs {
      display: flex;
      gap: 6px;
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-color);
    }
    .ar-chat-mode-tab {
      flex: 1;
      border: none;
      background: transparent;
      padding: 8px;
      border-radius: 8px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      color: var(--text-muted);
    }
    .ar-chat-mode-tab.active {
      background: var(--mac-elevated);
      color: var(--text-main);
    }
    .ar-chat-sender-name {
      font-size: 11px;
      font-weight: 700;
      margin-bottom: 4px;
      color: var(--text-muted);
    }
  `;
}

function floatingChatWidgetHtml() {
  return `
  <button type="button" class="ar-chat-fab" id="arChatFab" aria-label="Open chat">
    Chat
    <span class="ar-chat-fab-badge" id="arChatFabBadge"></span>
  </button>
  <div class="ar-chat-panel" id="arChatPanel" aria-label="Chat">
    <div class="ar-chat-header">
      <span class="ar-chat-header-title">Chat</span>
      <div class="ar-chat-header-actions">
        <button type="button" class="ar-chat-header-btn" id="arChatMinimizeBtn">−</button>
      </div>
    </div>
    <div class="ar-chat-name-row">
      <input type="text" id="arChatDeviceName" placeholder="Your name" maxlength="64" />
    </div>
    <div class="ar-chat-status">
      <span class="ar-chat-status-dot" id="arChatWsDot"></span>
      <span id="arChatWsStatus">Connecting...</span>
    </div>
    <div class="ar-chat-inline-error" id="arChatError"></div>
    <div class="ar-chat-mode-tabs">
      <button type="button" class="ar-chat-mode-tab active" id="arChatModeGlobalBtn">Global</button>
      <button type="button" class="ar-chat-mode-tab" id="arChatModeDirectBtn">Direct</button>
    </div>
    <div class="ar-chat-body">
      <div class="ar-chat-view active" id="arChatGlobalView">
        <div class="ar-chat-view-header">
          <span>Everyone on this gateway</span>
        </div>
        <div class="ar-chat-messages" id="arChatGlobalMessageList"></div>
        <div class="ar-chat-compose">
          <input type="text" id="arChatGlobalMessageInput" placeholder="Message everyone..." maxlength="2000" />
          <button type="button" id="arChatGlobalSendBtn">Send</button>
        </div>
      </div>
      <div class="ar-chat-view" id="arChatPeersView">
        <div class="ar-chat-view-header">
          <span>Online</span>
          <button type="button" class="ar-chat-header-btn" id="arChatRefreshPeersBtn">Refresh</button>
        </div>
        <div class="ar-chat-peer-list" id="arChatPeerList">
          <div class="ar-chat-empty">Looking for peers...</div>
        </div>
      </div>
      <div class="ar-chat-view" id="arChatThreadView">
        <div class="ar-chat-view-header">
          <button type="button" class="ar-chat-header-btn" id="arChatBackBtn">← Peers</button>
          <span id="arChatThreadTitle">Chat</span>
        </div>
        <div class="ar-chat-messages" id="arChatMessageList"></div>
        <div class="ar-chat-compose">
          <input type="text" id="arChatMessageInput" placeholder="Type a message..." maxlength="2000" disabled />
          <button type="button" id="arChatSendBtn" disabled>Send</button>
        </div>
      </div>
    </div>
  </div>
  `;
}

function floatingChatWidgetScript() {
  return `
(function() {
  const DEVICE_ID_KEY = 'airreceive_device_id';
  const DEVICE_NAME_KEY = 'airreceive_chat_device_name';
  const HISTORY_KEY = 'airreceive_chat_history';
  const GLOBAL_HISTORY_KEY = 'airreceive_global_chat_history';
  const GLOBAL_PEER_ID = '__global__';

  let opts = {};
  let ws = null;
  let myDeviceId = localStorage.getItem(DEVICE_ID_KEY) || null;
  let selectedPeer = null;
  let peers = [];
  let reconnectTimer = null;
  let unreadWhileClosed = 0;
  let panelOpen = false;
  let chatMode = 'global';
  let baseDocumentTitle = document.title;
  let notificationPermissionRequested = false;

  const fab = document.getElementById('arChatFab');
  const fabBadge = document.getElementById('arChatFabBadge');
  const panel = document.getElementById('arChatPanel');
  const nameInput = document.getElementById('arChatDeviceName');
  const wsDot = document.getElementById('arChatWsDot');
  const wsStatus = document.getElementById('arChatWsStatus');
  const errorEl = document.getElementById('arChatError');
  const modeGlobalBtn = document.getElementById('arChatModeGlobalBtn');
  const modeDirectBtn = document.getElementById('arChatModeDirectBtn');
  const globalView = document.getElementById('arChatGlobalView');
  const globalMessageList = document.getElementById('arChatGlobalMessageList');
  const globalMessageInput = document.getElementById('arChatGlobalMessageInput');
  const globalSendBtn = document.getElementById('arChatGlobalSendBtn');
  const peerList = document.getElementById('arChatPeerList');
  const refreshPeersBtn = document.getElementById('arChatRefreshPeersBtn');
  const peersView = document.getElementById('arChatPeersView');
  const threadView = document.getElementById('arChatThreadView');
  const threadTitle = document.getElementById('arChatThreadTitle');
  const messageList = document.getElementById('arChatMessageList');
  const messageInput = document.getElementById('arChatMessageInput');
  const sendBtn = document.getElementById('arChatSendBtn');
  const backBtn = document.getElementById('arChatBackBtn');
  const minimizeBtn = document.getElementById('arChatMinimizeBtn');

  function getDeviceName() {
    return (nameInput && nameInput.value.trim()) ||
      localStorage.getItem(DEVICE_NAME_KEY) ||
      localStorage.getItem('airreceive_device_name') ||
      localStorage.getItem('airreceive_receiver_name') ||
      'Browser';
  }

  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return proto + '//' + location.host + '/ws/receiver';
  }

  function getActiveWs() {
    if (opts.getExternalWs) {
      const external = opts.getExternalWs();
      if (external && external.readyState === WebSocket.OPEN) return external;
    }
    return ws && ws.readyState === WebSocket.OPEN ? ws : null;
  }

  function usesExternalWs() {
    return typeof opts.getExternalWs === 'function';
  }

  function showChatError(msg) {
    if (!errorEl) return;
    errorEl.textContent = msg;
    errorEl.classList.add('visible');
    setTimeout(function() { errorEl.classList.remove('visible'); }, 5000);
  }

  function setConnected(online) {
    if (!wsDot || !wsStatus) return;
    wsDot.classList.toggle('online', online);
    wsStatus.textContent = online ? 'Connected' : 'Disconnected — reconnecting...';
  }

  function updateFabBadge() {
    if (!fabBadge) return;
    if (unreadWhileClosed > 0 && !panelOpen) {
      fabBadge.textContent = String(Math.min(unreadWhileClosed, 99));
      fabBadge.classList.add('visible');
    } else {
      fabBadge.classList.remove('visible');
    }
    updateDocumentTitle();
  }

  function updateDocumentTitle() {
    if (unreadWhileClosed > 0 && !panelOpen) {
      document.title = '(' + Math.min(unreadWhileClosed, 99) + ') ' + baseDocumentTitle;
    } else {
      document.title = baseDocumentTitle;
    }
  }

  function requestNotificationPermission() {
    if (notificationPermissionRequested) return;
    notificationPermissionRequested = true;
    if (typeof Notification === 'undefined') return;
    if (Notification.permission === 'default') {
      Notification.requestPermission().catch(function() { /* non-blocking */ });
    }
  }

  function playChatBeep() {
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = 880;
      gain.gain.value = 0.08;
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      osc.stop(ctx.currentTime + 0.12);
      osc.onended = function() { ctx.close(); };
    } catch (e) { /* ignore */ }
  }

  function showBrowserNotification(sender, body) {
    if (typeof Notification === 'undefined') return;
    if (Notification.permission !== 'granted') return;
    const chatMinimized = !panelOpen;
    if (!document.hidden && !chatMinimized) return;
    try {
      new Notification(sender, { body: body, tag: 'airreceive-chat' });
    } catch (e) { /* ignore */ }
  }

  function shouldAlertForGlobalMessage() {
    return !panelOpen || chatMode !== 'global';
  }

  function shouldAlertForDirectMessage(peerId) {
    if (!panelOpen) return true;
    if (chatMode !== 'direct') return true;
    if (!selectedPeer || selectedPeer.id !== peerId) return true;
    return false;
  }

  function bumpUnread(sender, preview, isGlobal) {
    unreadWhileClosed += 1;
    updateFabBadge();
    if (!panelOpen) playChatBeep();
    showBrowserNotification(
      isGlobal ? sender + ' (global)' : sender,
      preview
    );
  }

  function openPanel() {
    panelOpen = true;
    unreadWhileClosed = 0;
    updateFabBadge();
    requestNotificationPermission();
    if (panel) panel.classList.add('open');
    if (fab) fab.classList.add('hidden');
    showGlobalView();
    refreshPeers();
    renderGlobalThread();
  }

  function closePanel() {
    panelOpen = false;
    if (panel) panel.classList.remove('open');
    if (fab) fab.classList.remove('hidden');
  }

  function setModeTabActive(mode) {
    chatMode = mode;
    if (modeGlobalBtn) modeGlobalBtn.classList.toggle('active', mode === 'global');
    if (modeDirectBtn) modeDirectBtn.classList.toggle('active', mode === 'direct');
  }

  function showGlobalView() {
    setModeTabActive('global');
    if (globalView) globalView.classList.add('active');
    if (peersView) peersView.classList.remove('active');
    if (threadView) threadView.classList.remove('active');
    selectedPeer = null;
    renderGlobalThread();
  }

  function showDirectPeersView() {
    setModeTabActive('direct');
    if (globalView) globalView.classList.remove('active');
    if (peersView) peersView.classList.add('active');
    if (threadView) threadView.classList.remove('active');
    selectedPeer = null;
    messageInput.disabled = true;
    sendBtn.disabled = true;
    renderThread();
  }

  function showPeersView() {
    showDirectPeersView();
  }

  function showThreadView(peer) {
    setModeTabActive('direct');
    selectedPeer = peer;
    if (globalView) globalView.classList.remove('active');
    if (peersView) peersView.classList.remove('active');
    if (threadView) threadView.classList.add('active');
    threadTitle.textContent = peer.displayName + ' · ' + (peer.roleLabel || peer.role || 'Device');
    messageInput.disabled = false;
    sendBtn.disabled = false;
    renderThread();
  }

  function loadGlobalHistory() {
    try { return JSON.parse(localStorage.getItem(GLOBAL_HISTORY_KEY) || '[]'); }
    catch (e) { return []; }
  }

  function saveGlobalHistory(messages) {
    localStorage.setItem(GLOBAL_HISTORY_KEY, JSON.stringify(messages));
  }

  function appendGlobalMessage(msg) {
    const messages = loadGlobalHistory();
    const exists = messages.some(function(m) {
      return m.messageId && msg.messageId && m.messageId === msg.messageId;
    });
    if (!exists) {
      messages.push(msg);
      while (messages.length > 200) messages.shift();
      saveGlobalHistory(messages);
    }
  }

  function renderGlobalThread() {
    if (!globalMessageList) return;
    globalMessageList.innerHTML = '';
    const msgs = loadGlobalHistory();
    if (msgs.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'ar-chat-empty';
      empty.textContent = 'No messages yet. Say hello to everyone!';
      globalMessageList.appendChild(empty);
      return;
    }
    msgs.forEach(function(msg) {
      const isOut = msg.fromDeviceId && myDeviceId && msg.fromDeviceId === myDeviceId;
      const bubble = document.createElement('div');
      bubble.className = 'ar-chat-bubble ' + (isOut || msg.direction === 'out' ? 'out' : 'in');
      if (!isOut && msg.direction !== 'out' && msg.fromDisplayName) {
        const sender = document.createElement('div');
        sender.className = 'ar-chat-sender-name';
        sender.textContent = msg.fromDisplayName;
        bubble.appendChild(sender);
      }
      const text = document.createElement('div');
      text.textContent = msg.text;
      bubble.appendChild(text);
      const meta = document.createElement('div');
      meta.className = 'ar-chat-bubble-meta';
      meta.textContent = formatTime(msg.sentAt);
      bubble.appendChild(meta);
      globalMessageList.appendChild(bubble);
    });
    globalMessageList.scrollTop = globalMessageList.scrollHeight;
  }

  async function syncGlobalHistory() {
    try {
      const res = await fetch('/api/chat/global');
      const data = await res.json();
      (data.messages || []).forEach(function(msg) {
        appendGlobalMessage({
          messageId: msg.messageId,
          fromDeviceId: msg.fromDeviceId,
          fromDisplayName: msg.fromDisplayName,
          text: msg.text,
          sentAt: msg.sentAt,
          direction: (msg.fromDeviceId && myDeviceId && msg.fromDeviceId === myDeviceId) ? 'out' : 'in'
        });
      });
      if (chatMode === 'global') renderGlobalThread();
    } catch (e) { /* ignore */ }
  }

  function loadHistory() {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '{}'); }
    catch (e) { return {}; }
  }

  function saveHistory(history) {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
  }

  function appendMessage(peerId, msg) {
    const history = loadHistory();
    if (!history[peerId]) history[peerId] = [];
    const exists = history[peerId].some(function(m) {
      return m.messageId && msg.messageId && m.messageId === msg.messageId;
    });
    if (!exists) {
      history[peerId].push(msg);
      saveHistory(history);
    }
  }

  function formatTime(ts) {
    const d = new Date(ts);
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function renderThread() {
    if (!messageList) return;
    messageList.innerHTML = '';
    if (!selectedPeer) return;
    const msgs = loadHistory()[selectedPeer.id] || [];
    if (msgs.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'ar-chat-empty';
      empty.textContent = 'No messages yet. Say hello!';
      messageList.appendChild(empty);
      return;
    }
    msgs.forEach(function(msg) {
      const bubble = document.createElement('div');
      bubble.className = 'ar-chat-bubble ' + (msg.direction === 'out' ? 'out' : 'in');
      bubble.textContent = msg.text;
      const meta = document.createElement('div');
      meta.className = 'ar-chat-bubble-meta';
      meta.textContent = formatTime(msg.sentAt) + (msg.status === 'queued' ? ' · queued' : '');
      bubble.appendChild(meta);
      messageList.appendChild(bubble);
    });
    messageList.scrollTop = messageList.scrollHeight;
  }

  function renderPeers() {
    if (!peerList) return;
    if (peers.length === 0) {
      peerList.innerHTML = '<div class="ar-chat-empty">No one else online — open AirReceive on another device.</div>';
      return;
    }
    peerList.innerHTML = '';
    peers.forEach(function(peer) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'ar-chat-peer';
      btn.innerHTML = '<div class="ar-chat-peer-name">' + escapeHtml(peer.displayName) + '</div>' +
        '<div class="ar-chat-peer-meta">' + escapeHtml(peer.roleLabel || peer.role || 'Device') + ' · online</div>';
      btn.addEventListener('click', function() { showThreadView(peer); });
      peerList.appendChild(btn);
    });
  }

  async function refreshPeers() {
    try {
      const qs = myDeviceId ? '?exclude=' + encodeURIComponent(myDeviceId) : '';
      const res = await fetch('/api/chat/peers' + qs);
      const data = await res.json();
      peers = (data.peers || []).filter(function(p) { return p.id !== myDeviceId; });
      renderPeers();
    } catch (e) {
      if (peerList) peerList.innerHTML = '<div class="ar-chat-empty">Could not load peers.</div>';
    }
  }

  async function pollPending() {
    if (!myDeviceId) return;
    try {
      const res = await fetch('/api/chat/pending/' + encodeURIComponent(myDeviceId));
      const data = await res.json();
      (data.messages || []).forEach(handleIncomingMessage);
    } catch (e) { /* ignore */ }
  }

  function handleIncomingGlobalMessage(msg) {
    if (msg.fromDeviceId && myDeviceId && msg.fromDeviceId === myDeviceId) {
      return;
    }
    appendGlobalMessage({
      messageId: msg.messageId,
      fromDeviceId: msg.fromDeviceId,
      fromDisplayName: msg.fromDisplayName,
      text: msg.text,
      sentAt: msg.sentAt,
      direction: 'in'
    });
    if (shouldAlertForGlobalMessage()) {
      const preview = (msg.text || '').trim();
      bumpUnread(msg.fromDisplayName || 'Someone', preview.slice(0, 80), true);
    }
    if (chatMode === 'global') renderGlobalThread();
  }

  function sendGlobalMessage() {
    const activeWs = getActiveWs();
    if (!activeWs) {
      showChatError('Not connected to gateway.');
      return;
    }
    const text = globalMessageInput.value.trim();
    if (!text) return;
    const clientMessageId = 'g-' + Date.now() + '-' + Math.random().toString(36).slice(2);
    appendGlobalMessage({
      clientMessageId: clientMessageId,
      messageId: clientMessageId,
      fromDeviceId: myDeviceId,
      fromDisplayName: getDeviceName(),
      direction: 'out',
      text: text,
      sentAt: Date.now(),
      status: 'sending'
    });
    renderGlobalThread();
    globalMessageInput.value = '';
    activeWs.send(JSON.stringify({
      type: 'GLOBAL_CHAT_SEND',
      text: text,
      clientMessageId: clientMessageId
    }));
  }

  function updateGlobalOutgoingStatus(clientMessageId, messageId) {
    const messages = loadGlobalHistory();
    const idx = messages.findIndex(function(m) { return m.clientMessageId === clientMessageId; });
    if (idx >= 0) {
      messages[idx].messageId = messageId || messages[idx].messageId;
      messages[idx].status = 'delivered';
      saveGlobalHistory(messages);
      renderGlobalThread();
    }
  }

  function handleIncomingMessage(msg) {
    const peerId = msg.fromDeviceId;
    appendMessage(peerId, {
      messageId: msg.messageId,
      direction: 'in',
      text: msg.text,
      sentAt: msg.sentAt,
      fromDisplayName: msg.fromDisplayName
    });
    if (shouldAlertForDirectMessage(peerId)) {
      const preview = (msg.text || '').trim();
      bumpUnread(msg.fromDisplayName || 'Someone', preview.slice(0, 80), false);
    }
    if (selectedPeer && selectedPeer.id === peerId) renderThread();
  }

  function sendMessage() {
    if (chatMode === 'global') {
      sendGlobalMessage();
      return;
    }
    const activeWs = getActiveWs();
    if (!selectedPeer || !activeWs) {
      showChatError('Not connected to gateway.');
      return;
    }
    const text = messageInput.value.trim();
    if (!text) return;
    const clientMessageId = 'c-' + Date.now() + '-' + Math.random().toString(36).slice(2);
    appendMessage(selectedPeer.id, {
      clientMessageId: clientMessageId,
      messageId: clientMessageId,
      direction: 'out',
      text: text,
      sentAt: Date.now(),
      status: 'sending'
    });
    renderThread();
    messageInput.value = '';
    activeWs.send(JSON.stringify({
      type: 'CHAT_SEND',
      toDeviceId: selectedPeer.id,
      text: text,
      clientMessageId: clientMessageId
    }));
  }

  function updateOutgoingStatus(clientMessageId, messageId, status) {
    if (!selectedPeer) return;
    const history = loadHistory();
    const msgs = history[selectedPeer.id] || [];
    const idx = msgs.findIndex(function(m) { return m.clientMessageId === clientMessageId; });
    if (idx >= 0) {
      msgs[idx].messageId = messageId || msgs[idx].messageId;
      msgs[idx].status = status;
      saveHistory(history);
      renderThread();
    }
  }

  function connect() {
    if (usesExternalWs()) {
      const external = opts.getExternalWs();
      setConnected(!!(external && external.readyState === WebSocket.OPEN));
      if (opts.getMyDeviceId) {
        const id = opts.getMyDeviceId();
        if (id) {
          myDeviceId = id;
          pollPending();
          refreshPeers();
        }
      }
      return;
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (ws) {
      try { ws.close(); } catch (e) { /* ignore */ }
      ws = null;
    }
    ws = new WebSocket(wsUrl());
    ws.onopen = function() {
      setConnected(true);
      ws.send(JSON.stringify({
        type: 'REGISTER',
        displayName: getDeviceName(),
        deviceId: myDeviceId || undefined
      }));
    };
    ws.onclose = function() {
      setConnected(false);
      reconnectTimer = setTimeout(connect, 3000);
    };
    ws.onerror = function() { setConnected(false); };
    ws.onmessage = function(ev) {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      handleWsMessage(msg);
    };
  }

  function handleWsMessage(msg) {
    if (msg.type === 'REGISTERED') {
      myDeviceId = msg.deviceId;
      localStorage.setItem(DEVICE_ID_KEY, myDeviceId);
      pollPending();
      refreshPeers();
      syncGlobalHistory();
      return;
    }
    if (msg.type === 'GLOBAL_CHAT_MESSAGE') {
      handleIncomingGlobalMessage(msg);
      return;
    }
    if (msg.type === 'GLOBAL_CHAT_SENT') {
      updateGlobalOutgoingStatus(msg.clientMessageId, msg.messageId);
      return;
    }
    if (msg.type === 'GLOBAL_CHAT_ERROR') {
      showChatError(msg.error || 'Global chat error');
      return;
    }
    if (msg.type === 'CHAT_MESSAGE') {
      handleIncomingMessage(msg);
      return;
    }
    if (msg.type === 'CHAT_SENT') {
      updateOutgoingStatus(msg.clientMessageId, msg.messageId, msg.status);
      return;
    }
    if (msg.type === 'CHAT_ERROR') {
      showChatError(msg.error || 'Chat error');
    }
  }

  function bindUi() {
    if (nameInput) {
      nameInput.value = getDeviceName();
      nameInput.addEventListener('change', function() {
        localStorage.setItem(DEVICE_NAME_KEY, nameInput.value.trim());
        const activeWs = getActiveWs();
        if (activeWs && !usesExternalWs()) {
          activeWs.send(JSON.stringify({
            type: 'REGISTER',
            displayName: getDeviceName(),
            deviceId: myDeviceId || undefined
          }));
        }
      });
    }
    if (fab) fab.addEventListener('click', openPanel);
    if (minimizeBtn) minimizeBtn.addEventListener('click', closePanel);
    if (modeGlobalBtn) modeGlobalBtn.addEventListener('click', showGlobalView);
    if (modeDirectBtn) modeDirectBtn.addEventListener('click', showDirectPeersView);
    if (backBtn) backBtn.addEventListener('click', showPeersView);
    if (refreshPeersBtn) refreshPeersBtn.addEventListener('click', refreshPeers);
    if (sendBtn) sendBtn.addEventListener('click', sendMessage);
    if (globalSendBtn) globalSendBtn.addEventListener('click', sendGlobalMessage);
    if (messageInput) {
      messageInput.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          sendMessage();
        }
      });
    }
    if (globalMessageInput) {
      globalMessageInput.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          sendGlobalMessage();
        }
      });
    }
    document.querySelectorAll('.ar-chat-nav-open').forEach(function(el) {
      el.addEventListener('click', function(e) {
        e.preventDefault();
        openPanel();
      });
    });
  }

  window.AirReceiveFloatingChat = {
    init: function(options) {
      opts = options || {};
      bindUi();
      connect();
      setInterval(refreshPeers, 3000);
      if (usesExternalWs()) {
        setInterval(function() {
          const external = opts.getExternalWs();
          setConnected(!!(external && external.readyState === WebSocket.OPEN));
        }, 2000);
      }
      if (opts.openOnLoad) openPanel();
    },
    open: openPanel,
    close: closePanel,
    handleWsMessage: handleWsMessage,
    onRegistered: function(deviceId) {
      myDeviceId = deviceId;
      pollPending();
      refreshPeers();
      syncGlobalHistory();
    }
  };
})();
  `;
}

function floatingChatPageTail(openOnLoad, skipAutoInit) {
  const openFlag = openOnLoad ? 'true' : 'false';
  const bootScript = skipAutoInit ? '' : `
<script>
document.addEventListener('DOMContentLoaded', function() {
  if (window.AirReceiveFloatingChat) {
    AirReceiveFloatingChat.init({ openOnLoad: ${openFlag} });
  }
});
</script>
  `;
  return `
${floatingChatWidgetHtml()}
<script>${floatingChatWidgetScript()}</script>
${bootScript}
  `;
}

function gatewayNavHtml(activeNav) {
  const items = [
    { key: 'home', href: '/', label: 'Home' },
    { key: 'android', href: '/to-android', label: 'Send to Android' },
    { key: 'send', href: '/send', label: 'Send to device' },
    { key: 'receive', href: '/receive', label: 'Receive' },
    { key: 'chat', href: '#', label: 'Chat', openChat: true },
    { key: 'support', href: '/support', label: 'Support' }
  ];
  return '<nav class="gateway-nav">' + items.map((item) => {
    const cls = item.key === activeNav ? 'gateway-nav-link active' : 'gateway-nav-link';
    const chatCls = item.openChat ? ' ar-chat-nav-open' : '';
    return '<a class="' + cls + chatCls + '" href="' + item.href + '">' + item.label + '</a>';
  }).join('') + '</nav>';
}

function gatewayPageHtml({ title, activeNav, accent = '#007aff', extraCss = '', bodyHtml, openChatOnLoad = false }) {
  return `<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  ${airReceiveFaviconLink()}
  <title>${title}</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss(accent)}
    ${floatingChatWidgetCss()}
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
  ${floatingChatPageTail(openChatOnLoad)}
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
  const openChatOnLoad = req.query.chat === 'open';
  res.send(gatewayPageHtml({
    title: 'AirReceive Gateway',
    activeNav: 'home',
    accent: '#007aff',
    openChatOnLoad,
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
        <span>Pick an online receiver and send any number of files (large selections upload in batches).</span>
      </a>
      <a class="hub-card" href="/receive">
        <strong>Receive files</strong>
        <span>Stay on this page to receive files sent from another device or Android.</span>
      </a>
      <a class="hub-card ar-chat-nav-open" href="#">
        <strong>Direct messages</strong>
        <span>Text chat with any online Android phone or browser on this gateway.</span>
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
  ${airReceiveFaviconLink()}
  <title>AirReceive — Send to Android</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#30d158')}
    ${floatingChatWidgetCss()}

    .to-android-card {
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
      padding: 32px;
      text-align: center;
      box-shadow: 0 8px 24px var(--mac-shadow);
    }

    .logo-container {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 72px;
      height: 72px;
      margin-bottom: 16px;
    }

    .logo-container .airreceive-logo {
      width: 72px;
      height: 72px;
    }

    h1 { font-size: 24px; font-weight: 700; }

    .tagline {
      color: var(--text-muted);
      font-size: 14px;
      margin-top: 8px;
      margin-bottom: 24px;
    }

    .status-badge { margin-bottom: 24px; }
    .status-badge .dot { animation: pulse 1.5s infinite; }
    @keyframes pulse {
      0% { opacity: 0.3; }
      50% { opacity: 1; }
      100% { opacity: 0.3; }
    }

    /* Drag Drop Area */
    .to-android-page .drop-zone {
      border: 2px dashed var(--border-color);
      background-color: var(--mac-hover);
      border-radius: var(--mac-radius-lg);
      padding: 30px 16px;
      cursor: pointer;
      transition: border-color 0.2s, background-color 0.2s;
      position: relative;
    }

    .to-android-page .drop-zone:hover,
    .to-android-page .drop-zone.drag-over {
      border-color: var(--primary);
      background-color: color-mix(in srgb, var(--primary) 8%, var(--mac-input-bg));
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

    .to-android-page .toast-success {
      background-color: var(--toast-success-bg);
      border: 1px solid var(--border-color);
      color: var(--toast-success-text);
    }

    .to-android-page .toast-error {
      background-color: var(--toast-error-bg);
      border: 1px solid var(--border-color);
      color: var(--toast-error-text);
    }

    /* Render Instructions */
    .to-android-page .instructions {
      text-align: left;
      background-color: var(--mac-input-bg);
      border: 1px solid var(--border-color);
      border-radius: var(--mac-radius);
      padding: 16px 20px;
      margin-top: 24px;
      font-size: 13px;
      color: var(--text-muted);
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

    .to-android-page code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      background-color: var(--mac-hover);
      padding: 2px 6px;
      border-radius: 4px;
      color: var(--primary);
      font-size: 11.5px;
      word-break: break-all;
    }

    .to-android-page .instructions strong {
      color: var(--text-main);
    }

    .to-android-page .footer {
      font-size: 11px;
      color: var(--text-muted);
      margin-top: 32px;
      text-align: center;
      line-height: 1.45;
    }

    .to-android-page .device-empty strong {
      color: var(--text-main);
    }

    @keyframes fadeInUp {
      from { transform: translateY(10px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    label.device-label { text-align: left; }
    .device-list { margin-bottom: 16px; max-height: 160px; overflow-y: auto; }
    .device-option input { margin-right: 10px; }
    .refresh-btn { margin-bottom: 8px; }
  </style>
</head>
<body>
  <div class="page-shell">
    ${macSiteHeaderHtml('android')}
  <div class="container to-android-page">
    <div class="card to-android-card">
      <div class="logo-container">
        ${airReceiveLogoSvg()}
      </div>

      <h1>Send to Android</h1>
      <p class="tagline">Upload photos from this browser to your Android phone</p>

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
          <strong id="dropZoneTitle">Select photos or drag &amp; drop</strong>
          <span>Any number of images — uploaded in batches of up to ${MAX_BATCH_FILES} files or 100 MB each.</span>
        </div>
        <input type="file" id="fileInput" class="file-input" accept="image/*" multiple />
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

      <div id="pinBox" style="display:none; margin-top:16px; padding:16px; border-radius:12px; border:1px solid var(--border-color); text-align:center;">
        <p style="font-size:13px; color:var(--text-muted); margin-bottom:8px;">Tell the Android receiver this code:</p>
        <div id="pinDisplay" style="font-size:36px; font-weight:700; letter-spacing:8px; font-family:monospace;"></div>
        <p id="pinWaitText" style="font-size:12px; color:var(--text-muted); margin-top:8px;">Waiting for receiver to confirm...</p>
      </div>

      <div class="toast toast-success" id="successToast">
        Photos successfully transferred to your Android device!
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
    ${transferAuthClientJs()}
    ${gatewayBatchUtilsJs()}
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const dropZoneTitle = document.getElementById('dropZoneTitle');
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
    const pinBox = document.getElementById('pinBox');
    const pinDisplay = document.getElementById('pinDisplay');
    const pinWaitText = document.getElementById('pinWaitText');

    let selectedPhoneId = null;
    let isUploading = false;

    urlPlaceholder.textContent = window.location.origin;

    function updateDropZoneEnabled() {
      dropZone.classList.toggle('disabled', !selectedPhoneId || isUploading);
    }

    async function refreshPhones() {
      try {
        const excludeId = new URLSearchParams(window.location.search).get('exclude') || getSenderDeviceId();
        const query = '/api/devices?role=phone' + (excludeId ? '&exclude=' + encodeURIComponent(excludeId) : '');
        const res = await fetch(query);
        const data = await res.json();
        const phones = (data.phones || []).filter((dev) => dev.id !== excludeId);
        if (phones.length === 0) {
          phoneListEl.innerHTML = '<div class="device-empty">No other phones online. Open AirReceive on another Android device, enable gateway in <strong>Settings</strong>, and keep the app in the foreground. You cannot send to this device.</div>';
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
          text.textContent = dev.displayName + ' — online' + (dev.passwordProtection ? ' (password)' : '');
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
      if (selectedPhoneId && !isUploading) fileInput.click();
    });

    dropZone.addEventListener('dragover', (e) => {
      e.preventDefault();
      if (!isUploading) dropZone.classList.add('drag-over');
    });

    ['dragleave', 'dragend', 'drop'].forEach(event => {
      dropZone.addEventListener(event, () => dropZone.classList.remove('drag-over'));
    });

    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      if (e.dataTransfer.files.length) {
        handleFilesSelect(Array.from(e.dataTransfer.files));
      }
    });

    fileInput.addEventListener('change', () => {
      if (fileInput.files.length) {
        handleFilesSelect(Array.from(fileInput.files));
        fileInput.value = '';
      }
    });

    function handleFilesSelect(files) {
      if (!selectedPhoneId) {
        showError('Select an Android device first.');
        return;
      }
      if (isUploading) return;
      const images = files.filter((f) => f.type.startsWith('image/'));
      if (images.length === 0) {
        showError('Only image files are supported.');
        return;
      }
      if (images.length < files.length && images.length > 0) {
        console.warn('Skipped ' + (files.length - images.length) + ' non-image file(s).');
      }
      dropZoneTitle.textContent = images.length + ' photo(s) selected — uploading...';
      uploadFiles(images);
    }

    async function uploadFiles(files) {
      hideToasts();
      pinBox.style.display = 'none';
      isUploading = true;
      updateDropZoneEnabled();

      const chunks = chunkFiles(files);
      let sentTotal = 0;

      try {
        const auth = await ensureTransferAuth(
          selectedPhoneId,
          'Browser sender',
          (pin) => {
            pinBox.style.display = 'block';
            pinDisplay.textContent = pin;
            fileTransferName.textContent = 'Waiting for receiver to enter code...';
            progressContainer.style.display = 'block';
            progressBar.style.width = '0%';
            percentText.textContent = '';
          },
          () => { pinWaitText.textContent = 'Still waiting for receiver...'; }
        );

        progressContainer.style.display = 'block';

        for (let i = 0; i < chunks.length; i++) {
          const chunk = chunks[i];
          const batchLabel = chunks.length > 1
            ? 'Uploading batch ' + (i + 1) + ' of ' + chunks.length + ' (' + chunk.length + ' file(s))...'
            : 'Uploading ' + chunk.length + ' file(s)...';
          fileTransferName.textContent = batchLabel;
          progressBar.style.width = '0%';
          percentText.textContent = '';

          const formData = new FormData();
          formData.append('target', 'phone');
          formData.append('targetDeviceId', selectedPhoneId);
          formData.append('sessionId', auth.sessionId);
          formData.append('uploadToken', auth.uploadToken);
          const senderDeviceId = getSenderDeviceId();
          if (senderDeviceId) formData.append('senderDeviceId', senderDeviceId);
          chunk.forEach((f) => formData.append('files', f));

          const res = await fetch('/upload/batch', { method: 'POST', body: formData });
          const data = await res.json().catch(() => ({}));

          if (!res.ok) {
            showError(data.error || 'Upload failed. Is the phone still online?');
            return;
          }
          if (data.phoneRelayed === false) {
            showError('Upload reached the server, but the phone is not connected. Refresh the device list and try again.');
            return;
          }
          sentTotal += data.count || chunk.length;
          progressBar.style.width = '100%';
          percentText.textContent = '100%';
        }

        showSuccess('Sent ' + sentTotal + ' photo(s) successfully.');
        dropZoneTitle.textContent = 'Select photos or drag & drop';
      } catch (e) {
        showError(e.message || 'Transfer failed.');
      } finally {
        progressContainer.style.display = 'none';
        pinBox.style.display = 'none';
        isUploading = false;
        updateDropZoneEnabled();
      }
    }

    function showSuccess(msg) {
      successToast.textContent = msg || 'Photos successfully transferred to your Android device!';
      successToast.style.display = 'block';
      setTimeout(() => {
        successToast.style.display = 'none';
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
  ${floatingChatPageTail(false)}
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
  ${airReceiveFaviconLink()}
  <title>AirReceive — Send files</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#007aff')}
    ${floatingChatWidgetCss()}
    .card {
      padding: 32px;
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
    }
    h1, .tagline { text-align: center; }
    input[type="text"] { margin-bottom: 16px; }
    .device-list { margin-bottom: 16px; max-height: 180px; overflow-y: auto; }
    .device-option input { margin-right: 10px; }
    .drop-zone { margin-bottom: 16px; }
    .drop-zone strong { display: block; margin-bottom: 6px; color: var(--text-main); }
    .drop-zone span { font-size: 12px; color: var(--text-muted); }
    .file-input { display: none; }
    .progress { display: none; margin-top: 12px; font-size: 13px; color: var(--text-muted); }
    .toast-error, .toast-success {
      display: none;
      margin-top: 12px;
      padding: 12px;
      border-radius: 12px;
      font-size: 13px;
    }
    .refresh-btn { margin-bottom: 8px; }
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
        <span>Any number of files — uploaded in batches of up to ${MAX_BATCH_FILES} files or 100 MB each.</span>
        <input type="file" id="fileInput" class="file-input" multiple />
      </div>

      <button type="button" class="send-btn" id="sendBtn" disabled>Send to selected device</button>
      <div id="pinBox" style="display:none; margin-top:16px; padding:16px; border-radius:12px; border:1px solid var(--border-color); text-align:center;">
        <p style="font-size:13px; color:var(--text-muted); margin-bottom:8px;">Tell the receiver this code:</p>
        <div id="pinDisplay" style="font-size:36px; font-weight:700; letter-spacing:8px; font-family:monospace;"></div>
        <p id="pinWaitText" style="font-size:12px; color:var(--text-muted); margin-top:8px;">Waiting for receiver to confirm...</p>
      </div>
      <p class="progress" id="progressText"></p>
      <div class="toast-success" id="successToast"></div>
      <div class="toast-error" id="errorToast"></div>
    </div>
  </div>
  <script>
    ${transferAuthClientJs()}
    ${gatewayBatchUtilsJs()}
    const SENDER_NAME_KEY = 'airreceive_sender_name';
    const DEVICE_ID_KEY = 'airreceive_device_id';
    const deviceListEl = document.getElementById('deviceList');
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const sendBtn = document.getElementById('sendBtn');
    const progressText = document.getElementById('progressText');
    const successToast = document.getElementById('successToast');
    const errorToast = document.getElementById('errorToast');
    const senderNameInput = document.getElementById('senderName');
    const refreshBtn = document.getElementById('refreshBtn');
    const pinBox = document.getElementById('pinBox');
    const pinDisplay = document.getElementById('pinDisplay');
    const pinWaitText = document.getElementById('pinWaitText');

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
        const excludeId = getSenderDeviceId();
        const query = '/api/devices?role=receiver' + (excludeId ? '&exclude=' + encodeURIComponent(excludeId) : '');
        const res = await fetch(query);
        const data = await res.json();
        const receivers = (data.receivers || []).filter((dev) => dev.id !== excludeId);
        if (receivers.length === 0) {
          deviceListEl.innerHTML = '<div class="device-empty">No other receivers online. Open <strong>/receive</strong> on another laptop or phone first. You cannot send to this device.</div>';
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
          text.textContent = dev.displayName + ' — online' + (dev.passwordProtection ? ' (password)' : '');
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
        pendingFiles = Array.from(fileInput.files);
        dropZone.querySelector('strong').textContent = pendingFiles.length + ' file(s) selected';
        updateSendEnabled();
      }
    });

    sendBtn.addEventListener('click', async () => {
      if (!selectedDeviceId || pendingFiles.length === 0) return;
      sendBtn.disabled = true;
      progressText.style.display = 'block';
      pinBox.style.display = 'none';
      const chunks = chunkFiles(pendingFiles);
      let sentTotal = 0;
      try {
        const senderLabel = senderNameInput.value.trim() || 'Web sender';
        const auth = await ensureTransferAuth(
          selectedDeviceId,
          senderLabel,
          (pin) => {
            pinBox.style.display = 'block';
            pinDisplay.textContent = pin;
            progressText.textContent = 'Waiting for receiver to enter the code...';
          },
          () => { pinWaitText.textContent = 'Still waiting for receiver...'; }
        );
        for (let i = 0; i < chunks.length; i++) {
          const chunk = chunks[i];
          progressText.textContent = chunks.length > 1
            ? 'Uploading batch ' + (i + 1) + ' of ' + chunks.length + '...'
            : 'Uploading...';
          const formData = new FormData();
          formData.append('target', 'receiver');
          formData.append('targetDeviceId', selectedDeviceId);
          formData.append('sessionId', auth.sessionId);
          formData.append('uploadToken', auth.uploadToken);
          const senderDeviceId = getSenderDeviceId();
          if (senderDeviceId) formData.append('senderDeviceId', senderDeviceId);
          chunk.forEach((f) => formData.append('files', f));
          const res = await fetch('/upload/batch', { method: 'POST', body: formData });
          const data = await res.json().catch(() => ({}));
          if (!res.ok) {
            showError(data.error || 'Upload failed. Is the receiver still online?');
            sendBtn.disabled = false;
            progressText.style.display = 'none';
            pinBox.style.display = 'none';
            return;
          }
          sentTotal += data.count || chunk.length;
        }
        showSuccess('Sent ' + sentTotal + ' file(s) successfully.');
        pendingFiles = [];
        fileInput.value = '';
        dropZone.querySelector('strong').textContent = 'Select files to send';
        updateSendEnabled();
        pinBox.style.display = 'none';
      } catch (e) {
        showError(e.message || 'Network error');
        sendBtn.disabled = false;
        pinBox.style.display = 'none';
      }
      progressText.style.display = 'none';
    });

    refreshBtn.addEventListener('click', refreshDevices);
    setInterval(refreshDevices, 3000);
    refreshDevices();
  </script>
  </div>
  </div>
  ${floatingChatPageTail(false)}
</body>
</html>
  `);
});

app.get('/chat', (req, res) => {
  res.redirect('/?chat=open');
});

// iPhone / Safari receive page
app.get('/receive', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  ${airReceiveFaviconLink()}
  <title>AirReceive — Receive photos</title>
  <script>${macThemeBootScript()}</script>
  <style>
    ${macDesignCss('#007aff')}
    ${floatingChatWidgetCss()}
    .card {
      padding: 32px;
      text-align: center;
      backdrop-filter: blur(40px) saturate(180%);
      -webkit-backdrop-filter: blur(40px) saturate(180%);
    }
    .status-badge { margin-bottom: 20px; }
    .batch-wrap {
      display: none;
      margin-top: 20px;
      padding: 16px;
      border-radius: var(--mac-radius-lg);
      background: var(--mac-elevated);
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
      background: var(--mac-input-bg);
    }
    .action-buttons { margin-top: 4px; }
    .save-all-btn, .download-all-btn {
      display: block;
      width: 100%;
      text-align: center;
      box-sizing: border-box;
    }
    .download-all-btn { margin-top: 10px; width: 100%; }
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
      font-size: 13px;
    }
    .waiting {
      color: var(--text-muted);
      font-size: 14px;
      margin-top: 12px;
    }
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
      background: var(--mac-hover);
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
      background: var(--toast-warn-bg);
      border: 1px solid var(--border-color);
      color: var(--toast-warn-text);
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
    .utility-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .password-toggle {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 12px 0;
      font-size: 13px;
      text-align: left;
    }
    .auth-modal {
      display: none;
      position: fixed;
      inset: 0;
      background: rgba(0,0,0,0.55);
      z-index: 2000;
      align-items: center;
      justify-content: center;
      padding: 16px;
    }
    .auth-modal.visible { display: flex; }
    .auth-card {
      background: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 16px;
      padding: 24px;
      max-width: 360px;
      width: 100%;
      text-align: center;
    }
    .auth-card h2 { font-size: 18px; margin-bottom: 8px; }
    .auth-card p { font-size: 13px; color: var(--text-muted); margin-bottom: 16px; }
    .auth-card input {
      width: 100%;
      font-size: 24px;
      letter-spacing: 8px;
      text-align: center;
      padding: 12px;
      margin-bottom: 12px;
    }
    .auth-card input.auth-input-error {
      border-color: var(--toast-error-text);
      box-shadow: 0 0 0 2px rgba(255, 69, 58, 0.25);
    }
    .auth-error {
      display: none;
      margin: 0 0 12px;
      padding: 10px 12px;
      border-radius: 10px;
      font-size: 13px;
      font-weight: 600;
      text-align: center;
      background: var(--toast-error-bg);
      color: var(--toast-error-text);
      border: 1px solid var(--toast-error-text);
    }
    .auth-error.visible { display: block; }
    .auth-card .auth-actions { display: flex; gap: 8px; justify-content: center; }
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

      <label class="password-toggle">
        <input type="checkbox" id="passwordProtectionToggle" />
        Require password before accepting files
      </label>

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

      <div class="auth-modal" id="authModal">
        <div class="auth-card">
          <h2>Incoming transfer</h2>
          <p id="authSenderHint">A sender wants to send files. Enter the code shown on their device.</p>
          <input type="text" id="authPinInput" inputmode="numeric" maxlength="6" placeholder="000000" autocomplete="one-time-code" />
          <div class="auth-error" id="authError" role="alert"></div>
          <div class="auth-actions">
            <button type="button" class="utility-btn" id="authCancelBtn">Cancel</button>
            <button type="button" class="save-all-btn" id="authConfirmBtn">Confirm</button>
          </div>
        </div>
      </div>

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
    const PASSWORD_PROTECTION_KEY = 'airreceive_password_protection';

    const passwordProtectionToggle = document.getElementById('passwordProtectionToggle');
    const authModal = document.getElementById('authModal');
    const authPinInput = document.getElementById('authPinInput');
    const authError = document.getElementById('authError');
    const authSenderHint = document.getElementById('authSenderHint');
    const authConfirmBtn = document.getElementById('authConfirmBtn');
    const authCancelBtn = document.getElementById('authCancelBtn');
    let pendingAuthSessionId = null;
    let myDeviceId = null;

    passwordProtectionToggle.checked = localStorage.getItem(PASSWORD_PROTECTION_KEY) === '1';
    passwordProtectionToggle.addEventListener('change', () => {
      const enabled = passwordProtectionToggle.checked;
      localStorage.setItem(PASSWORD_PROTECTION_KEY, enabled ? '1' : '0');
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'SET_PASSWORD_PROTECTION', passwordProtection: enabled }));
      }
    });

    function showAuthError(msg) {
      if (!authError) return;
      authError.textContent = msg;
      authError.classList.add('visible');
      authPinInput.classList.add('auth-input-error');
      authPinInput.focus();
      authPinInput.select();
    }

    function clearAuthError() {
      if (!authError) return;
      authError.textContent = '';
      authError.classList.remove('visible');
      authPinInput.classList.remove('auth-input-error');
    }

    function showAuthModal(sessionId, senderLabel) {
      pendingAuthSessionId = sessionId;
      authSenderHint.textContent = (senderLabel || 'A sender') + ' wants to send files. Enter the code shown on the Android sender.';
      authPinInput.value = '';
      clearAuthError();
      authModal.classList.add('visible');
      authPinInput.focus();
    }

    async function pollPendingAuth() {
      if (!myDeviceId || !passwordProtectionToggle.checked) return;
      if (pendingAuthSessionId && authModal.classList.contains('visible')) return;
      try {
        const res = await fetch('/api/transfer/pending/' + encodeURIComponent(myDeviceId));
        const data = await res.json().catch(() => ({}));
        const pending = data.pending || [];
        if (pending.length > 0) {
          const first = pending[0];
          showAuthModal(first.sessionId, first.senderLabel);
        }
      } catch (e) { /* ignore */ }
    }

    function hideAuthModal() {
      authModal.classList.remove('visible');
      pendingAuthSessionId = null;
      authPinInput.value = '';
      clearAuthError();
    }

    authCancelBtn.addEventListener('click', hideAuthModal);

    authConfirmBtn.addEventListener('click', async () => {
      if (!pendingAuthSessionId) return;
      const pin = authPinInput.value.trim();
      if (!pin) {
        showAuthError('Enter the 6-digit code.');
        return;
      }
      clearAuthError();
      try {
        const res = await fetch('/api/transfer/verify', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId: pendingAuthSessionId,
            pin,
            targetDeviceId: myDeviceId
          })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          showAuthError(data.error || 'Incorrect code. Check the code on the sender and try again.');
          return;
        }
        hideAuthModal();
        showSuccess('Code accepted — receiving files...');
      } catch (e) {
        showAuthError('Could not verify code: ' + (e.message || 'network error'));
      }
    });

    authPinInput.addEventListener('input', () => {
      if (authError && authError.classList.contains('visible')) {
        clearAuthError();
      }
    });

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
    let pendingBatchIds = [];
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

    function updateBatchTitle() {
      const count = cachedBatchFiles.length;
      batchTitle.textContent = count + ' file' + (count === 1 ? '' : 's') + ' ready';
      saveAllBtn.textContent = 'Save all ' + count + ' photos to Photos';
      downloadAllBtn.textContent = 'Download all ' + count + ' files';
    }

    async function cleanupBatch() {
      for (const batchId of pendingBatchIds) {
        try {
          await fetch('/batch/' + batchId, { method: 'DELETE' });
        } catch (e) {
          console.warn('Batch cleanup failed', batchId, e);
        }
      }
      pendingBatchIds = [];
      cachedBatchFiles = [];
      thumbGrid.innerHTML = '';
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
          deviceId: localStorage.getItem(DEVICE_ID_KEY) || undefined,
          passwordProtection: passwordProtectionToggle.checked
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

      async function appendBatchThumbnails(files) {
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
        if (msg.batchId && !pendingBatchIds.includes(msg.batchId)) {
          pendingBatchIds.push(msg.batchId);
        }
        const incoming = msg.count || (msg.files && msg.files.length) || 0;
        batchWrap.classList.add('visible');
        saveAllBtn.disabled = true;
        downloadAllBtn.disabled = true;
        if (msg.files && msg.files.length) {
          batchTitle.textContent = 'Receiving ' + incoming + ' file(s)... (' + cachedBatchFiles.length + ' so far)';
          await appendBatchThumbnails(msg.files);
        }
        updateBatchTitle();
      }

      ws.onmessage = async (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'REGISTERED') {
            localStorage.setItem(DEVICE_ID_KEY, msg.deviceId);
            localStorage.setItem(DEVICE_NAME_KEY, msg.displayName);
            myDeviceId = msg.deviceId;
            deviceIdentity.style.display = 'block';
            myDeviceNameEl.textContent = msg.displayName;
            if (typeof msg.passwordProtection === 'boolean') {
              passwordProtectionToggle.checked = msg.passwordProtection;
              localStorage.setItem(PASSWORD_PROTECTION_KEY, msg.passwordProtection ? '1' : '0');
            }
            if (window.AirReceiveFloatingChat) {
              AirReceiveFloatingChat.onRegistered(msg.deviceId);
            }
            pollPendingAuth();
            return;
          }
          if (msg.type === 'AUTH_REQUIRED') {
            showAuthModal(msg.sessionId, msg.senderLabel);
            return;
          }
          if (msg.type === 'CHAT_MESSAGE' || msg.type === 'CHAT_SENT' || msg.type === 'CHAT_ERROR' ||
              msg.type === 'GLOBAL_CHAT_MESSAGE' || msg.type === 'GLOBAL_CHAT_SENT' || msg.type === 'GLOBAL_CHAT_ERROR') {
            if (window.AirReceiveFloatingChat) {
              AirReceiveFloatingChat.handleWsMessage(msg);
            }
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

    setInterval(() => {
      if (!document.hidden) pollPendingAuth();
    }, 2000);

    document.addEventListener('DOMContentLoaded', function() {
      if (window.AirReceiveFloatingChat) {
        AirReceiveFloatingChat.init({
          getExternalWs: function() { return ws; },
          getMyDeviceId: function() { return myDeviceId; }
        });
      }
    });
  </script>
  </div>
  </div>
  ${floatingChatPageTail(false, true)}
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
