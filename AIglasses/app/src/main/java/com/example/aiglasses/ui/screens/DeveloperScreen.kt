package com.example.aiglasses.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.BuildConfig
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.link.BleState
import com.example.aiglasses.link.FirmwareStats
import com.example.aiglasses.link.LinkRoute
import com.example.aiglasses.link.TransportMetrics
import com.example.aiglasses.link.WifiPhase
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.ButtonVariant
import com.example.aiglasses.ui.components.DevStatRow
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.components.GlassPillButton
import com.example.aiglasses.ui.components.LogStream
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metrics dashboard — parity with iOS DeveloperView: per-transport RTT +
 * pings, throughput, mic frame accounting, image gaps, reconnects, MTU/PHY,
 * WiFi lifecycle, firmware 'T' truth, per-photo transfer records, log console.
 */
@Composable
fun DeveloperScreen(viewModel: MainViewModel) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val logMessages by viewModel.logMessages.collectAsStateWithLifecycle()
    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    val wifiPhase by viewModel.wifiPhase.collectAsStateWithLifecycle()
    val metrics by viewModel.linkMetrics.collectAsStateWithLifecycle()
    val fwStats by viewModel.fwStats.collectAsStateWithLifecycle()
    val photoTransfers by viewModel.photoTransfers.collectAsStateWithLifecycle()
    val model by viewModel.realtimeModel.collectAsStateWithLifecycle()
    val voice by viewModel.realtimeVoice.collectAsStateWithLifecycle()
    val effort by viewModel.realtimeEffort.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = glassesStatus.connectionState)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Developer",
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary
                )
            }

            // App build stamp + voice engine config
            item {
                DevSection(title = "App") {
                    DevStatRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
                    DevStatRow("Realtime model", model)
                    DevStatRow("Voice / effort", "$voice / $effort")
                    DevStatRow(
                        "Voice state",
                        when (pipelineStatus.voiceState) {
                            VoiceState.Idle -> "idle"
                            VoiceState.Connecting -> "connecting"
                            VoiceState.Listening -> "listening"
                            VoiceState.Hearing -> "hearing"
                            VoiceState.Thinking -> "thinking"
                            VoiceState.Speaking -> "speaking"
                        }
                    )
                }
            }

            // Bluetooth (primary transport)
            item {
                DevSection(title = "Bluetooth") {
                    DevStatRow(
                        "State",
                        when (val s = bleState) {
                            is BleState.Connected -> "connected (${s.name})"
                            is BleState.Connecting -> "connecting"
                            is BleState.Scanning -> "scanning"
                            else -> "disconnected"
                        }
                    )
                    DevStatRow("MTU / PHY", "${if (metrics.mtu > 0) metrics.mtu else "—"} / ${phyLabel(metrics.phyTx, metrics.phyRx)}")
                    DevStatRow("Reconnects", "${metrics.bleReconnects}")
                    DevStatRow("RTT l/a/m/M", rttQuad(metrics.ble))
                    DevStatRow("Pings sent / lost", "${metrics.ble.pingsSent} / ${metrics.ble.pingsLost}")
                    DevStatRow("RX / TX rate", "${rate(metrics.ble.rxBytesPerSec)} / ${rate(metrics.ble.txBytesPerSec)}")
                    DevStatRow("RX / TX total", "${bytes(metrics.ble.rxBytesTotal)} / ${bytes(metrics.ble.txBytesTotal)}")
                    DevStatRow(
                        "Mic frames /s",
                        String.format(
                            Locale.US, "recv %.0f · ok %.0f · dup %.0f · lost %.0f",
                            metrics.micRecvPerSec, metrics.micAcceptedPerSec,
                            metrics.micDupPerSec, metrics.micLostPerSec
                        )
                    )
                    DevStatRow("Image seq gaps", "${metrics.imageSeqGapsTotal}")
                }
            }

            // WiFi bulk lane
            item {
                DevSection(title = "Wi-Fi bulk lane") {
                    DevStatRow(
                        "Phase",
                        when (val p = wifiPhase) {
                            is WifiPhase.Off -> "off"
                            is WifiPhase.Requesting -> "requesting ('F' sent)"
                            is WifiPhase.Approving -> "approving / associating"
                            is WifiPhase.Connecting -> "dialing socket"
                            is WifiPhase.Active -> "ACTIVE"
                            is WifiPhase.Failed -> "failed: ${p.message}"
                        }
                    )
                    DevStatRow("Connects / redials", "${metrics.wifiConnects} / ${metrics.wifiRedials}")
                    DevStatRow("Socket uptime", uptime(metrics.wifiSocketUptimeMs))
                    DevStatRow("RTT l/a/m/M", rttQuad(metrics.wifi))
                    DevStatRow("Pings sent / lost", "${metrics.wifi.pingsSent} / ${metrics.wifi.pingsLost}")
                    DevStatRow("RX / TX rate", "${rate(metrics.wifi.rxBytesPerSec)} / ${rate(metrics.wifi.txBytesPerSec)}")
                    DevStatRow("RX / TX total", "${bytes(metrics.wifi.rxBytesTotal)} / ${bytes(metrics.wifi.txBytesTotal)}")
                }
            }

            // Firmware 'T' truth
            item {
                DevSection(title = "Firmware ('T' every 5 s)") {
                    val fw = fwStats
                    if (fw == null) {
                        Text(
                            text = "No stats packet yet — arrives ~5 s after connecting (needs current firmware).",
                            fontSize = 12.sp,
                            color = TextTertiary,
                            lineHeight = 16.sp
                        )
                    } else {
                        FirmwareRows(fw)
                    }
                }
            }

            // Per-photo transfer records
            item {
                DevSection(title = "Photo transfers (${photoTransfers.size})") {
                    if (photoTransfers.isEmpty()) {
                        Text(
                            text = "No photos received yet.",
                            fontSize = 12.sp,
                            color = TextTertiary
                        )
                    } else {
                        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        photoTransfers.forEach { t ->
                            DevStatRow(
                                timeFormat.format(Date(t.timestampMs)),
                                String.format(
                                    Locale.US, "%s · %s · %d ms · %.1f KB/s",
                                    if (t.route == LinkRoute.Wifi) "Wi-Fi" else "BLE",
                                    bytes(t.bytes.toLong()), t.millis, t.kbPerSec
                                )
                            )
                        }
                    }
                }
            }

            // Export
            item {
                DevSection(title = "Export") {
                    Spacer(Modifier.height(4.dp))
                    GlassPillButton(
                        text = "Copy Logs to Clipboard",
                        onClick = {
                            val logsText = viewModel.copyLogsToClipboard()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AI Glasses Logs", logsText))
                            Toast.makeText(context, "Logs copied (${logMessages.size} entries)", Toast.LENGTH_SHORT).show()
                        },
                        variant = ButtonVariant.Default,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Full log stream
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LOG STREAM (${logMessages.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = TextTertiary
                    )
                    GlassCard(depth = 1, cornerRadius = 14.dp) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            LogStream(
                                entries = logMessages,
                                modifier = Modifier.fillMaxSize(),
                                emptyMessage = "No log entries"
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FirmwareRows(fw: FirmwareStats) {
    val ageS = (System.currentTimeMillis() - fw.receivedAtMs) / 1000
    DevStatRow("Packet", "v${fw.version} · ${ageS}s ago")
    DevStatRow("Uptime", uptime(fw.uptimeMs))
    DevStatRow("Free heap / PSRAM", "${bytes(fw.freeHeap)} / ${bytes(fw.freePsram)}")
    DevStatRow("ATT MTU / PHY", "${fw.attMtu} / ${fw.phyLabel}")
    DevStatRow(
        "Link flags",
        buildString {
            append(if (fw.bleConnected) "BLE ✓" else "BLE ✗")
            append(if (fw.softApUp) " · AP ✓" else " · AP ✗")
            append(if (fw.wifiClientConnected) " · client ✓" else " · client ✗")
        }
    )
    DevStatRow("Next photo route", if (fw.routeIsWifi) "Wi-Fi" else "BLE")
    DevStatRow("STA RSSI", if (fw.staRssi != 0) "${fw.staRssi} dBm" else "—")
    DevStatRow("BLE / WiFi connects", "${fw.bleConnectCount} / ${fw.wifiClientConnects}")
    DevStatRow("Pings heard BLE/WiFi", "${fw.pingsHeardBle} / ${fw.pingsHeardWifi}")
    DevStatRow("BLE tx/rx /s", "${bytes(fw.bleTxPerSec)} / ${bytes(fw.bleRxPerSec)}")
    DevStatRow("WiFi tx/rx /s", "${bytes(fw.wifiTxPerSec)} / ${bytes(fw.wifiRxPerSec)}")
    DevStatRow("BLE tx/rx total", "${bytes(fw.bleTxTotal)} / ${bytes(fw.bleRxTotal)}")
    DevStatRow("WiFi tx/rx total", "${bytes(fw.wifiTxTotal)} / ${bytes(fw.wifiRxTotal)}")
    DevStatRow(
        "Last photo",
        when (fw.lastPhotoRoute) {
            1 -> "BLE · ${bytes(fw.lastPhotoBytes)} · ${fw.lastPhotoMs} ms"
            2 -> "Wi-Fi · ${bytes(fw.lastPhotoBytes)} · ${fw.lastPhotoMs} ms"
            else -> "none yet"
        }
    )
    DevStatRow("Socket uptime (fw)", uptime(fw.wifiSocketUptimeMs))
}

@Composable
private fun DevSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = TextTertiary
        )
        GlassCard(depth = 1, cornerRadius = 14.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

// ── Formatting helpers ──

private fun rttQuad(t: TransportMetrics): String =
    if (t.rttMs <= 0 && t.rttAvgMs <= 0) "—"
    else String.format(
        Locale.US, "%.0f / %.0f / %.0f / %.0f ms",
        t.rttMs, t.rttAvgMs, t.rttMinMs, t.rttMaxMs
    )

private fun bytes(b: Long): String = when {
    b >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

private fun rate(bps: Double): String =
    if (bps <= 0) "0 B/s" else "${bytes(bps.toLong())}/s"

private fun uptime(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun phyLabel(tx: Int, rx: Int): String {
    fun name(v: Int) = when (v) {
        1 -> "1M"
        2 -> "2M"
        3 -> "Coded"
        else -> "?"
    }
    if (tx == 0 && rx == 0) return "—"
    return if (tx == rx) name(tx) else "${name(tx)}/${name(rx)}"
}
