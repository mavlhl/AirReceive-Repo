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

enum class ChatMode {
    Global,
    Direct
}

class ChatViewModel(
    application: Application,
    private val airReceiveViewModel: AirReceiveViewModel
) : AndroidViewModel(application) {

    private val _chatMode = MutableStateFlow(ChatMode.Global)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val _inDirectThread = MutableStateFlow(false)
    val inDirectThread: StateFlow<Boolean> = _inDirectThread.asStateFlow()

    private val _peers = MutableStateFlow<List<GatewayReceiverDevice>>(emptyList())
    val peers: StateFlow<List<GatewayReceiverDevice>> = _peers.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private val _selectedPeer = MutableStateFlow<GatewayReceiverDevice?>(null)
    val selectedPeer: StateFlow<GatewayReceiverDevice?> = _selectedPeer.asStateFlow()

    val globalMessages: StateFlow<List<ChatMessage>> =
        airReceiveViewModel.observeGlobalChatMessages()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        selectMode(ChatMode.Global)
    }

    fun ensureGateway() {
        airReceiveViewModel.ensureHostedGatewayForChat()
    }

    fun selectMode(mode: ChatMode) {
        if (mode == ChatMode.Global) {
            closeDirectThread()
        }
        _chatMode.value = mode
        if (mode == ChatMode.Global) {
            airReceiveViewModel.markChatPeerRead(ChatMessage.PEER_GLOBAL)
        }
        syncActiveChatContext()
    }

    fun refreshPeers() {
        airReceiveViewModel.refreshChatPeers { list ->
            _peers.value = list
            val current = _selectedPeerId.value
            if (current != null && list.none { it.id == current }) {
                closeDirectThread()
            } else if (current != null) {
                _selectedPeer.value = list.firstOrNull { it.id == current }
            }
        }
    }

    fun openDirectThread(peer: GatewayReceiverDevice) {
        _chatMode.value = ChatMode.Direct
        _selectedPeerId.value = peer.id
        _selectedPeer.value = peer
        _inDirectThread.value = true
        airReceiveViewModel.markChatPeerRead(peer.id)
        syncActiveChatContext()
    }

    fun closeDirectThread() {
        _inDirectThread.value = false
        _selectedPeerId.value = null
        _selectedPeer.value = null
        syncActiveChatContext()
    }

    fun syncActiveChatContext(onChatTab: Boolean = true) {
        if (!onChatTab) {
            airReceiveViewModel.setActiveChatContext(null)
            return
        }
        val context = when (_chatMode.value) {
            ChatMode.Global -> ActiveChatContext(ChatMode.Global, null)
            ChatMode.Direct -> {
                if (_inDirectThread.value) {
                    ActiveChatContext(ChatMode.Direct, _selectedPeerId.value)
                } else {
                    ActiveChatContext(ChatMode.Direct, null)
                }
            }
        }
        airReceiveViewModel.setActiveChatContext(context)
    }

    fun sendMessage(text: String) {
        when (_chatMode.value) {
            ChatMode.Global -> airReceiveViewModel.sendGlobalChatMessage(text)
            ChatMode.Direct -> {
                val peer = _selectedPeer.value ?: return
                airReceiveViewModel.sendChatMessage(peer.id, peer.displayName, text)
            }
        }
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
        airReceiveViewModel.setActiveChatContext(null)
        refreshJob?.cancel()
        super.onCleared()
    }
}
