package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.example.data.ReceivedPhoto
import com.example.server.GatewayReceiverDevice
import com.example.ui.components.AirReceiveTopBar
import com.example.ui.components.MacNavItem
import com.example.ui.components.MacPrimaryButton
import com.example.ui.components.MacPrimaryButtonText
import com.example.ui.components.MacSecondaryButton
import com.example.ui.components.MacSegmentedNavBar
import com.example.ui.components.MacStatusDot
import com.example.ui.navigation.AirReceiveNavHost
import com.example.ui.navigation.AppRoute
import com.example.ui.theme.MacContentBg
import com.example.ui.theme.MacGlassBorder
import com.example.ui.theme.MacGlassFill
import com.example.ui.theme.MacRedContainer
import com.example.ui.theme.MacShapeButton
import com.example.ui.theme.MacShapeLarge
import com.example.ui.theme.MacShapeMedium
import com.example.ui.theme.MacSpace1
import com.example.ui.theme.MacSpace2
import com.example.ui.theme.MacSystemBlue
import com.example.ui.theme.MacSystemGreen
import com.example.ui.theme.MacSystemOrange
import com.example.ui.theme.MacSystemRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.*
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.util.DebugAgentLog
import com.example.util.DonateLinks
import com.example.util.rememberDarkThemePreference
import com.example.util.GallerySaver
import com.example.util.QrCodeGenerator
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: AirReceiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val (darkTheme, setDarkTheme) = rememberDarkThemePreference()
            MyApplicationTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AirReceiveApp(viewModel, darkTheme = darkTheme, onToggleTheme = { setDarkTheme(!darkTheme) })
                }
            }
        }

        // Listen for events (like completed transfers) to play audio chimes & trigger haptics
        lifecycleScopeLaunch()
    }

    private fun lifecycleScopeLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.eventFlow.collect { event ->
                    if (isFinishing || isDestroyed) return@collect
                    when (event) {
                        is ViewModelEvent.TransferSuccess -> {
                            playAirDropChime()
                            triggerSuccessVibration()
                        }
                        is ViewModelEvent.SendSuccess -> {
                            playAirDropChime()
                            triggerSuccessVibration()
                            val n = event.photoCount
                            showToast("Sent $n file${if (n == 1) "" else "s"} successfully.")
                        }
                        is ViewModelEvent.SaveGalleryResult -> {
                            if (event.saved > 0) {
                                playAirDropChime()
                            }
                            showToast(
                                "Saved ${event.saved} of ${event.total} photo${if (event.total == 1) "" else "s"} to Pictures/AirReceive"
                            )
                        }
                        is ViewModelEvent.Error -> {
                            showToast(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun showToast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun playAirDropChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            // Play responsive double chime
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    tg.startTone(ToneGenerator.TONE_PROP_ACK, 160)
                } catch (ignored: Exception) {}
            }, 140)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerSuccessVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(longArrayOf(0, 100, 80, 150), -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirReceiveApp(
    viewModel: AirReceiveViewModel,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
  val statusTop = WindowInsets.statusBars.getTop(density)
  val navBottom = WindowInsets.navigationBars.getBottom(density)
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val photoList by viewModel.receivedPhotos.collectAsStateWithLifecycle()
    var selectedPhotoForView by remember { mutableStateOf<ReceivedPhoto?>(null) }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoute.Gallery
    val needsGatewaySetup = serverState.customUrl.isEmpty()
    val showReceiverHint = !serverState.isRunning

    val layoutInsets = WindowInsets.safeDrawing.asPaddingValues()
    // #region agent log
    LaunchedEffect(statusTop, navBottom, layoutInsets) {
        DebugAgentLog.log(
            location = "MainActivity.kt:ColumnLayout",
            message = "Layout insets (post-fix)",
            hypothesisId = "C",
            runId = "post-fix",
            data =
                mapOf(
                    "statusTopPx" to statusTop,
                    "navBottomPx" to navBottom,
                    "safeTopDp" to layoutInsets.calculateTopPadding().value,
                    "safeBottomDp" to layoutInsets.calculateBottomPadding().value,
                ),
        )
    }
    // #endregion

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            AirReceiveTopBar(
                modifier = Modifier.statusBarsPadding(),
                isServerRunning = serverState.isRunning,
                isDarkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
            )
            AirReceiveNavHost(
                navController = navController,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                viewModel = viewModel,
                serverState = serverState,
                photoList = photoList,
                selectedPhotoForView = selectedPhotoForView,
                onPhotoSelected = { selectedPhotoForView = it },
            )
            MacSegmentedNavBar(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = MacSpace1, vertical = MacSpace1),
                items =
                    listOf(
                        MacNavItem(
                            route = AppRoute.Gallery,
                            label = "Home",
                            icon = Icons.Default.Home,
                            showBadge = photoList.isNotEmpty(),
                            badgeText = "${photoList.size.coerceAtMost(99)}",
                        ),
                        MacNavItem(
                            route = AppRoute.Send,
                            label = "Send",
                            icon = Icons.Default.Send,
                            showBadge = needsGatewaySetup,
                        ),
                        MacNavItem(
                            route = AppRoute.Settings,
                            label = "Settings",
                            icon = Icons.Default.Settings,
                            showBadge = showReceiverHint,
                        ),
                        MacNavItem(
                            route = AppRoute.Support,
                            label = "Support",
                            icon = Icons.Default.Favorite,
                        ),
                    ),
                selectedRoute = currentRoute,
                onItemSelected = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        }

    selectedPhotoForView?.let { photo ->
        FullscreenPhotoViewer(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f),
            photo = photo,
            onDismiss = { selectedPhotoForView = null },
            onSaveToDisk = {
                if (photo.isImage) {
                    viewModel.savePhotoToGallery(photo)
                } else {
                    Toast.makeText(context, "Only images can be saved to Photos.", Toast.LENGTH_SHORT).show()
                }
            },
            onSharePhoto = { sharePhotoFile(context, File(photo.filePath), photo.mimeType) },
            onDeletePhoto = {
                viewModel.deletePhoto(photo)
                selectedPhotoForView = null
                Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
            }
        )
    }
    }
}

