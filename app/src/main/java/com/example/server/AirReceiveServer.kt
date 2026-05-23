package com.example.server

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class AirReceiveServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onTransferStarted: (fileName: String, fileSize: Long) -> Unit,
    private val onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    private val onTransferCompleted: (
        fileName: String, 
        filePath: String, 
        fileSize: Long, 
        mimeType: String, 
        senderIp: String
    ) -> Unit,
    private val onTransferFailed: (error: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val serverExecutor = Executors.newCachedThreadPool()
    private var isRunning = false

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            serverSocket = s
            isRunning = true
            
            // Start main acceptor loop in background thread
            Executors.newSingleThreadExecutor().submit {
                while (isRunning) {
                    try {
                        val clientSocket = s.accept()
                        serverExecutor.submit {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("AirReceiveServer", "Error accepting connection", e)
                        }
                    }
                }
            }
            Log.d("AirReceiveServer", "Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e("AirReceiveServer", "Failed to start server", e)
            false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.d("AirReceiveServer", "Server stopped")
        } catch (e: Exception) {
            Log.e("AirReceiveServer", "Error stopping server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000 // 15 seconds socket read timeout
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // Read headers byte by byte to avoid buffer-stealing from request body
            val headerStream = ByteArrayOutputStream()
            var last1 = 0
            var last2 = 0
            var last3 = 0
            var last4 = 0
            while (isRunning) {
                val b = inputStream.read()
                if (b == -1) break
                headerStream.write(b)

                last4 = last3
                last3 = last2
                last2 = last1
                last1 = b

                if (last1 == '\n'.code && last2 == '\r'.code && last3 == '\n'.code && last4 == '\r'.code) {
                    break
                }
                if (last1 == '\n'.code && last2 == '\n'.code) {
                    break
                }
                if (headerStream.size() > 8192) {
                    break // safety limit
                }
            }

            val headersText = headerStream.toString("UTF-8")
            val lines = headersText.split("\r\n", "\n")
            if (lines.isEmpty() || lines[0].isEmpty()) {
                socket.close()
                return
            }

            val requestLine = lines[0]
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) {
                socket.close()
                return
            }
            val method = requestParts[0].uppercase()
            val path = requestParts[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "GET" && (path == "/" || path == "/index.html")) {
                val responseBytes = HTML_CONTENT.toByteArray(Charsets.UTF_8)
                val httpResponse = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${responseBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                outputStream.write(httpResponse)
                outputStream.write(responseBytes)
                outputStream.flush()
            } else if (method == "POST" && path == "/upload") {
                val senderIp = socket.inetAddress?.hostAddress ?: "Unknown iOS Device"
                val rawFileName = headers["x-file-name"] ?: "photo.jpg"
                val fileName = try {
                    URLDecoder.decode(rawFileName, "UTF-8")
                } catch (e: Exception) {
                    rawFileName
                }
                val mimeType = headers["content-type"] ?: "image/jpeg"
                val contentLengthStr = headers["content-length"]
                val totalBytes = contentLengthStr?.toLongOrNull() ?: -1L

                Log.d("AirReceiveServer", "Upload starting: $fileName, Size: $totalBytes, Path: /upload")

                val outputDir = File(context.filesDir, "received_photos")
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }

                var targetFile = File(outputDir, fileName)
                if (targetFile.exists()) {
                    val ext = targetFile.extension
                    val baseName = targetFile.nameWithoutExtension
                    targetFile = File(outputDir, "${baseName}_${System.currentTimeMillis()}.${ext}")
                }

                onTransferStarted(targetFile.name, totalBytes)

                var fileOutputStream: FileOutputStream? = null
                try {
                    fileOutputStream = FileOutputStream(targetFile)
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    if (totalBytes > 0) {
                        while (totalBytesRead < totalBytes) {
                            val limit = Math.min(buffer.size.toLong(), totalBytes - totalBytesRead).toInt()
                            bytesRead = inputStream.read(buffer, 0, limit)
                            if (bytesRead == -1) break
                            fileOutputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onTransferProgress(totalBytesRead, totalBytes)
                        }
                    } else {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            fileOutputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                        }
                    }

                    fileOutputStream.flush()
                    fileOutputStream.close()
                    fileOutputStream = null

                    // Send success response
                    val reply = "Success".toByteArray(Charsets.UTF_8)
                    val httpResponse = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/plain; charset=utf-8\r\n")
                        append("Content-Length: ${reply.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    outputStream.write(httpResponse)
                    outputStream.write(reply)
                    outputStream.flush()

                    Log.d("AirReceiveServer", "Upload complete: ${targetFile.absolutePath}")
                    onTransferCompleted(
                        targetFile.name,
                        targetFile.absolutePath,
                        totalBytesRead,
                        mimeType,
                        senderIp
                    )
                } catch (e: Exception) {
                    Log.e("AirReceiveServer", "Upload failed", e)
                    fileOutputStream?.close()
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    onTransferFailed(e.localizedMessage ?: "Unknown stream error")
                    
                    val reply = "Error".toByteArray(Charsets.UTF_8)
                    val httpResponse = buildString {
                        append("HTTP/1.1 500 Internal Server Error\r\n")
                        append("Content-Type: text/plain; charset=utf-8\r\n")
                        append("Content-Length: ${reply.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    try {
                        outputStream.write(httpResponse)
                        outputStream.write(reply)
                        outputStream.flush()
                    } catch (ignored: Exception) {}
                }
            } else {
                // Return 404
                val httpResponse = buildString {
                    append("HTTP/1.1 404 Not Found\r\n")
                    append("Content-Length: 0\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                outputStream.write(httpResponse)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e("AirReceiveServer", "Error handling client connection", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    companion object {
        private val HTML_CONTENT = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0">
            <title>AirReceive - Send Photos</title>
            <style>
                ${com.example.ui.theme.MacWebStyles.ROOT_VARS}
                ${com.example.ui.theme.MacWebStyles.BODY_BASE}
                body {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                    overflow-x: hidden;
                }
                .container {
                    width: 88%;
                    max-width: 420px;
                    text-align: center;
                    padding: 32px 24px;
                    background: var(--mac-glass);
                    border-radius: var(--mac-radius-lg);
                    backdrop-filter: blur(40px) saturate(180%);
                    -webkit-backdrop-filter: blur(40px) saturate(180%);
                    box-shadow: 0 16px 40px rgba(0, 0, 0, 0.5);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                }
                .logo-container {
                    margin-bottom: 28px;
                    position: relative;
                    display: inline-block;
                }
                .airdrop-icon {
                    width: 88px;
                    height: 88px;
                    background: radial-gradient(circle, #007aff 0%, #0056b3 100%);
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin: 0 auto;
                    position: relative;
                    animation: pulse 2.5s infinite;
                    box-shadow: 0 4px 20px rgba(0, 122, 255, 0.4);
                }
                @keyframes pulse {
                    0% { box-shadow: 0 0 0 0 rgba(0, 122, 255, 0.6); }
                    70% { box-shadow: 0 0 0 20px rgba(0, 122, 255, 0); }
                    100% { box-shadow: 0 0 0 0 rgba(0, 122, 255, 0); }
                }
                .airdrop-icon svg {
                    width: 48px;
                    height: 48px;
                    fill: #ffffff;
                }
                h1 {
                    font-size: 26px;
                    font-weight: 700;
                    margin: 16px 0 8px 0;
                    letter-spacing: -0.6px;
                    color: #ffffff;
                }
                p {
                    font-size: 14px;
                    color: var(--mac-label-secondary);
                    margin: 0 0 28px 0;
                    line-height: 1.5;
                }
                .btn-select {
                    display: block;
                    background-color: #007aff;
                    color: #ffffff;
                    font-size: 16px;
                    font-weight: 600;
                    padding: 16px;
                    border-radius: var(--mac-radius);
                    border: none;
                    cursor: pointer;
                    transition: background-color 0.2s, transform 0.1s;
                    width: 100%;
                    box-sizing: border-box;
                    box-shadow: 0 6px 18px rgba(0, 122, 255, 0.35);
                }
                .btn-select:active {
                    background-color: #0056b3;
                    transform: scale(0.97);
                }
                #file-input {
                    display: none;
                }
                .upload-queue {
                    margin-top: 28px;
                    text-align: left;
                    max-height: 280px;
                    overflow-y: auto;
                    padding-right: 4px;
                }
                /* Custom Scrollbar for Queue */
                .upload-queue::-webkit-scrollbar {
                    width: 5px;
                }
                .upload-queue::-webkit-scrollbar-track {
                    background: rgba(255, 255, 255, 0.02);
                }
                .upload-queue::-webkit-scrollbar-thumb {
                    background: rgba(255, 255, 255, 0.15);
                    border-radius: 3px;
                }
                .queue-item {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    background: rgba(255, 255, 255, 0.04);
                    padding: 14px;
                    border-radius: 14px;
                    margin-bottom: 10px;
                    font-size: 13px;
                    border: 1px solid rgba(255, 255, 255, 0.04);
                }
                .file-info {
                    display: flex;
                    flex-direction: column;
                    flex-grow: 1;
                    margin-right: 14px;
                    overflow: hidden;
                }
                .file-name {
                    font-weight: 600;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    color: #edf2f7;
                }
                .file-status {
                    font-size: 11px;
                    color: #718096;
                    margin-top: 3px;
                    display: flex;
                    justify-content: space-between;
                }
                .progress-bar {
                    height: 5px;
                    background-color: rgba(255, 255, 255, 0.08);
                    border-radius: 3px;
                    margin-top: 8px;
                    overflow: hidden;
                }
                .progress-fill {
                    height: 100%;
                    background-color: var(--mac-green);
                    width: 0%;
                    transition: width 0.15s ease-out;
                    border-radius: 3px;
                }
                .status-badge {
                    font-size: 11px;
                    font-weight: 700;
                    padding: 5px 10px;
                    border-radius: 12px;
                    white-space: nowrap;
                }
                .status-waiting { background-color: rgba(255, 159, 10, 0.15); color: var(--mac-orange); }
                .status-uploading { background-color: rgba(0, 122, 255, 0.15); color: var(--mac-blue); }
                .status-completed { background-color: rgba(48, 209, 88, 0.15); color: var(--mac-green); }
                .status-failed { background-color: rgba(255, 69, 58, 0.15); color: var(--mac-red); }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="logo-container">
                    <div class="airdrop-icon">
                        <svg viewBox="0 0 24 24">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 14.59L11 18.59l-4.59-4.59L11 9.41l2 2 3.59-3.59 2.41 2.41-6 6zM12 4c.55 0 1 .45 1 1v4.59l4.59-4.59 1.41 1.41-6 6-6-6 1.41-1.41L11 9.59V5c0-.55.45-1 1-1z"/>
                        </svg>
                    </div>
                </div>
                <h1>AirReceive Link</h1>
                <p>Select and send photos instantly to the receiving Android device.</p>
                
                <button class="btn-select" onclick="document.getElementById('file-input').click()">Choose Photos</button>
                <input type="file" id="file-input" multiple accept="image/*" onchange="handleFiles(this.files)">
                
                <div class="upload-queue" id="upload-queue"></div>
            </div>

            <script>
                const queueContainer = document.getElementById('upload-queue');
                let uploadQueue = [];
                let currentlyUploading = false;

                function formatBytes(bytes, decimals = 2) {
                    if (bytes === 0) return '0 Bytes';
                    const k = 1014;
                    const dm = decimals < 0 ? 0 : decimals;
                    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                    const i = Math.floor(Math.log(bytes) / Math.log(k));
                    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
                }

                function handleFiles(files) {
                    for (let i = 0; i < files.length; i++) {
                        const file = files[i];
                        const fileId = 'file_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                        
                        const item = {
                            id: fileId,
                            file: file,
                            name: file.name,
                            size: formatBytes(file.size),
                            status: 'waiting',
                            progress: 0
                        };
                        
                        uploadQueue.push(item);
                        addQueueItemToUI(item);
                    }
                    processQueue();
                }

                function addQueueItemToUI(item) {
                    const div = document.createElement('div');
                    div.className = 'queue-item';
                    div.id = item.id;
                    div.innerHTML = `
                        <div class="file-info">
                            <span class="file-name">` + item.name + `</span>
                            <span class="file-status">
                                <span>` + item.size + `</span>
                                <span id="text-` + item.id + `">Waiting</span>
                            </span>
                            <div class="progress-bar"><div class="progress-fill" id="progress-` + item.id + `"></div></div>
                        </div>
                        <span class="status-badge status-waiting" id="badge-` + item.id + `">Waiting</span>
                    `;
                    queueContainer.appendChild(div);
                    queueContainer.scrollTop = queueContainer.scrollHeight;
                }

                async function processQueue() {
                    if (currentlyUploading) return;
                    
                    const nextItem = uploadQueue.find(item => item.status === 'waiting');
                    if (!nextItem) return;
                    
                    currentlyUploading = true;
                    nextItem.status = 'uploading';
                    
                    const badge = document.getElementById('badge-' + nextItem.id);
                    if (badge) {
                        badge.className = 'status-badge status-uploading';
                        badge.textContent = 'Sending';
                    }
                    const textStatus = document.getElementById('text-' + nextItem.id);
                    if (textStatus) textStatus.textContent = 'Uploading...';

                    try {
                        await uploadFile(nextItem);
                        nextItem.status = 'completed';
                        if (badge) {
                            badge.className = 'status-badge status-completed';
                            badge.textContent = 'Done';
                        }
                        if (textStatus) {
                            textStatus.textContent = 'Completed';
                            textStatus.style.color = '#34d399';
                        }
                    } catch (err) {
                        nextItem.status = 'failed';
                        if (badge) {
                            badge.className = 'status-badge status-failed';
                            badge.textContent = 'Failed';
                        }
                        if (textStatus) {
                            textStatus.textContent = 'Upload failed';
                            textStatus.style.color = '#f87171';
                        }
                    } finally {
                        currentlyUploading = false;
                        // Yield to let paint finish
                        setTimeout(processQueue, 150);
                    }
                }

                function uploadFile(item) {
                    return new Promise((resolve, reject) => {
                        const xhr = new XMLHttpRequest();
                        xhr.open('POST', '/upload', true);
                        
                        xhr.setRequestHeader('X-File-Name', encodeURIComponent(item.name));
                        xhr.setRequestHeader('Content-Type', item.file.type || 'application/octet-stream');
                        
                        xhr.upload.onprogress = (e) => {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                item.progress = percent;
                                const fill = document.getElementById('progress-' + item.id);
                                if (fill) fill.style.width = percent + '%';
                                const textStatus = document.getElementById('text-' + item.id);
                                if (textStatus) textStatus.textContent = 'Sending ' + percent + '%';
                            }
                        };
                        
                        xhr.onload = () => {
                            if (xhr.status >= 200 && xhr.status < 300) {
                                resolve();
                            } else {
                                reject(new Error('Server error code: ' + xhr.status));
                            }
                        };
                        
                        xhr.onerror = () => reject(new Error('Network error has occurred'));
                        xhr.send(item.file);
                    });
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
