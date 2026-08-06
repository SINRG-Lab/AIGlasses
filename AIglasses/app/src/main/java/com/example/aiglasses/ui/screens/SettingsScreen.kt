package com.example.aiglasses.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.BuildConfig
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.RealtimeSettings
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.Eyebrow
import com.example.aiglasses.ui.components.PanelCard
import com.example.aiglasses.ui.theme.ErrorRed
import com.example.aiglasses.ui.theme.Hairline
import com.example.aiglasses.ui.theme.MonoTelemetry
import com.example.aiglasses.ui.theme.PanelHigh
import com.example.aiglasses.ui.theme.PanelHighest
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.SignalOrangeDim
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val voiceWanted by viewModel.voiceAutoEnabled.collectAsStateWithLifecycle()
    val model by viewModel.realtimeModel.collectAsStateWithLifecycle()
    val voice by viewModel.realtimeVoice.collectAsStateWithLifecycle()
    val effort by viewModel.realtimeEffort.collectAsStateWithLifecycle()
    val wifiAuto by viewModel.wifiAuto.collectAsStateWithLifecycle()

    var localApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    val isConnected = glassesStatus.connectionState != ConnectionState.Disconnected

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
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // ── Account ──
        item { Eyebrow("Account") }
        item {
            PanelCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "OpenAI API key",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    OutlinedTextField(
                        value = localApiKey,
                        onValueChange = { localApiKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        textStyle = MonoTelemetry,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("sk-...", style = MonoTelemetry, color = TextTertiary) },
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Text(
                                    text = if (showApiKey) "Hide" else "Show",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SignalOrange,
                            unfocusedBorderColor = Hairline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextSecondary,
                            cursorColor = SignalOrange,
                            focusedContainerColor = PanelHighest,
                            unfocusedContainerColor = PanelHighest
                        ),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { viewModel.setApiKey(localApiKey) },
                            enabled = localApiKey != apiKey
                        ) {
                            Text(
                                text = "Save",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (localApiKey != apiKey) SignalOrange else TextTertiary
                            )
                        }
                    }
                }
            }
        }

        // ── Voice ──
        item { Eyebrow("Voice", modifier = Modifier.padding(top = 12.dp)) }
        item {
            PanelCard {
                Column {
                    SettingsValueRow(
                        title = "Status",
                        value = when {
                            !voiceWanted -> "off"
                            else -> when (pipelineStatus.voiceState) {
                                VoiceState.Idle -> "waiting"
                                VoiceState.Connecting -> "connecting"
                                VoiceState.Listening -> "listening"
                                VoiceState.Hearing -> "hearing"
                                VoiceState.Thinking -> "thinking"
                                VoiceState.Speaking -> "speaking"
                            }
                        },
                        mono = true
                    )
                    SettingsDivider()
                    OptionPickerRow(
                        title = "Model",
                        options = RealtimeSettings.MODELS,
                        selected = model,
                        onSelect = { viewModel.setRealtimeModel(it) }
                    )
                    SettingsDivider()
                    OptionPickerRow(
                        title = "Voice",
                        options = RealtimeSettings.VOICES,
                        selected = voice,
                        onSelect = { viewModel.setRealtimeVoice(it) }
                    )
                    SettingsDivider()
                    OptionPickerRow(
                        title = "Reasoning effort",
                        options = RealtimeSettings.EFFORTS,
                        selected = effort,
                        onSelect = { viewModel.setRealtimeEffort(it) }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = if (voiceWanted) "Stop voice" else "Start voice",
                        titleColor = if (voiceWanted) ErrorRed else SignalOrange,
                        subtitle = "Starts by itself when the glasses connect; " +
                            "stopping keeps it off until you start it again.",
                        onClick = {
                            if (voiceWanted) viewModel.stopVoice()
                            else viewModel.retryVoiceNow()
                        }
                    )
                }
            }
        }
        item {
            Text(
                text = "Model, voice and effort apply the next time the session (re)connects.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // ── Glasses ──
        item { Eyebrow("Glasses", modifier = Modifier.padding(top = 12.dp)) }
        item {
            PanelCard {
                Column {
                    SettingsValueRow(
                        title = "Device",
                        value = glassesStatus.deviceName.ifBlank { "—" },
                        mono = true
                    )
                    SettingsDivider()
                    SettingsValueRow(
                        title = "Connection",
                        value = glassesStatus.connectionState.name.lowercase(),
                        mono = true
                    )
                    if (glassesStatus.mtu > 0) {
                        SettingsDivider()
                        SettingsValueRow(title = "MTU", value = "${glassesStatus.mtu}", mono = true)
                    }
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Wi-Fi transfer",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Joins the glasses' network for faster photos",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                        Switch(
                            checked = wifiAuto,
                            onCheckedChange = { viewModel.setWifiAuto(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = SignalOrange,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = PanelHighest,
                                uncheckedBorderColor = Hairline
                            )
                        )
                    }
                }
            }
        }

        // ── About ──
        item { Eyebrow("About", modifier = Modifier.padding(top = 12.dp)) }
        item {
            PanelCard {
                Column {
                    SettingsValueRow(title = "App version", value = BuildConfig.VERSION_NAME, mono = true)
                    SettingsDivider()
                    SettingsValueRow(
                        title = "Build",
                        value = "${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})",
                        mono = true
                    )
                    SettingsDivider()
                    SettingsValueRow(title = "Min SDK", value = "API 24", mono = true)
                }
            }
        }

        // ── Destructive — last group, error color, no card tint (§6) ──
        item { Eyebrow("Connection", modifier = Modifier.padding(top = 12.dp)) }
        item {
            PanelCard {
                if (isConnected) {
                    SettingsActionRow(
                        title = "Disconnect glasses",
                        titleColor = ErrorRed,
                        subtitle = null,
                        onClick = { viewModel.stopScan() }
                    )
                } else {
                    SettingsActionRow(
                        title = "Connect glasses",
                        titleColor = SignalOrange,
                        subtitle = null,
                        onClick = { viewModel.startScan() }
                    )
                }
            }
        }
    }
}

// ── Row primitives (§6: min 56dp, h-padding 16, hairline dividers inset 16) ──

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = Hairline,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
private fun SettingsValueRow(title: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = value,
            style = if (mono) MonoTelemetry else MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    subtitle: String?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** One settings row with a horizontal strip of selectable pills. */
@Composable
private fun OptionPickerRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { if (!isSelected) onSelect(option) },
                    shape = CircleShape,
                    color = if (isSelected) SignalOrangeDim else PanelHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) SignalOrange.copy(alpha = 0.5f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) SignalOrange else TextSecondary
                        )
                    }
                }
            }
        }
    }
}
