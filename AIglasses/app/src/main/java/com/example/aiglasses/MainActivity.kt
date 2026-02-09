package com.example.aiglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.aiglasses.ui.theme.AIglassesTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "aiglasses_prefs"
        private const val KEY_API_KEY = "openai_api_key"
    }

    private var bleService: BleVoiceService? = null
    private var pipeline: VoiceAssistantPipeline? = null

    // Callback for when BLE permissions are granted
    private var onPermissionsGranted: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "BLE permissions granted")
            onPermissionsGranted?.invoke()
        } else {
            Log.w(TAG, "BLE permissions denied: $permissions")
        }
    }

    // Stores crash info so we can show it on screen
    private var crashInfo: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Catch crashes and log them so we can debug
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e(TAG, "UNCAUGHT CRASH: ${throwable.message}\n$stackTrace")
            // Let the default handler finish the crash
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            AIglassesTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleService?.disconnect()
        pipeline?.destroy()
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions(onGranted: () -> Unit) {
        onPermissionsGranted = onGranted

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        permissionLauncher.launch(permissions)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load API key: BuildConfig (from secrets.properties) > SharedPreferences > empty
        val builtInKey = BuildConfig.OPENAI_API_KEY
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val initialKey = if (builtInKey.isNotBlank()) builtInKey else savedKey

        var apiKey by remember { mutableStateOf(initialKey) }
        var bleActive by remember { mutableStateOf(false) }
        var espConnected by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("Disconnected") }
        val logMessages = remember { mutableStateListOf<LogEntry>() }
        val listState = rememberLazyListState()

        fun addLog(tag: String, message: String) {
            logMessages.add(LogEntry(tag, message))
            while (logMessages.size > 100) logMessages.removeAt(0)
        }

        fun startBle() {
            if (apiKey.isBlank()) {
                statusText = "Enter API key first"
                return
            }

            // Save API key
            prefs.edit().putString(KEY_API_KEY, apiKey).apply()

            fun doStart() {
                try {
                    val openAI = OpenAIService(apiKey)
                    pipeline = VoiceAssistantPipeline(
                        context = this@MainActivity,
                        openAIService = openAI,
                        onEvent = { event ->
                            runOnUiThread {
                                when (event) {
                                    is VoiceAssistantPipeline.PipelineEvent.Processing ->
                                        addLog("PIPELINE", "Processing utterance...")
                                    is VoiceAssistantPipeline.PipelineEvent.Transcription ->
                                        addLog("USER", event.text)
                                    is VoiceAssistantPipeline.PipelineEvent.AiResponse ->
                                        addLog("AI", event.text)
                                    is VoiceAssistantPipeline.PipelineEvent.TtsSynthesizing ->
                                        addLog("TTS", "Synthesizing speech...")
                                    is VoiceAssistantPipeline.PipelineEvent.Error ->
                                        addLog("ERROR", event.message)
                                }
                            }
                        }
                    )
                    pipeline?.initTts()

                    val currentPipeline = pipeline
                    if (currentPipeline == null) {
                        addLog("ERROR", "Failed to create pipeline")
                        statusText = "Pipeline init failed"
                        return
                    }

                    bleService = BleVoiceService(
                        context = this@MainActivity,
                        pipeline = currentPipeline,
                        onEvent = { event ->
                            runOnUiThread {
                                when (event) {
                                    is BleVoiceService.BleEvent.ScanStarted -> {
                                        bleActive = true
                                        statusText = "Scanning for ESP32..."
                                        addLog("BLE", "Scanning for ESP32...")
                                    }
                                    is BleVoiceService.BleEvent.ScanStopped -> {
                                        addLog("BLE", "Scan stopped")
                                    }
                                    is BleVoiceService.BleEvent.DeviceFound -> {
                                        statusText = "Found ${event.name}, connecting..."
                                        addLog("BLE", "Found: ${event.name} [${event.address}]")
                                    }
                                    is BleVoiceService.BleEvent.Connected -> {
                                        espConnected = true
                                        statusText = "Connected to ${event.name}"
                                        addLog("BLE", "Connected to ${event.name}")
                                    }
                                    is BleVoiceService.BleEvent.Disconnected -> {
                                        espConnected = false
                                        statusText = "Disconnected"
                                        addLog("BLE", "Disconnected")
                                    }
                                    is BleVoiceService.BleEvent.MtuNegotiated -> {
                                        addLog("BLE", "MTU: ${event.mtu}")
                                    }
                                    is BleVoiceService.BleEvent.ReceivingAudio ->
                                        addLog("AUDIO", "Receiving: ${event.chunks} chunks (${event.bytes} bytes)")
                                    is BleVoiceService.BleEvent.ProcessingStarted ->
                                        addLog("BLE", "Processing utterance...")
                                    is BleVoiceService.BleEvent.SendingAudio ->
                                        addLog("AUDIO", "Sending ${event.totalBytes} bytes to ESP32")
                                    is BleVoiceService.BleEvent.AudioSent ->
                                        addLog("AUDIO", "Sent ${event.totalBytes} bytes")
                                    is BleVoiceService.BleEvent.Error ->
                                        addLog("ERROR", event.message)
                                }
                            }
                        }
                    )
                    bleService?.startScan()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BLE", e)
                    addLog("ERROR", "Start failed: ${e.message}")
                    statusText = "Error: ${e.message}"
                }
            }

            if (hasBlePermissions()) {
                doStart()
            } else {
                requestBlePermissions { doStart() }
            }
        }

        fun stopBle() {
            bleService?.disconnect()
            bleService = null
            pipeline?.destroy()
            pipeline = null
            bleActive = false
            espConnected = false
            statusText = "Disconnected"
        }

        // Auto-scroll log to bottom
        LaunchedEffect(logMessages.size) {
            if (logMessages.isNotEmpty()) {
                listState.animateScrollToItem(logMessages.size - 1)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("AI Glasses (BLE)") }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // BLE status card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (espConnected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Bluetooth Low Energy",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            if (espConnected) "Connected to ESP32"
                            else if (bleActive) "Scanning..."
                            else "Not connected",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (espConnected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No WiFi hotspot needed — direct BLE connection",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // API Key input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenAI API Key") },
                    placeholder = { Text("sk-...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !bleActive,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Status & controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(statusText, fontWeight = FontWeight.Medium)
                        Row {
                            StatusDot(active = bleActive, label = "BLE")
                            Spacer(modifier = Modifier.width(12.dp))
                            StatusDot(active = espConnected, label = "ESP32")
                        }
                    }

                    Button(
                        onClick = {
                            if (bleActive) stopBle() else startBle()
                        }
                    ) {
                        Text(if (bleActive) "Disconnect" else "Scan & Connect")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Log
                Text("Conversation Log", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (logMessages.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Tap 'Scan & Connect' to find ESP32",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            items(logMessages) { entry ->
                                LogMessageRow(entry)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusDot(active: Boolean, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = MaterialTheme.shapes.small,
                color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
            ) {}
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 12.sp)
        }
    }

    @Composable
    private fun LogMessageRow(entry: LogEntry) {
        val color = when (entry.tag) {
            "USER" -> MaterialTheme.colorScheme.primary
            "AI" -> MaterialTheme.colorScheme.tertiary
            "ERROR" -> MaterialTheme.colorScheme.error
            "BLE" -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
        val fontWeight = when (entry.tag) {
            "USER", "AI" -> FontWeight.Medium
            else -> FontWeight.Normal
        }

        Text(
            text = "[${entry.tag}] ${entry.message}",
            fontSize = 13.sp,
            color = color,
            fontWeight = fontWeight
        )
    }
}

data class LogEntry(val tag: String, val message: String)
