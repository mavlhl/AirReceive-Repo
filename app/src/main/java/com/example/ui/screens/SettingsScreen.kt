package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ActiveTransferCard
import com.example.GatewayOptionRow
import com.example.SendToIphoneSetupCard
import com.example.ServerStatusCard
import com.example.SharePortalPanel
import com.example.ui.viewmodel.ActiveTransfer
import com.example.ui.viewmodel.AirReceiveViewModel
import com.example.ui.viewmodel.GatewaySelection
import com.example.ui.viewmodel.ServerState

@Composable
fun SettingsScreen(
    state: ServerState,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onApplyHostedGateway: () -> Unit,
    onClearGateway: () -> Unit,
    onUpdateCustomUrl: (String) -> Unit,
    onOpenSendTab: () -> Unit
) {
    var showCustomField by remember(state.gatewaySelection) {
        mutableStateOf(state.gatewaySelection == GatewaySelection.CUSTOM)
    }
    var inputUrl by remember(state.customUrl, state.gatewaySelection) {
        mutableStateOf(
            if (state.gatewaySelection == GatewaySelection.CUSTOM) state.customUrl else ""
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SETTINGS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
        )

        ServerStatusCard(
            state = state,
            onToggleServer = onToggleServer,
            onRefreshNetwork = onRefreshNetwork,
            onOpenSettings = { },
            showGatewaySettingsLink = false
        )

        if (state.isRunning && state.serverUrl.isNotEmpty() && state.customUrl.isEmpty()) {
            SharePortalPanel(url = state.serverUrl)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "PUBLIC GATEWAY MODE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Use the free AirReceive cloud gateway or your own Render URL for cross-network transfers.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                GatewayOptionRow(
                    label = "Free AirReceive gateway",
                    subtitle = AirReceiveViewModel.HOSTED_GATEWAY_URL,
                    selected = state.gatewaySelection == GatewaySelection.HOSTED && !showCustomField,
                    onClick = {
                        showCustomField = false
                        onApplyHostedGateway()
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                GatewayOptionRow(
                    label = "My own cloud portal",
                    subtitle = "Paste your Render or custom HTTPS URL",
                    selected = state.gatewaySelection == GatewaySelection.CUSTOM || showCustomField,
                    onClick = { showCustomField = true }
                )
                Spacer(modifier = Modifier.height(8.dp))
                GatewayOptionRow(
                    label = "Local Wi-Fi only",
                    subtitle = "No public gateway (same network)",
                    selected = state.gatewaySelection == GatewaySelection.NONE && !showCustomField,
                    onClick = {
                        showCustomField = false
                        onClearGateway()
                    }
                )

                AnimatedVisibility(
                    visible = showCustomField || state.gatewaySelection == GatewaySelection.CUSTOM,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://your-app.onrender.com", fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (inputUrl.isNotEmpty()) {
                                        IconButton(onClick = { inputUrl = "" }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onUpdateCustomUrl(inputUrl) }) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Save",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
            }
        }

        if (state.customUrl.isEmpty()) {
            SendToIphoneSetupCard(onOpenSettings = onOpenSendTab)
        }

        AnimatedVisibility(
            visible = state.activeTransfer != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            state.activeTransfer?.let { transfer: ActiveTransfer ->
                ActiveTransferCard(transfer = transfer)
            }
        }
    }
}
