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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EdithBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HeaderBar()

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

            Spacer(Modifier.height(16.dp))

            SendRow(
                enabled = connectionState == ConnectionState.CONNECTED && transcript.isNotBlank(),
                sending = connectionState == ConnectionState.SENDING,
                onSend = { viewModel.sendCurrentTranscript() }
            )

            Spacer(Modifier.weight(1f))

            SettingsSection()
        }
    }
}

@Composable
private fun HeaderBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "EDITH",
            color = EdithAccent,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "GLASSES CONTROLLER",
            color = EdithTextPrimary.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Divider(color = EdithAccentDim, thickness = 1.dp, modifier = Modifier.padding(top = 8.dp))
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
                    .background(indicatorColor)
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
private fun SendRow(enabled: Boolean, sending: Boolean, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sending) {
            Text("SENDING...", color = EdithWarning, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
        }
        Button(
            onClick = onSend,
            enabled = enabled && !sending,
            colors = ButtonDefaults.buttonColors(containerColor = EdithAccent, contentColor = Color.Black)
        ) {
            Text("SEND TO GLASSES")
        }
    }
}

@Composable
private fun SettingsSection() {
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
