const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const fs = require('fs');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ noServer: true });

const PORT = process.env.PORT || 8080;
const UPLOAD_DIR = path.join('/tmp', 'airreceive_uploads');

// Ensure upload directory exists
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

// Memory map of active file transfers: fileId -> { name, path, size, mimeType, timestamp }
const fileMap = new Map();

// Track connected AirReceive phones
const activePhones = new Set();

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

// Periodic cleanup of files older than 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [id, info] of fileMap.entries()) {
    if (now - info.timestamp > 5 * 60 * 1000) {
      console.log(`[Clean] Expired file id ${id} (${info.name}) deleted.`);
      try {
        if (fs.existsSync(info.path)) {
          fs.unlinkSync(info.path);
        }
      } catch (err) {
        console.error(`[Clean Error] Failed to delete ${info.path}`, err);
      }
      fileMap.delete(id);
    }
  }
}, 60 * 1000);

// Status route for webpage to see if any phone is online
app.get('/api/status', (req, res) => {
  res.json({
    phoneConnected: activePhones.size > 0,
    connectionsCount: activePhones.size
  });
});

// Upload route
app.post('/upload', upload.single('file'), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No file provided' });
  }

  // Generate transfer details
  const fileId = req.file.filename.split('.')[0];
  const fileInfo = {
    id: fileId,
    name: req.file.originalname,
    path: req.file.path,
    size: req.file.size,
    mimeType: req.file.mimetype || 'image/jpeg',
    timestamp: Date.now()
  };

  fileMap.set(fileId, fileInfo);
  console.log(`[Upload] File received: ${fileInfo.name} (${fileInfo.size} bytes). ID: ${fileId}`);

  // Notify all connected phones via WebSocket
  if (activePhones.size === 0) {
    console.warn(`[Broadcast] No phones connected to relay the file.`);
  } else {
    const notification = JSON.stringify({
      type: 'NOTIFY_UPLOAD',
      id: fileId,
      name: fileInfo.name,
      size: fileInfo.size,
      mimeType: fileInfo.mimeType
    });

    for (const phoneSocket of activePhones) {
      if (phoneSocket.readyState === WebSocket.OPEN) {
        phoneSocket.send(notification);
        console.log(`[Broadcast] Notified phone socket of new download.`);
      }
    }
  }

  res.json({
    success: true,
    fileId: fileId,
    name: fileInfo.name
  });
});

// Download route for the AirReceive phone app
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

  console.log(`[Download] Phone is downloading file ${info.name}...`);
  res.download(info.path, info.name, (err) => {
    if (err) {
      console.error(`[Download Error] Failed streaming file ${info.name}:`, err);
    } else {
      console.log(`[Download Success] Completed download of ${info.name}. Deleting from gateway.`);
      // Delete temporary file to save cloud disk space immediately
      try {
        fs.unlinkSync(info.path);
        fileMap.delete(fileId);
      } catch (e) {
        console.error(`[Cleanup Error] Failed removing file payload:`, e);
      }
    }
  });
});

// Self-contained elegant frontend index page HTML
app.get('/', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AirReceive Cloud Gateway</title>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&family=JetBrains+Mono&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg-color: #0d1117;
      --card-bg: #161b22;
      --border-color: #30363d;
      --primary: #10B981; /* Emerald */
      --primary-hover: #059669;
      --text-main: #f0f6fc;
      --text-muted: #8b949e;
      --accent: #38bdf8;
    }

    body {
      margin: 0;
      padding: 0;
      background-color: var(--bg-color);
      color: var(--text-main);
      font-family: 'Plus Jakarta Sans', sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }

    .container {
      width: 90%;
      max-width: 520px;
      margin: 24px auto;
    }

    .card {
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 20px;
      padding: 32px;
      text-align: center;
      box-shadow: 0 10px 30px rgba(0,0,0,0.5);
    }

    .logo-container {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background: linear-gradient(135deg, #10b981, #059669);
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
      border-radius: 100px;
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
      background-color: #ef4444;
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
      font-family: 'JetBrains Mono', monospace;
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
  </style>
</head>
<body>

  <div class="container">
    <div class="card">
      <div class="logo-container">
        <!-- Photo/Image transfer vector icon -->
        <svg viewBox="0 0 24 24">
          <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
        </svg>
      </div>

      <h1>AirReceive Gateway</h1>
      <p class="tagline">Send photos & images securely from any device, anywhere in the world</p>

      <div class="status-badge" id="statusBadge">
        <span class="dot"></span>
        <span id="statusText">Checking Connection...</span>
      </div>

      <div class="drop-zone" id="dropZone">
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
          <li>Tap the <strong>Gear/Settings icon</strong> on the status card.</li>
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

    // Display current address
    urlPlaceholder.textContent = window.location.origin;

    // Direct click trigger on drag zone
    dropZone.addEventListener('click', () => fileInput.click());

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
          showSuccess();
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
</body>
</html>
  `);
});

// Handle upgrade for WebSocket server on /ws/phone
server.on('upgrade', (request, socket, head) => {
  const pathname = new URL(request.url, `http://${request.headers.host}`).pathname;

  if (pathname === '/ws/phone') {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request);
    });
  } else {
    socket.destroy();
  }
});

// Socket controller
wss.on('connection', (ws) => {
  console.log('[WebSocket] Phone application client connected.');
  activePhones.add(ws);

  ws.on('close', () => {
    console.log('[WebSocket] Phone client disconnected.');
    activePhones.delete(ws);
  });

  ws.on('error', (err) => {
    console.error('[WebSocket] Socket connection error:', err);
    activePhones.delete(ws);
  });
});

// Start the server
server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Gateway] Server active on port ${PORT}`);
  console.log(`[Gateway] Connect AirReceive client to: http://localhost:${PORT}`);
});
