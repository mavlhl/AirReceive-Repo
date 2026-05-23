package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ActiveTransferCard
import com.example.SendToIphoneSetupCard
import com.example.ServerStatusCard
import com.example.SharePortalPanel
import com.example.ui.viewmodel.ActiveTransfer
import com.example.ui.viewmodel.ServerState

@Composable
fun HomeScreen(
    serverState: ServerState,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServerStatusCard(
            state = serverState,
            onToggleServer = onToggleServer,
            onRefreshNetwork = onRefreshNetwork,
            onOpenSettings = onOpenSettings
        )

        if (serverState.isRunning && serverState.serverUrl.isNotEmpty() && serverState.customUrl.isEmpty()) {
            SharePortalPanel(url = serverState.serverUrl)
        }

        if (serverState.customUrl.isEmpty()) {
            SendToIphoneSetupCard(onOpenSettings = onOpenSettings)
        }

        AnimatedVisibility(
            visible = serverState.activeTransfer != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            serverState.activeTransfer?.let { transfer: ActiveTransfer ->
                ActiveTransferCard(transfer = transfer)
            }
        }
    }
}