@Composable
fun GatewayOptionRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MacShapeMedium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ServerStatusCard(
    state: ServerState,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenSettings: () -> Unit,
    showGatewaySettingsLink: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "pulse_state")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacGlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = if (state.isRunning) MacSystemGreen else MacSystemRed,
                                        alpha = if (state.isRunning) alphaAnim else 1.0f
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isRunning) "RECEIVING ACTIVE" else "RECEIVING STOPPED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = if (state.isRunning) MaterialTheme.colorScheme.primary else MacSystemRed
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.customUrl.isNotEmpty()) "Public Gateway Mode" else state.networkName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefreshNetwork,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh connection details",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (showGatewaySettingsLink) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "Gateway settings",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.isRunning && state.serverUrl.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MacShapeLarge)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = if (state.customUrl.isNotEmpty()) "⚡ CLOUD PORTAL ADDRESS (ACTIVE)" else "AIRRECEIVE PORTAL ADDRESS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.customUrl.isNotEmpty()) MacSystemGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.serverUrl,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (state.customUrl.isNotEmpty()) MacSystemGreen else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("server_url_text"),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (state.ipAddress.isEmpty() && state.customUrl.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MacShapeLarge)
                        .background(MacRedContainer)
                        .border(1.dp, MacSystemRed.copy(alpha = 0.2f), MacShapeLarge)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "No local network detected",
                            tint = MacSystemRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Please connect to a Wi-Fi network or configure a gateway in Settings.",
                            fontSize = 12.sp,
                            color = MacSystemRed.copy(alpha = 0.85f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onToggleServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("toggle_server_btn"),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning) MacSystemRed else MaterialTheme.colorScheme.primary,
                    contentColor = if (state.isRunning) Color.White else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.isRunning) "Stop Receiver" else "Start Receiver",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SharePortalPanel(url: String) {
    val context = LocalContext.current
    val qrCodeBitmap = remember(url) {
        QrCodeGenerator.generate(url, 350)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacGlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SHARE THIS LINK OR QR",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // QR Display Container
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(MacShapeLarge)
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrCodeBitmap != null) {
                        Image(
                            bitmap = qrCodeBitmap.asImageBitmap(),
                            contentDescription = "Scan to Open Transfer Portal",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF3F4F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "QR Generation Failed",
                                tint = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Guide instruction notes
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "1. Scan QR Code",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Point iOS Camera here to open native-feel browser portal.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 8.dp)
                    )

                    Text(
                        text = "2. Send Photos",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap 'Choose Photos' & pick from library. Instant Wi-Fi stream!",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action: Copy Web link (styled matching bottom action items)
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AirReceive Upload Link", url))
                    Toast.makeText(context, "Url copied to clipboard!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_copy_link"),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copy Manual Web Link",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun SupportMaverickPanel() {
    val context = LocalContext.current
    val bmcUrl = DonateLinks.BMC_URL
    val qrCodeBitmap = remember(bmcUrl) {
        QrCodeGenerator.generate(bmcUrl, 350)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacSystemOrange.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SUPPORT MAVERICK",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MacSystemOrange,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Thank you for supporting Maverick! Your donation helps keep AirReceive updated and free to use.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(MacShapeLarge)
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrCodeBitmap != null) {
                    Image(
                        bitmap = qrCodeBitmap.asImageBitmap(),
                        contentDescription = "Buy Me a Coffee QR code",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "QR generation failed",
                        tint = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Scan QR or tap the button below",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(bmcUrl))
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_bmc_donate"),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MacSystemOrange,
                    contentColor = MacContentBg
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Buy Me a Coffee", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun SendToIphoneSetupCard(onOpenSettings: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacSystemBlue.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "SEND TO IPHONE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MacSystemBlue
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To send photos to iPhone or PC, set up the public gateway first:",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. Open the Settings tab.\n2. Choose Free AirReceive gateway or enter your own Render URL.\n3. Use the Send tab to pick a receiver and transfer files.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings", color = MacSystemBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun LocalWifiSendPanel(
    targetUrl: String,
    defaultPortalUrl: String,
    onTargetUrlChange: (String) -> Unit,
    onSaveTargetUrl: () -> Unit,
    onSendPhotos: (List<Uri>) -> Unit
) {
    var inputUrl by remember(targetUrl) { mutableStateOf(targetUrl) }
    val maxBatch = com.example.server.AirReceiveLocalSender.MAX_BATCH_FILES

    LaunchedEffect(defaultPortalUrl, targetUrl) {
        if (targetUrl.isEmpty() && defaultPortalUrl.isNotEmpty()) {
            inputUrl = defaultPortalUrl
            onTargetUrlChange(defaultPortalUrl)
        }
    }

    fun effectiveTarget(): String = inputUrl.trim().ifEmpty { targetUrl.trim() }
    fun commitAndSend(uris: List<Uri>) {
        val effective = effectiveTarget()
        if (effective.isNotEmpty() && effective != targetUrl) {
            onTargetUrlChange(effective)
        }
        onSendPhotos(uris)
    }

    val canSend = effectiveTarget().isNotBlank()

    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) commitAndSend(uris.take(maxBatch))
    }
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxBatch)
    ) { uris ->
        if (uris.isNotEmpty()) commitAndSend(uris)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacSystemGreen.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "SEND ON SAME WI-FI",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MacSystemGreen,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Receiver URL defaults to this phone's portal. Change it to the other device's URL (from their Settings) to send to them.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Receiver portal URL") },
                placeholder = { Text("http://192.168.1.10:8080", fontSize = 13.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inputUrl.isNotEmpty()) {
                            IconButton(onClick = { inputUrl = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = {
                            onTargetUrlChange(inputUrl.trim())
                            onSaveTargetUrl()
                        }) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Save URL",
                                tint = MacSystemGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )

            if (targetUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Saved: $targetUrl",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MacSystemGreen,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_send_local"),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MacSystemGreen,
                    contentColor = MacContentBg,
                    disabledContainerColor = MacSystemGreen.copy(alpha = 0.35f),
                    disabledContentColor = MacContentBg.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select photos or files", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    pickImagesLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
                shape = MacShapeButton,
                border = BorderStroke(1.dp, MacSystemGreen.copy(alpha = 0.5f))
            ) {
                Text("Photo gallery", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SendToIphonePanel(
    receiveUrl: String,
    onlineReceivers: List<GatewayReceiverDevice>,
    selectedReceiverId: String?,
    onSelectReceiver: (String?) -> Unit,
    onRefreshReceivers: () -> Unit,
    onSendPhotos: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val maxBatch = com.example.server.AirReceiveGatewaySender.MAX_BATCH_FILES
    val canSend = selectedReceiverId != null
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onSendPhotos(uris.take(maxBatch))
        }
    }
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxBatch)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onSendPhotos(uris)
        }
    }

    val qrCodeBitmap = remember(receiveUrl) {
        QrCodeGenerator.generate(receiveUrl, 350)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(containerColor = MacGlassFill),
        border = BorderStroke(1.dp, MacSystemBlue.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SEND TO DEVICE (GATEWAY)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MacSystemBlue,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "1. On the target PC or phone, open the receive page and keep it in the foreground.\n2. Pick the device below, then send up to 20 photos or files.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Send to",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onRefreshReceivers) {
                    Text("Refresh", fontSize = 12.sp, color = MacSystemBlue)
                }
            }

            if (onlineReceivers.isEmpty()) {
                Text(
                    text = "No receivers online. Open /receive on the target device, then tap Refresh.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                onlineReceivers.forEach { device ->
                    GatewayOptionRow(
                        label = device.displayName,
                        subtitle = "Online — tap to select",
                        selected = device.id == selectedReceiverId,
                        onClick = { onSelectReceiver(device.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(MacShapeLarge)
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrCodeBitmap != null) {
                        Image(
                            bitmap = qrCodeBitmap.asImageBitmap(),
                            contentDescription = "Scan to open iPhone receive page",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = receiveUrl,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MacSystemBlue,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("AirReceive iPhone Receive Link", receiveUrl)
                    )
                    Toast.makeText(context, "Receive page URL copied!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MacSystemBlue
                )
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy receive page link", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { pickFilesLauncher.launch(arrayOf("image/*")) },
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_send_to_iphone"),
                shape = MacShapeButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MacSystemBlue,
                    contentColor = MacContentBg,
                    disabledContainerColor = MacSystemBlue.copy(alpha = 0.35f),
                    disabledContentColor = MacContentBg.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select photos or files", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    pickImagesLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
                shape = MacShapeButton,
                border = BorderStroke(1.dp, MacSystemBlue.copy(alpha = 0.5f))
            ) {
                Text("Photo gallery", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ActiveTransferCard(transfer: ActiveTransfer) {
    val progressPercent = (transfer.progress * 100).toInt()
    val isDone = transfer.status == TransferStatus.COMPLETED
    val isFailed = transfer.status == TransferStatus.FAILED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_transfer_card"),
        shape = MacShapeMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            when {
                isDone -> MacSystemGreen
                isFailed -> MacSystemRed
                else -> MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isDone -> MacSystemGreen.copy(alpha = 0.2f)
                                    isFailed -> MacSystemRed.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isDone -> Icons.Default.Check
                                isFailed -> Icons.Default.Warning
                                else -> Icons.Default.Info
                            },
                            contentDescription = null,
                            tint = when {
                                isDone -> MacSystemGreen
                                isFailed -> MacSystemRed
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = transfer.fileName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                isDone && transfer.direction == TransferDirection.OUTBOUND -> "Send completed"
                                isDone -> "Transfer finalized successfully"
                                isFailed && transfer.direction == TransferDirection.OUTBOUND -> "Send failed"
                                isFailed -> "Transfer error"
                                transfer.direction == TransferDirection.OUTBOUND -> "Sending..."
                                else -> "Receiving..."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = if (isDone) "Done" else if (isFailed) "Failed" else "$progressPercent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = when {
                        isDone -> MacSystemGreen
                        isFailed -> MacSystemRed
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { if (isDone) 1.0f else if (isFailed) 0.0f else transfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    isDone -> MacSystemGreen
                    isFailed -> MacSystemRed
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun RadarVisualizer(isRunning: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar_pulse")
    val scalePulse1 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale_pulse_1"
    )
    val alphaPulse1 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha_pulse_1"
    )

    val scalePulse2 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale_pulse_2"
    )
    val alphaPulse2 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha_pulse_2"
    )

    Box(
        modifier = Modifier
            .size(220.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ambient soft purple radial gradient glow (blurred background effect)
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isRunning) 0.15f else 0.03f),
                            Color.Transparent
                        )
                    )
                )
        )

        if (isRunning) {
            // Pulse circle 1
            Box(
                modifier = Modifier
                    .size(180.dp * scalePulse1)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = alphaPulse1), CircleShape)
            )

            // Pulse circle 2
            Box(
                modifier = Modifier
                    .size(180.dp * scalePulse2)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = alphaPulse2), CircleShape)
            )
        }

        // Concentric Circle 1 (Static outline)
        Box(
            modifier = Modifier
                .size(170.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
        )

        // Concentric Circle 2 (Static outline)
        Box(
            modifier = Modifier
                .size(120.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
        )

        // Central core hub
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info, // A radar-like info symbol
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isRunning) "LISTENING" else "OFFLINE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun EmptyGalleryState(serverActive: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Aesthetic pulsing Radar core matching the HTML design theme
        RadarVisualizer(isRunning = serverActive)

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Ready to discover",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (serverActive) {
                Build.MODEL + " • Ready to receive"
            } else {
                Build.MODEL + " • Locked & offline"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (serverActive) {
                "Open Safari on iOS, scan QR and send photos here.\nThey will populate this gallery instantly!"
            } else {
                "Tap 'Start Receiver' above to begin listening for AirReceive photo streams."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
fun GalleryGridView(
    photos: List<ReceivedPhoto>,
    onPhotoClick: (ReceivedPhoto) -> Unit,
    onShareClick: (ReceivedPhoto) -> Unit,
    onSaveClick: (ReceivedPhoto) -> Unit,
    onDeleteClick: (ReceivedPhoto) -> Unit
) {
    // Jetpack Compose 2-column Grid layout matching standard layouts
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 2000.dp) // Avoid infinite constraint errors inside Scrollable Column
            .testTag("gallery_grid"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false // Scoll is handled by root container
    ) {
        items(photos, key = { it.id }) { photo ->
            PhotoCard(
                photo = photo,
                onClick = { onPhotoClick(photo) },
                onShare = { onShareClick(photo) },
                onSaveToGallery = { onSaveClick(photo) },
                onDelete = { onDeleteClick(photo) }
            )
        }
    }
}

@Composable
fun PhotoCard(
    photo: ReceivedPhoto,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onSaveToGallery: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(MacShapeMedium)
            .clickable(onClick = onClick)
            .testTag("photo_card_${photo.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Photo preview
                AsyncImage(
                    model = photo.filePath,
                    contentDescription = photo.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // Meta Info Footer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Text(
                        text = photo.fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = photo.formattedSize,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = photo.senderIp.substringAfterLast("."),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Quick hover transparent icon panel
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (photo.isImage) {
                    IconButton(
                        onClick = onSaveToGallery,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Save to Photos",
                            tint = MacSystemGreen,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MacSystemRed,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FullscreenPhotoViewer(
    modifier: Modifier = Modifier,
    photo: ReceivedPhoto,
    onDismiss: () -> Unit,
    onSaveToDisk: () -> Unit,
    onSharePhoto: () -> Unit,
    onDeletePhoto: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = photo.fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "From ${photo.senderIp}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                AsyncImage(
                    model = photo.filePath,
                    contentDescription = photo.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (photo.isImage) {
                    Button(
                        onClick = onSaveToDisk,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_export_photo"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Save to Photos", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = onSharePhoto,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Share", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                IconButton(
                    onClick = onDeletePhoto,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MacRedContainer)
                        .border(1.dp, MacSystemRed, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete from App",
                        tint = MacSystemRed
                    )
                }
            }
        }
}

// Global share utility triggered on main actions
fun sharePhotoFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "com.aistudio.airreceive.hzqwpb.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Photo via..."))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error initiating file share dialog", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
