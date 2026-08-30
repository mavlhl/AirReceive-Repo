package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatMessage
import com.example.server.GatewayReceiverDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    application: Application,
    private val airReceiveViewModel: AirReceiveViewModel
) : AndroidViewModel(application) {

    private val _peers = MutableStateFlow<List<GatewayReceiverDevice>>(emptyList())
    val peers: StateFlow<List<GatewayReceiverDevice>> = _peers.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private val _selectedPeer = MutableStateFlow<GatewayReceiverDevice?>(null)
    val selectedPeer: StateFlow<GatewayReceiverDevice?> = _selectedPeer.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = _selectedPeerId
        .flatMapLatest { peerId ->
            if (peerId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                airReceiveViewModel.observeChatMessages(peerId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> = airReceiveViewModel.chatUnreadCount

    private var refreshJob: Job? = null

    init {
        ensureGateway()
        startPeerRefresh()
    }

    fun ensureGateway() {
        airReceiveViewModel.ensureHostedGatewayForChat()
    }

    fun refreshPeers() {
        airReceiveViewModel.refreshChatPeers { list ->
            _peers.value = list
            val current = _selectedPeerId.value
            if (current != null && list.none { it.id == current }) {
                selectPeer(null)
            } else if (current != null) {
                _selectedPeer.value = list.firstOrNull { it.id == current }
            }
        }
    }

    fun selectPeer(peer: GatewayReceiverDevice?) {
        _selectedPeerId.value = peer?.id
        _selectedPeer.value = peer
        if (peer != null) {
            airReceiveViewModel.markChatPeerRead(peer.id)
        }
    }

    fun sendMessage(text: String) {
        val peer = _selectedPeer.value ?: return
        airReceiveViewModel.sendChatMessage(peer.id, peer.displayName, text)
    }

    private fun startPeerRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                refreshPeers()
                delay(3000)
            }
        }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        super.onCleared()
    }
}
