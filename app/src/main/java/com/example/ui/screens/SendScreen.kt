package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.LocalWifiSendPanel
import com.example.SendToIphonePanel
import com.example.ui.viewmodel.AirReceiveViewModel
import com.example.ui.viewmodel.ServerState

@Composable
fun SendScreen(
    serverState: ServerState,
    viewModel: AirReceiveViewModel,
    onOpenSettings: () -> Unit,
    onSendPhotosGateway: (List<android.net.Uri>) -> Unit,
    onSendPhotosLocal: (List<android.net.Uri>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when {
            serverState.customUrl.isNotEmpty() -> {
                val receiveUrl = serverState.customUrl.removeSuffix("/") + "/receive"
                androidx.compose.runtime.LaunchedEffect(serverState.customUrl) {
                    viewModel.refreshReceivers()
                }
                SendToIphonePanel(
                    receiveUrl = receiveUrl,
                    onlineReceivers = serverState.onlineReceivers,
                    selectedReceiverId = serverState.selectedReceiverId,
                    onSelectReceiver = { viewModel.selectReceiver(it) },
                    onRefreshReceivers = { viewModel.refreshReceivers() },
                    onSendPhotos = onSendPhotosGateway
                )
            }
            serverState.ipAddress.isNotEmpty() -> {
                LocalWifiSendPanel(
                    targetUrl = serverState.localSendTargetUrl,
                    onTargetUrlChange = { viewModel.updateLocalSendTarget(it) },
                    onSaveTargetUrl = { },
                    onSendPhotos = onSendPhotosLocal
                )
            }
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Connect to Wi-Fi to send files on your local network, or enable the cloud gateway in Settings for cross-network send.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onOpenSettings) {
                            Text("Open Settings")
                        }
                    }
                }
            }
        }
    }
}
