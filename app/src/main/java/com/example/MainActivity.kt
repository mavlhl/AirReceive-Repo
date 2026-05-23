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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.ReceivedPhoto
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.*
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
            MyApplicationTheme(darkTheme = true) { // Force Dark Premium Vibe
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF06070D) // Luxurious deep slate background
                ) {
                    AirReceiveApp(viewModel)
                }
            }
        }

        // Listen for events (like completed transfers) to play audio chimes & trigger haptics
        lifecycleScopeLaunch()
    }

    private fun lifecycleScopeLaunch() {
        lifecycleScope.launch {
            viewModel.eventFlow.collectLatest { event ->
                when (event) {
                    is ViewModelEvent.TransferSuccess -> {
                        playAirDropChime()
                        triggerSuccessVibration()
                    }
                    is ViewModelEvent.SendSuccess -> {
                        playAirDropChime()
                        triggerSuccessVibration()
                        Toast.makeText(
                            this@MainActivity,
                            "Photo sent to iPhone. Save it from Safari on the receive page.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ViewModelEvent.Error -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
fun AirReceiveApp(viewModel: AirReceiveViewModel) {
    val context = LocalContext.current
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val photoList by viewModel.receivedPhotos.collectAsStateWithLifecycle()
    var selectedPhotoForView by remember { mutableStateOf<ReceivedPhoto?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (serverState.isRunning) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AIRRECEIVE",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Dashboard Dynamic Grid
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Connection Status Card
                ServerStatusCard(
                    state = serverState,
                    onToggleServer = {
                        if (serverState.isRunning) {
                            viewModel.stopServer()
                        } else {
                            viewModel.startServer()
                        }
                    },
                    onRefreshNetwork = {
                        viewModel.refreshNetworkInfo()
                        Toast.makeText(context, "Network status scanned", Toast.LENGTH_SHORT).show()
                    },
                    onUpdateCustomUrl = { url ->
                        viewModel.setCustomUrl(url)
                        if (url.isNotEmpty()) {
                            Toast.makeText(context, "Public portal URL saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Reset to local Wi-Fi mode", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                // Sharing Instructions & QR-Code Panel
                if (serverState.isRunning && serverState.serverUrl.isNotEmpty() && serverState.customUrl.isEmpty()) {
                    SharePortalPanel(url = serverState.serverUrl)
                }

                // Send to iPhone via public gateway
                if (serverState.customUrl.isNotEmpty()) {
                    val receiveUrl = remember(serverState.customUrl) {
                        serverState.customUrl.removeSuffix("/") + "/receive"
                    }
                    SendToIphonePanel(
                        receiveUrl = receiveUrl,
                        onSendPhotos = { viewModel.sendPhotosToGateway(it) }
                    )
                }

                // Active Download Progress Tracker
                AnimatedVisibility(
                    visible = serverState.activeTransfer != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    serverState.activeTransfer?.let { transfer ->
                        ActiveTransferCard(transfer = transfer)
                    }
                }

                // Photos Grid header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "RECEIVED PHOTOS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${photoList.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (photoList.isNotEmpty()) {
                        Text(
                            text = "Clear All",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .testTag("btn_clear_all")
                                .clickable {
                                    viewModel.clearAllPhotos()
                                    Toast.makeText(context, "Gallery cleared", Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                }

                // Gallery Container
                if (photoList.isEmpty()) {
                    EmptyGalleryState(serverState.isRunning)
                } else {
                    GalleryGridView(
                        photos = photoList,
                        onPhotoClick = { selectedPhotoForView = it },
                        onShareClick = { sharePhotoFile(context, File(it.filePath), it.mimeType) },
                        onDeleteClick = { viewModel.deletePhoto(it) }
                    )
                }
            }

            // Image Fullscreen Preview Overlay Modal
            selectedPhotoForView?.let { photo ->
                FullscreenPhotoViewer(
                    photo = photo,
                    onDismiss = { selectedPhotoForView = null },
                    onSaveToDisk = {
                        val file = File(photo.filePath)
                        val ok = GallerySaver.saveToPublicGallery(
                            context = context,
                            file = file,
                            fileName = photo.fileName,
                            mimeType = photo.mimeType
                        )
                        if (ok) {
                            Toast.makeText(context, "Saved to device Photo Album successfully", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to export photo", Toast.LENGTH_SHORT).show()
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
}

@Composable
fun ServerStatusCard(
    state: ServerState,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onUpdateCustomUrl: (String) -> Unit
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

    var showAdvancedSettings by remember { mutableStateOf(false) }
    var inputUrl by remember(state.customUrl) { mutableStateOf(state.customUrl) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                                        color = if (state.isRunning) Color(0xFF10B981) else Color(0xFFEF4444),
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
                            color = if (state.isRunning) MaterialTheme.colorScheme.primary else Color(0xFFF87171)
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
                        onClick = { showAdvancedSettings = !showAdvancedSettings },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (showAdvancedSettings || state.customUrl.isNotEmpty()) 
                                    MaterialTheme.colorScheme.primaryContainer 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Public Tunnel Setup",
                            tint = if (showAdvancedSettings || state.customUrl.isNotEmpty())
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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

            AnimatedVisibility(
                visible = showAdvancedSettings,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = "PUBLIC GATEWAY / PROXY DOMAIN SETUP",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Paste your public 'Development App URL' (from your browser's address bar) to allow access from any phone on any network.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://ais-dev-...run.app", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (inputUrl.isNotEmpty()) {
                                    IconButton(onClick = {
                                        inputUrl = ""
                                        onUpdateCustomUrl("")
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(onClick = {
                                    onUpdateCustomUrl(inputUrl)
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.isRunning && state.serverUrl.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = if (state.customUrl.isNotEmpty()) "⚡ CLOUD PORTAL ADDRESS (ACTIVE)" else "AIRRECEIVE PORTAL ADDRESS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.customUrl.isNotEmpty()) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.serverUrl,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (state.customUrl.isNotEmpty()) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "No local network detected",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Please connect to a Wi-Fi network or configure a Public Gateway URL above.",
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5)
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
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning) Color(0xFFBA1A1A) else MaterialTheme.colorScheme.primary,
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "HOW TO SEND FROM APPLE IPHONE",
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
                        .clip(RoundedCornerShape(16.dp))
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
                shape = CircleShape,
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
fun SendToIphonePanel(
    receiveUrl: String,
    onSendPhotos: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SEND TO IPHONE (GATEWAY)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color(0xFF38BDF8),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "1. On iPhone, open the receive page in Safari and keep it in the foreground.\n2. Tap Send below and choose photos.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(16.dp))
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
                        color = Color(0xFF38BDF8),
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
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = Color(0xFF38BDF8)
                )
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy iPhone Receive Link", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    pickImagesLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_send_to_iphone"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF38BDF8),
                    contentColor = Color(0xFF0D1117)
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Photos to iPhone", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            when {
                isDone -> Color(0xFF10B981)
                isFailed -> Color(0xFFEF4444)
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
                                    isDone -> Color(0xFF10B981).copy(alpha = 0.2f)
                                    isFailed -> Color(0xFFEF4444).copy(alpha = 0.2f)
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
                                isDone -> Color(0xFF34D399)
                                isFailed -> Color(0xFFF87171)
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
                                isDone -> "Transfer finalized successfully"
                                isFailed -> "Transfer error"
                                else -> "Streaming via Wi-Fi..."
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
                        isDone -> Color(0xFF34D399)
                        isFailed -> Color(0xFFF87171)
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
                    isDone -> Color(0xFF10B981)
                    isFailed -> Color(0xFFEF4444)
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
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(18.dp))
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
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FullscreenPhotoViewer(
    photo: ReceivedPhoto,
    onDismiss: () -> Unit,
    onSaveToDisk: () -> Unit,
    onSharePhoto: () -> Unit,
    onDeletePhoto: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Main Image Zoomable Canvas
            AsyncImage(
                model = photo.filePath,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )

            // Dynamic Header Info bar overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
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
                        color = Color(0xFF9CA3AF)
                    )
                }
            }

            // Bottom action tray buttons overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSaveToDisk,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(50.dp)
                        .testTag("btn_export_photo"),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(imageVector = Icons.Default.Done, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Save", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onSharePhoto,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Share", fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onDeletePhoto,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF7F1D1D).copy(alpha = 0.5f))
                        .border(1.dp, Color(0xFFEF4444), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete from App",
                        tint = Color(0xFFEF4444)
                    )
                }
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
