package com.example.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.server.GatewayReceiverDevice
import com.example.ui.components.MacPrimaryButton
import com.example.ui.components.MacPrimaryButtonText
import com.example.ui.components.MacStatusDot
import com.example.ui.theme.MacSpace2
import com.example.ui.theme.MacSystemBlue
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.ServerState
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(
    serverState: ServerState,
    chatViewModel: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    LaunchedEffect(Unit) {
        chatViewModel.ensureGateway()
    }

    if (serverState.customUrl.isEmpty()) {
        ChatGatewaySetupPrompt(onOpenSettings = onOpenSettings)
        return
    }

    val peers by chatViewModel.peers.collectAsStateWithLifecycle()
    val selectedPeer by chatViewModel.selectedPeer.collectAsStateWithLifecycle()
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
  var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, selectedPeer?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MacSpace2),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Direct messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = { chatViewModel.refreshPeers() }) {
                Text("Refresh")
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Online",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                    selected = selectedPeer?.id == peer.id,
                                    onClick = { chatViewModel.selectPeer(peer) }
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = selectedPeer?.let { "${it.displayName} · ${it.roleLabel}" } ?: "Select a peer",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (selectedPeer == null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Choose someone from the list to start chatting.",
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { if (it.length <= 2000) draft = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Type a message...") },
                                maxLines = 4
                            )
                            MacPrimaryButton(
                                onClick = {
                                    val text = draft.trim()
                                    if (text.isNotEmpty()) {
                                        chatViewModel.sendMessage(text)
                                        draft = ""
                                    }
                                },
                                enabled = draft.isNotBlank()
                            ) {
                                MacPrimaryButtonText("Send")
                            }
                        }
                    }
                }
            }
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
private fun ChatBubble(message: com.example.data.ChatMessage) {
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
            Text(text = message.text, fontSize = 14.sp)
            Text(
                text = buildString {
                    append(time)
                    if (message.status == com.example.data.ChatMessage.STATUS_QUEUED) append(" · queued")
                    if (message.status == com.example.data.ChatMessage.STATUS_SENDING) append(" · sending")
                    if (message.status == com.example.data.ChatMessage.STATUS_FAILED) append(" · failed")
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
            text = "Direct messages use the AirReceive gateway. Enable the free hosted gateway or add your own URL in Settings.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MacPrimaryButton(onClick = onOpenSettings) {
            MacPrimaryButtonText("Open Settings")
        }
    }
}
