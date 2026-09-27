package com.akshat.edithglasses

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.akshat.edithglasses.ui.*

class MainActivity : ComponentActivity() {

    private val viewModel: EdithViewModel by viewModels()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var permissionsGranted by remember { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                permissionsGranted = results.values.all { it }
            }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(requiredPermissions)
            }

            // The moment permissions are granted (typically right after the
            // system dialog on first launch), kick off the hands-free loop:
            // BLE auto-connect + continuous listen/transcribe/send. Runs
            // once — EdithViewModel.autoStart() itself is idempotent too.
            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted) {
                    viewModel.autoStart()
                }
            }

            EdithGlassesTheme {
                EdithApp(
                    viewModel = viewModel,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
                )
            }
        }
    }
}

@Composable
fun EdithApp(
    viewModel: EdithViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val bleStatus by viewModel.bleStatusMessage.collectAsState()
    val deviceName by viewModel.discoveredDeviceName.collectAsState()
    val listeningState by viewModel.listeningState.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val speechError by viewModel.speechError.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val lastSent by viewModel.lastSentText.collectAsState()
    val autoModeEnabled by viewModel.autoModeEnabled.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()

    // On load: if there's no key stored yet, prompt for it right away —
    // entering it here writes straight to encrypted on-device storage and
    // never touches a file that could end up in a commit.
    var showApiKeyDialog by remember { mutableStateOf(!hasApiKey) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EdithBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HudBackdrop(modifier = Modifier.matchParentSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeaderBar(
                    hasApiKey = hasApiKey,
                    onKeyIconClick = { showApiKeyDialog = true }
                )

                Spacer(Modifier.height(20.dp))

                ConnectionPanel(
                    connectionState = connectionState,
                    deviceName = deviceName ?: BleProtocol.DEVICE_NAME,
                    statusMessage = bleStatus,
                    permissionsGranted = permissionsGranted,
                    onConnect = {
                        if (permissionsGranted) viewModel.connect() else onRequestPermissions()
                    },
                    onDisconnect = { viewModel.disconnect() }
                )

                Spacer(Modifier.height(24.dp))

                OledPreview(text = lastSent ?: "EDITH ONLINE")

                Spacer(Modifier.height(24.dp))

                MicButton(
                    listeningState = listeningState,
                    autoModeEnabled = autoModeEnabled,
                    enabled = permissionsGranted,
                    onToggle = {
                        when {
                            !permissionsGranted -> onRequestPermissions()
                            autoModeEnabled -> viewModel.pauseAuto()
                            else -> viewModel.resumeAuto()
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                TranscriptionArea(
                    transcript = transcript,
                    errorMessage = speechError,
                    isTranscribing = isTranscribing
                )

                Spacer(Modifier.weight(1f))

                SettingsSection(
                    hasApiKey = hasApiKey,
                    onManageApiKey = { showApiKeyDialog = true },
                    onClearApiKey = { viewModel.clearApiKey() }
                )
            }
        }

        if (showApiKeyDialog) {
            ApiKeyDialog(
                onSave = { key ->
                    viewModel.saveApiKey(key)
                    showApiKeyDialog = false
                },
                onDismiss = { showApiKeyDialog = false }
            )
        }
    }
}

// ----------------------------------------------------------------------------
// HUD BACKDROP — animated scan grid + sweeping line, purely decorative,
// drawn once behind everything else in the app.
// ----------------------------------------------------------------------------
@Composable
private fun HudBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hudBackdrop")

    val scanProgress by transition.animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanProgress"
    )

    val glowPulse by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    Canvas(modifier = modifier) {
        val gridColor = EdithAccentDim.copy(alpha = 0.45f)
        val step = 26.dp.toPx()

        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }

        val scanY = size.height * scanProgress
        val scanBrush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                EdithAccent.copy(alpha = glowPulse),
                Color.Transparent
            ),
            startY = scanY - 60f,
            endY = scanY + 60f
        )
        drawRect(brush = scanBrush, topLeft = Offset(0f, scanY - 60f), size = androidx.compose.ui.geometry.Size(size.width, 120f))
        drawLine(
            EdithAccent.copy(alpha = (glowPulse + 0.35f).coerceAtMost(0.9f)),
            Offset(0f, scanY),
            Offset(size.width, scanY),
            strokeWidth = 1.4f
        )
    }
}

