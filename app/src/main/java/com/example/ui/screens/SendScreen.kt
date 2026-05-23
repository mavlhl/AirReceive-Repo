package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.SendToIphonePanel
import com.example.SendToIphoneSetupCard
import com.example.ui.viewmodel.AirReceiveViewModel
import com.example.ui.viewmodel.ServerState

@Composable
fun SendScreen(
    serverState: ServerState,
    viewModel: AirReceiveViewModel,
    onOpenSettings: () -> Unit,
    onSendPhotos: (List<android.net.Uri>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (serverState.customUrl.isNotEmpty()) {
            val receiveUrl = serverState.customUrl.removeSuffix("/") + "/receive"
            LaunchedEffect(serverState.customUrl) {
                viewModel.refreshReceivers()
            }
            SendToIphonePanel(
                receiveUrl = receiveUrl,
                onlineReceivers = serverState.onlineReceivers,
                selectedReceiverId = serverState.selectedReceiverId,
                onSelectReceiver = { viewModel.selectReceiver(it) },
                onRefreshReceivers = { viewModel.refreshReceivers() },
                onSendPhotos = onSendPhotos
            )
        } else {
            SendToIphoneSetupCard(onOpenSettings = onOpenSettings)
        }
    }
}
