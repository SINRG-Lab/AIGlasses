package com.example.aiglasses.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.BuildConfig
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.link.BleState
import com.example.aiglasses.link.FirmwareStats
import com.example.aiglasses.link.LinkRoute
import com.example.aiglasses.link.TransportMetrics
import com.example.aiglasses.link.WifiPhase
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.DevStatRow
import com.example.aiglasses.ui.components.Eyebrow
import com.example.aiglasses.ui.components.LogStream
import com.example.aiglasses.ui.components.PanelCard
import com.example.aiglasses.ui.theme.Hairline
import com.example.aiglasses.ui.theme.Ink
import com.example.aiglasses.ui.theme.MonoBig
import com.example.aiglasses.ui.theme.MonoData
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metrics dashboard — parity with iOS DeveloperView: per-transport RTT +
 * pings, throughput, mic frame accounting, image gaps, reconnects, MTU/PHY,
 * WiFi lifecycle, firmware 'T' truth, per-photo transfer records, log console.
 * The one screen allowed to look like raw instrumentation (§6).
 */
@Composable
fun DeveloperScreen(viewModel: MainViewModel) {
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Developer",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // ── Hero stat tiles: 2-column, MonoBig values (§6) ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        value = if (metrics.ble.rttMs > 0) "${metrics.ble.rttMs.toInt()}" else "—",
                        unit = "ms",
                        label = "BLE RTT",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        value = if (metrics.wifi.rttMs > 0) "${metrics.wifi.rttMs.toInt()}" else "—",
                        unit = "ms",
                        label = "WIFI RTT",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        value = if (metrics.mtu > 0) "${metrics.mtu}" else "—",
                        unit = "",
                        label = "MTU",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        value = "${metrics.bleReconnects}",
                        unit = "",
                        label = "RECONNECTS",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        value = lossPercent(metrics.ble),
                        unit = "%",
                        label = "BLE LOSS",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        value = fwStats?.staRssi?.takeIf { it != 0 }?.toString() ?: "—",
                        unit = "dBm",
                        label = "STA RSSI",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── App build stamp + voice engine config ──
        devSection("App") {
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

        // ── Bluetooth (primary transport) ──
        devSection("Bluetooth") {
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

        // ── WiFi bulk lane ──
        devSection("Wi-Fi bulk lane") {
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

        // ── Firmware 'T' truth ──
        devSection("Firmware ('T' every 5 s)") {
            val fw = fwStats
            if (fw == null) {
                Text(
                    text = "No stats packet yet — arrives ~5 s after connecting (needs current firmware).",
                    style = MonoData,
                    color = TextTertiary
                )
            } else {
                FirmwareRows(fw)
            }
        }

        // ── Per-photo transfer records ──
        devSection("Photo transfers (${photoTransfers.size})") {
            if (photoTransfers.isEmpty()) {
                Text(
                    text = "No photos received yet.",
                    style = MonoData,
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

        // ── Actions: hairline-outlined buttons (§6) ──
        item {
            OutlinedButton(
                onClick = {
                    val logsText = viewModel.copyLogsToClipboard()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AI Glasses Logs", logsText))
                    Toast.makeText(context, "Logs copied (${logMessages.size} entries)", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Copy logs to clipboard", style = MaterialTheme.typography.labelLarge)
            }
        }

        // ── Full log stream: mono tail on an Ink inset card ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Log stream (${logMessages.size})", modifier = Modifier.padding(top = 8.dp))
                PanelCard(color = Ink) {
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
    }
}

// ── Section scaffolding ──

private fun androidx.compose.foundation.lazy.LazyListScope.devSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(title, modifier = Modifier.padding(top = 8.dp))
            PanelCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    content = content
                )
            }
        }
    }
}

/** 2-column stat tile: MonoBig value + uppercase label (§6). */
@Composable
private fun StatTile(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier
) {
    PanelCard(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(
                    text = value,
                    style = MonoBig,
                    color = TextPrimary
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = " $unit",
                        style = MonoData,
                        color = TextTertiary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
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
            append(if (fw.bleConnected) "BLE up" else "BLE down")
            append(if (fw.softApUp) " · AP up" else " · AP down")
            append(if (fw.wifiClientConnected) " · client up" else " · client down")
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

// ── Formatting helpers ──

private fun lossPercent(t: TransportMetrics): String {
    if (t.pingsSent <= 0) return "—"
    return String.format(Locale.US, "%.1f", 100.0 * t.pingsLost / t.pingsSent)
}

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