@Composable
private fun HeaderBar(hasApiKey: Boolean, onKeyIconClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "headerGlow")
    val glow by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "titleGlow"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(EdithAccent.copy(alpha = glow))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "EDITH",
                color = EdithAccent.copy(alpha = glow),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GLASSES CONTROLLER",
                color = EdithTextPrimary.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onKeyIconClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "API key",
                    tint = if (hasApiKey) EdithAccent else EdithWarning,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    Divider(
        color = EdithAccentDim,
        thickness = 1.dp,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ConnectionPanel(
    connectionState: ConnectionState,
    deviceName: String,
    statusMessage: String,
    permissionsGranted: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.SENDING
    val indicatorColor = when (connectionState) {
        ConnectionState.CONNECTED, ConnectionState.SENDING -> Color(0xFF4CD964)
        ConnectionState.CONNECTING, ConnectionState.SCANNING -> EdithWarning
        ConnectionState.DISCONNECTED -> Color(0xFFFF5C5C)
    }

    val transition = rememberInfiniteTransition(label = "indicatorPulse")
    val indicatorGlow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "indicatorGlowAnim"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EdithSurface)
            .border(1.dp, EdithAccentDim, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(indicatorColor.copy(alpha = if (isConnected) indicatorGlow else 1f))
            )
            Spacer(Modifier.width(8.dp))
            Text(deviceName, color = EdithTextPrimary, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(6.dp))
        Text(statusMessage, color = EdithTextPrimary.copy(alpha = 0.6f), fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConnect,
                enabled = !isConnected && connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.SCANNING,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = EdithAccent, contentColor = Color.Black)
            ) {
                Text("CONNECT")
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = isConnected || connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.SCANNING,
                modifier = Modifier.weight(1f)
            ) {
                Text("DISCONNECT")
            }
        }

        if (!permissionsGranted) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Bluetooth & microphone permissions are required.",
                color = EdithWarning,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OledPreview(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(2.dp, EdithAccentDim, RoundedCornerShape(10.dp))
            .padding(vertical = 18.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("OLED PREVIEW (unmirrored — glasses render flipped)", color = EdithTextPrimary.copy(alpha = 0.4f), fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            color = EdithAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MicButton(
    listeningState: ListeningState,
    autoModeEnabled: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    // The loop runs on its own now — opening the app already started it.
    // This button only pauses it (stop listening, stop auto-sending) or
    // resumes it; it's no longer how you kick off a single recording.
    val isListening = listeningState == ListeningState.LISTENING
    val isPaused = !autoModeEnabled
    val bg = when {
        isPaused -> EdithAccentDim
        isListening -> EdithWarning
        else -> EdithAccent
    }
    val iconTint = if (isPaused) EdithTextPrimary else Color.Black

    val transition = rememberInfiniteTransition(label = "micRing")
    val ringRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "ringRotation"
    )

    Box(contentAlignment = Alignment.Center) {
        // A slowly rotating segmented ring — only visible while active,
        // giving the button a "targeting system" feel instead of a plain
        // static circle.
        if (!isPaused) {
            Canvas(
                modifier = Modifier
                    .size(118.dp)
                    .rotate(ringRotation)
            ) {
                val segments = 8
                val sweep = 24f
                for (i in 0 until segments) {
                    val start = i * (360f / segments)
                    drawArc(
                        color = EdithAccent.copy(alpha = 0.55f),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(bg)
                .border(3.dp, EdithAccentDim, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onToggle,
                enabled = enabled,
                modifier = Modifier.size(80.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isPaused) "Resume auto-listening" else "Pause auto-listening",
                    tint = iconTint,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = when {
            isPaused -> "PAUSED — TAP TO RESUME"
            isListening -> "LISTENING..."
            else -> "AUTO-LISTENING"
        },
        color = EdithTextPrimary.copy(alpha = 0.7f),
        fontSize = 12.sp
    )
}

@Composable
private fun TranscriptionArea(transcript: String, errorMessage: String?, isTranscribing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EdithSurface)
            .border(1.dp, EdithAccentDim, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("TRANSCRIPTION", color = EdithTextPrimary.copy(alpha = 0.4f), fontSize = 10.sp)
            if (isTranscribing) {
                ShimmerSpinner()
                Text(
                    "TRANSCRIBING",
                    color = EdithAccent.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = transcript.ifBlank { "—" },
            color = EdithTextPrimary,
            fontSize = 15.sp
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(errorMessage, color = Color(0xFFFF5C5C), fontSize = 12.sp)
        }
    }
}


@Composable
private fun ShimmerSpinner() {
    val transition = rememberInfiniteTransition(label = "transcriptionShimmer")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinnerRotation"
    )

    Canvas(
        modifier = Modifier
            .size(14.dp)
            .rotate(rotation)
    ) {
        val shimmer = Brush.sweepGradient(
            colors = listOf(
                Color.Transparent,
                EdithAccent.copy(alpha = 0.25f),
                EdithAccent,
                Color.White,
                EdithAccent,
                EdithAccent.copy(alpha = 0.25f),
                Color.Transparent
            ),
            center = Offset(size.width / 2f, size.height / 2f)
        )
        drawArc(
            brush = shimmer,
            startAngle = -70f,
            sweepAngle = 290f,
            useCenter = false,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SettingsSection(
    hasApiKey: Boolean,
    onManageApiKey: () -> Unit,
    onClearApiKey: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = EdithAccentDim, thickness = 1.dp)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "HIDE SETTINGS" else "SETTINGS", color = EdithTextPrimary.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                InfoLine("Service UUID", BleProtocol.SERVICE_UUID.toString())
                InfoLine("Characteristic UUID", BleProtocol.TEXT_CHARACTERISTIC_UUID.toString())
                InfoLine("Device name filter", BleProtocol.DEVICE_NAME)
                InfoLine("Groq API key", if (hasApiKey) "Configured (encrypted, on-device)" else "Not set — JARVIS won't answer")

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onManageApiKey) {
                        Text(if (hasApiKey) "REPLACE KEY" else "SET API KEY")
                    }
                    if (hasApiKey) {
                        TextButton(onClick = onClearApiKey) {
                            Text("CLEAR", color = Color(0xFFFF5C5C))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = EdithTextPrimary.copy(alpha = 0.4f), fontSize = 10.sp)
        Text(value, color = EdithTextPrimary.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

// ----------------------------------------------------------------------------
// API KEY DIALOG — shown automatically on load whenever no key is stored
// yet. Saving writes straight to EncryptedSharedPreferences via
// EdithViewModel.saveApiKey(); the key never touches a file on disk that
// git could pick up.
// ----------------------------------------------------------------------------
@Composable
private fun ApiKeyDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyText by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(EdithSurface)
                .border(1.dp, EdithAccent, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = EdithAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("JARVIS // GROQ API KEY", color = EdithAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Stored encrypted on this device only — it's never written to a file, " +
                    "never built into the app, and can't end up in a git commit. " +
                    "Get a free key at console.groq.com/keys.",
                color = EdithTextPrimary.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("gsk_...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                            tint = EdithAccent
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EdithAccent,
                    unfocusedBorderColor = EdithAccentDim,
                    cursorColor = EdithAccent,
                    focusedLabelColor = EdithAccent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("SKIP FOR NOW", color = EdithTextPrimary.copy(alpha = 0.5f))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (keyText.isNotBlank()) onSave(keyText.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = EdithAccent, contentColor = Color.Black)
                ) {
                    Text("SAVE")
                }
            }
        }
    }
}
