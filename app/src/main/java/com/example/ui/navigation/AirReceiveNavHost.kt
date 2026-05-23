package com.example.ui.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.data.ReceivedPhoto
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.SendScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.AirReceiveViewModel
import com.example.ui.viewmodel.ServerState

@Composable
fun AirReceiveNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    viewModel: AirReceiveViewModel,
    serverState: ServerState,
    photoList: List<ReceivedPhoto>,
    selectedPhotoForView: ReceivedPhoto?,
    onPhotoSelected: (ReceivedPhoto?) -> Unit
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = AppRoute.Gallery,
        modifier = modifier
    ) {
        composable(AppRoute.Gallery) {
            GalleryScreen(
                serverState = serverState,
                photoList = photoList,
                selectedPhotoForView = selectedPhotoForView,
                onPhotoSelected = onPhotoSelected,
                viewModel = viewModel
            )
        }
        composable(AppRoute.Send) {
            SendScreen(
                serverState = serverState,
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(AppRoute.Settings) },
                onSendPhotosGateway = { viewModel.sendPhotosToGateway(it) },
                onSendPhotosLocal = { viewModel.sendPhotosToLocal(it) }
            )
        }
        composable(AppRoute.Settings) {
            SettingsScreen(
                state = serverState,
                onToggleServer = {
                    if (serverState.isRunning) viewModel.stopServer() else viewModel.startServer()
                },
                onRefreshNetwork = {
                    viewModel.refreshNetworkInfo()
                    Toast.makeText(context, "Network status scanned", Toast.LENGTH_SHORT).show()
                },
                onApplyHostedGateway = {
                    viewModel.applyHostedGateway()
                    Toast.makeText(context, "Using free AirReceive gateway", Toast.LENGTH_SHORT).show()
                },
                onClearGateway = {
                    viewModel.clearGateway()
                    Toast.makeText(context, "Reset to local Wi-Fi mode", Toast.LENGTH_SHORT).show()
                },
                onUpdateCustomUrl = { url ->
                    viewModel.setCustomUrl(url)
                    Toast.makeText(context, "Custom gateway URL saved!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
