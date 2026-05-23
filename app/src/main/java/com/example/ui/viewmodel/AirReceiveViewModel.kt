package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PhotoRepository
import com.example.data.ReceivedPhoto
import com.example.server.AirReceiveServer
import com.example.server.AirReceiveGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

enum class TransferStatus {
    IN_PROGRESS, COMPLETED, FAILED
}

data class ActiveTransfer(
    val fileName: String,
    val progress: Float,
    val bytesRead: Long,
    val totalBytes: Long,
    val status: TransferStatus
)

data class ServerState(
    val isRunning: Boolean = false,
    val serverUrl: String = "",
    val ipAddress: String = "",
    val networkName: String = "Local Network",
    val activeTransfer: ActiveTransfer? = null,
    val customUrl: String = ""
)

sealed interface ViewModelEvent {
    object TransferSuccess : ViewModelEvent
    class Error(val message: String) : ViewModelEvent
}

class AirReceiveViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PhotoRepository
    val receivedPhotos: StateFlow<List<ReceivedPhoto>>

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<ViewModelEvent>()
    val eventFlow: SharedFlow<ViewModelEvent> = _eventFlow.asSharedFlow()

    private var airReceiveServer: AirReceiveServer? = null
    private var airReceiveGatewayClient: AirReceiveGatewayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val prefs = application.getSharedPreferences("airreceive_prefs", Context.MODE_PRIVATE)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = PhotoRepository(database.photoDao())
        
        receivedPhotos = repository.allPhotos
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        // Clear the non-functional preset cloud URL if it exists in SharedPreferences
        if (prefs.getString("custom_url", "").orEmpty().contains("run.app")) {
            prefs.edit().remove("custom_url").apply()
        }

        // Monitor network and setup Server state
        refreshNetworkInfo()
        
        // Auto-start the server on load
        startServer()
    }

    fun refreshNetworkInfo() {
        val ip = getLocalIpAddress() ?: ""
        val ssid = getWifiSSID()
        val port = 8080
        val savedCustomUrl = prefs.getString("custom_url", "") ?: ""
        val url = if (savedCustomUrl.isNotEmpty()) {
            savedCustomUrl
        } else if (ip.isNotEmpty()) {
            "http://$ip:$port"
        } else {
            ""
        }
        
        _serverState.update { 
            it.copy(
                ipAddress = ip,
                networkName = ssid,
                serverUrl = url,
                customUrl = savedCustomUrl
            )
        }
    }

    fun setCustomUrl(url: String) {
        val trimmed = url.trim()
        val formatted = if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
        prefs.edit().putString("custom_url", formatted).apply()
        refreshNetworkInfo()
    }

    fun startServer() {
        if (_serverState.value.isRunning) return
        
        viewModelScope.launch {
            refreshNetworkInfo()
            val ip = _serverState.value.ipAddress
            val customUrl = _serverState.value.customUrl
            if (ip.isEmpty() && customUrl.isEmpty()) {
                _eventFlow.emit(ViewModelEvent.Error("No Wi-Fi or Local network connection found."))
                return@launch
            }

            acquireLocks()

            // Define reusable transfer callbacks for both local airReceiveServer and remote airReceiveGatewayClient
            val onStart = { name: String, size: Long ->
                _serverState.update {
                    it.copy(
                        activeTransfer = ActiveTransfer(
                            fileName = name,
                            progress = 0f,
                            bytesRead = 0,
                            totalBytes = size,
                            status = TransferStatus.IN_PROGRESS
                        )
                    )
                }
            }

            val onProgress = { read: Long, total: Long ->
                _serverState.update {
                    val current = it.activeTransfer
                    if (current != null) {
                        val percentage = if (total > 0) read.toFloat() / total.toFloat() else 0f
                        it.copy(
                            activeTransfer = current.copy(
                                progress = percentage,
                                bytesRead = read,
                                totalBytes = total
                            )
                        )
                    } else {
                        it
                    }
                }
            }

            val onCompleted = { name: String, path: String, size: Long, mimeType: String, senderIp: String ->
                Log.d("AirReceiveViewModel", "Saved file complete: $name. Saving meta to Room DB.")
                
                _serverState.update {
                    val current = it.activeTransfer
                    if (current != null) {
                        it.copy(
                            activeTransfer = current.copy(
                                progress = 1.0f,
                                status = TransferStatus.COMPLETED
                            )
                        )
                    } else {
                        it
                    }
                }

                viewModelScope.launch(Dispatchers.IO) {
                    val formatted = formatBytes(size)
                    val photo = ReceivedPhoto(
                        fileName = name,
                        filePath = path,
                        fileSize = size,
                        formattedSize = formatted,
                        senderIp = senderIp,
                        mimeType = mimeType
                    )
                    repository.insertPhoto(photo)
                    _eventFlow.emit(ViewModelEvent.TransferSuccess)
                    
                    kotlinx.coroutines.delay(2500)
                    _serverState.update {
                        if (it.activeTransfer?.fileName == name && it.activeTransfer?.status == TransferStatus.COMPLETED) {
                            it.copy(activeTransfer = null)
                        } else {
                            it
                        }
                    }
                }
                Unit
            }

            val onFailed = { error: String ->
                _serverState.update {
                    val current = it.activeTransfer
                    if (current != null) {
                        it.copy(
                            activeTransfer = current.copy(
                                status = TransferStatus.FAILED
                            )
                        )
                    } else {
                        it
                    }
                }
                viewModelScope.launch {
                    _eventFlow.emit(ViewModelEvent.Error("Transfer failed: $error"))
                }
                
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000)
                    _serverState.update {
                        if (it.activeTransfer?.status == TransferStatus.FAILED) {
                            it.copy(activeTransfer = null)
                        } else {
                            it
                        }
                    }
                }
                Unit
            }

            var serverStarted = false

            // Start local HTTP server if local IP is present
            if (ip.isNotEmpty()) {
                airReceiveServer = AirReceiveServer(
                    context = getApplication(),
                    port = 8080,
                    onTransferStarted = onStart,
                    onTransferProgress = onProgress,
                    onTransferCompleted = onCompleted,
                    onTransferFailed = onFailed
                )
                val success = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    airReceiveServer?.start() ?: false
                }
                if (success) {
                    serverStarted = true
                }
            }

            // Start remote WebSocket Client connection if custom gateway URL is specified
            if (customUrl.isNotEmpty()) {
                airReceiveGatewayClient = AirReceiveGatewayClient(
                    context = getApplication(),
                    serverUrl = customUrl,
                    onTransferStarted = onStart,
                    onTransferProgress = onProgress,
                    onTransferCompleted = onCompleted,
                    onTransferFailed = onFailed
                )
                airReceiveGatewayClient?.start()
                serverStarted = true
            }

            if (serverStarted) {
                _serverState.update { it.copy(isRunning = true) }
            } else {
                releaseLocks()
                _eventFlow.emit(ViewModelEvent.Error("Failed to initialize server. Ensure network is active."))
            }
        }
    }

    fun stopServer() {
        airReceiveServer?.stop()
        airReceiveServer = null
        airReceiveGatewayClient?.stop()
        airReceiveGatewayClient = null
        releaseLocks()
        _serverState.update { it.copy(isRunning = false, activeTransfer = null) }
    }

    fun deletePhoto(photo: ReceivedPhoto) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete actual physical file
                val file = File(photo.filePath)
                if (file.exists()) {
                    file.delete()
                }
                // Delete from DB
                repository.deletePhoto(photo)
            } catch (e: Exception) {
                Log.e("AirReceiveViewModel", "Error deleting photo file", e)
                _eventFlow.emit(ViewModelEvent.Error("Could not delete photo file."))
            }
        }
    }

    fun clearAllPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete all files in receiver folder
                val outputDir = File(getApplication<Application>().filesDir, "received_photos")
                if (outputDir.exists()) {
                    outputDir.listFiles()?.forEach { file ->
                        file.delete()
                    }
                }
                repository.clearAll()
            } catch (e: Exception) {
                Log.e("AirReceiveViewModel", "Error clearing photos", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopServer()
    }

    // Helper locks to keep Wi-Fi and CPU active during transfer
    private fun acquireLocks() {
        try {
            val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirReceive::ServerWakeLock").apply {
                acquire(10 * 60 * 1000L) // 10 mins max
            }
            
            val wifiManager = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirReceive::ServerWifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.e("AirReceiveViewModel", "Failed to acquire power locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e("AirReceiveViewModel", "Failed to release power locks", e)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("AirReceiveViewModel", "Error fetching IP", ex)
        }
        return null
    }

    private fun getWifiSSID(): String {
        return try {
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "Local Network"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "Local Network"
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wifiManager = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wifiManager.connectionInfo
                // On modern Android, SSID is often "<unknown ssid>" of empty unless fine location permission is granted,
                // so we fallback gracefully.
                if (info != null && info.ssid != null && info.ssid != "<unknown ssid>") {
                    info.ssid.replace("\"", "")
                } else {
                    "Wi-Fi Connection"
                }
            } else {
                "Ethernet/LAN Network"
            }
        } catch (e: Exception) {
            "Local Network"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups >= units.size) units.size - 1 else digitGroups
        return try {
            String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
        } catch (e: Exception) {
            "$bytes B"
        }
    }
}
