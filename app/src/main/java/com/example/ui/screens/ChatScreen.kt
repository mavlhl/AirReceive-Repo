package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ChatMessage
import com.example.server.GatewayReceiverDevice
import com.example.ui.components.MacPrimaryButton
import com.example.ui.components.MacPrimaryButtonText
import com.example.ui.components.MacStatusDot
import com.example.ui.theme.MacSpace2
import com.example.ui.theme.MacSystemBlue
import com.example.ui.viewmodel.ChatMode
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.ServerState
import com.example.util.ChatNotificationHelper
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(
    serverState: ServerState,
    chatViewModel: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* non-blocking */ }

    LaunchedEffect(Unit) {
        chatViewModel.ensureGateway()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ChatNotificationHelper.ensureChannel(context)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        chatViewModel.syncActiveChatContext(onChatTab = true)
        onDispose {
            chatViewModel.syncActiveChatContext(onChatTab = false)
        }
    }

    if (serverState.customUrl.isEmpty()) {
        ChatGatewaySetupPrompt(onOpenSettings = onOpenSettings)
        return
    }

    val chatMode by chatViewModel.chatMode.collectAsStateWithLifecycle()
    val inDirectThread by chatViewModel.inDirectThread.collectAsStateWithLifecycle()
    val peers by chatViewModel.peers.collectAsStateWithLifecycle()
    val selectedPeer by chatViewModel.selectedPeer.collectAsStateWithLifecycle()
    val globalMessages by chatViewModel.globalMessages.collectAsStateWithLifecycle()
    val directMessages by chatViewModel.messages.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val activeMessages = if (chatMode == ChatMode.Global) globalMessages else directMessages

    LaunchedEffect(chatMode, inDirectThread, selectedPeer?.id) {
        chatViewModel.syncActiveChatContext(onChatTab = true)
    }

    LaunchedEffect(activeMessages.size, chatMode, selectedPeer?.id) {
        if (activeMessages.isNotEmpty()) {
            listState.animateScrollToItem(activeMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MacSpace2),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!(chatMode == ChatMode.Direct && inDirectThread)) {
            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            TabRow(selectedTabIndex = if (chatMode == ChatMode.Global) 0 else 1) {
                Tab(
                    selected = chatMode == ChatMode.Global,
                    onClick = { chatViewModel.selectMode(ChatMode.Global) },
                    text = { Text("Global") }
                )
                Tab(
                    selected = chatMode == ChatMode.Direct,
                    onClick = { chatViewModel.selectMode(ChatMode.Direct) },
                    text = { Text("Direct") }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                chatMode == ChatMode.Global -> {
                    GlobalChatPanel(
                        messages = globalMessages,
                        draft = draft,
                        onDraftChange = { draft = it },
                        onSend = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                chatViewModel.sendMessage(text)
                                draft = ""
                            }
                        },
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                inDirectThread && selectedPeer != null -> {
                    DirectThreadPanel(
                        peer = selectedPeer!!,
                        messages = directMessages,
                        draft = draft,
                        onDraftChange = { draft = it },
                        onBack = { chatViewModel.closeDirectThread() },
                        onSend = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                chatViewModel.sendMessage(text)
                                draft = ""
                            }
                        },
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    DirectPeerListPanel(
                        peers = peers,
                        onRefreshPeers = { chatViewModel.refreshPeers() },
                        onSelectPeer = { chatViewModel.openDirectThread(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalChatPanel(
    messages: List<ChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Everyone on this gateway",
                modifier = Modifier.padding(12.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Say hello to everyone!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message = message, showSenderName = !message.isOutgoing)
                    }
                }
            }
            ChatComposeRow(
                draft = draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                enabled = true,
                placeholder = "Message everyone..."
            )
        }
    }
}

@Composable
private fun DirectPeerListPanel(
    peers: List<GatewayReceiverDevice>,
    onRefreshPeers: () -> Unit,
    onSelectPeer: (GatewayReceiverDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Online",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRefreshPeers) {
                    Text("Refresh")
                }
            }
            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No one else online — open AirReceive on another device.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(peers, key = { it.id }) { peer ->
                        ChatPeerRow(
                            peer = peer,
                            selected = false,
                            onClick = { onSelectPeer(peer) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectThreadPanel(
    peer: GatewayReceiverDevice,
    messages: List<ChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = peer.roleLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Say hello!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message = message)
                    }
                }
            }
            ChatComposeRow(
                draft = draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                enabled = true,
                placeholder = "Type a message..."
            )
        }
    }
}

@Composable
private fun ChatComposeRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    placeholder: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 2000) onDraftChange(it) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = 4,
            enabled = enabled
        )
        MacPrimaryButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank()
        ) {
            MacPrimaryButtonText("Send")
        }
    }
}

@Composable
private fun ChatPeerRow(
    peer: GatewayReceiverDevice,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MacStatusDot(isActive = true)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = peer.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                text = peer.roleLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, showSenderName: Boolean = false) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isOutgoing) {
        MacSystemBlue.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val time = remember(message.sentAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.sentAt))
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(0.85f),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            if (showSenderName && !isOutgoing) {
                Text(
                    text = message.peerDisplayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(text = message.text, fontSize = 14.sp)
            Text(
                text = buildString {
                    append(time)
                    if (message.status == ChatMessage.STATUS_QUEUED) append(" · queued")
                    if (message.status == ChatMessage.STATUS_SENDING) append(" · sending")
                    if (message.status == ChatMessage.STATUS_FAILED) append(" · failed")
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ChatGatewaySetupPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MacSpace2),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Gateway required for chat",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Chat uses the AirReceive gateway. Enable the free hosted gateway or add your own URL in Settings.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MacPrimaryButton(onClick = onOpenSettings) {
            MacPrimaryButtonText("Open Settings")
        }
    }
}
