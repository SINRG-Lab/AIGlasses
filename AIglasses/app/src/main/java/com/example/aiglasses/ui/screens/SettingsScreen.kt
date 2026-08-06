package com.example.aiglasses.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.BuildConfig
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.RealtimeSettings
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.ButtonVariant
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.components.GlassPillButton
import com.example.aiglasses.ui.theme.Blue
import com.example.aiglasses.ui.theme.GlassBorder
import com.example.aiglasses.ui.theme.GlassSurface
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
                    text = "Settings",
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Account
            item {
                SettingsSection(title = "Account") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "OpenAI API Key",
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                        OutlinedTextField(
                            value = localApiKey,
                            onValueChange = { localApiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            placeholder = { Text("sk-...", color = TextTertiary) },
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(
                                        text = if (showApiKey) "Hide" else "Show",
                                        fontSize = 12.sp,
                                        color = Blue
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Blue,
                                unfocusedBorderColor = GlassBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextSecondary,
                                cursorColor = Blue,
                                focusedContainerColor = GlassSurface,
                                unfocusedContainerColor = GlassSurface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        GlassPillButton(
                            text = "Save API Key",
                            onClick = { viewModel.setApiKey(localApiKey) },
                            variant = ButtonVariant.Accent,
                            enabled = localApiKey != apiKey,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Voice session
            item {
                SettingsSection(title = "Voice") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsRow(
                            label = "Status",
                            value = when {
                                !voiceWanted -> "Off"
                                else -> when (pipelineStatus.voiceState) {
                                    VoiceState.Idle -> "Waiting"
                                    VoiceState.Connecting -> "Connecting…"
                                    VoiceState.Listening -> "Listening"
                                    VoiceState.Hearing -> "Hearing you"
                                    VoiceState.Thinking -> "Thinking"
                                    VoiceState.Speaking -> "Speaking"
                                }
                            }
                        )
                        GlassPillButton(
                            text = if (voiceWanted) "Stop Voice" else "Start Voice",
                            onClick = {
                                if (voiceWanted) viewModel.stopVoice()
                                else viewModel.retryVoiceNow()
                            },
                            variant = if (voiceWanted) ButtonVariant.Danger else ButtonVariant.Accent,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Voice starts by itself when the glasses connect. " +
                                "Stopping keeps it off until you start it again.",
                            fontSize = 12.sp,
                            color = TextTertiary,
                            lineHeight = 16.sp
                        )
                        OptionPicker(
                            label = "Model",
                            options = RealtimeSettings.MODELS,
                            selected = model,
                            onSelect = { viewModel.setRealtimeModel(it) }
                        )
                        OptionPicker(
                            label = "Voice",
                            options = RealtimeSettings.VOICES,
                            selected = voice,
                            onSelect = { viewModel.setRealtimeVoice(it) }
                        )
                        OptionPicker(
                            label = "Reasoning effort",
                            options = RealtimeSettings.EFFORTS,
                            selected = effort,
                            onSelect = { viewModel.setRealtimeEffort(it) }
                        )
                        Text(
                            text = "Changes apply the next time the voice session (re)connects.",
                            fontSize = 12.sp,
                            color = TextTertiary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Glasses connection
            item {
                SettingsSection(title = "Glasses") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsRow(
                            label = "Device",
                            value = if (glassesStatus.deviceName.isNotBlank())
                                glassesStatus.deviceName else "Not connected"
                        )
                        SettingsRow(
                            label = "Connection",
                            value = glassesStatus.connectionState.name
                        )
                        if (glassesStatus.mtu > 0) {
                            SettingsRow(label = "MTU", value = "${glassesStatus.mtu}")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Wi-Fi photo boost",
                                    fontSize = 14.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Auto-join the glasses' Wi-Fi for faster photos",
                                    fontSize = 12.sp,
                                    color = TextTertiary
                                )
                            }
                            Switch(
                                checked = wifiAuto,
                                onCheckedChange = { viewModel.setWifiAuto(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Blue,
                                    uncheckedThumbColor = TextTertiary,
                                    uncheckedTrackColor = GlassSurface
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        if (isConnected) {
                            GlassPillButton(
                                text = "Disconnect",
                                onClick = { viewModel.stopScan() },
                                variant = ButtonVariant.Danger,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            GlassPillButton(
                                text = "Connect Glasses",
                                onClick = { viewModel.startScan() },
                                variant = ButtonVariant.Accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // About
            item {
                SettingsSection(title = "About") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsRow(label = "App version", value = BuildConfig.VERSION_NAME)
                        SettingsRow(label = "Build", value = "${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})")
                        SettingsRow(label = "Min SDK", value = "API 24 (Android 7.0)")
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SettingsSection(
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

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Text(text = value, fontSize = 14.sp, color = TextTertiary)
    }
}

/** One row of selectable pills (model / voice / effort). */
@Composable
private fun OptionPicker(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextTertiary
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { if (!isSelected) onSelect(option) },
                    shape = RoundedCornerShape(980.dp),
                    color = if (isSelected) Blue.copy(alpha = 0.2f) else GlassSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Blue.copy(alpha = 0.5f) else GlassBorder
                    )
                ) {
                    Text(
                        text = option,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Blue else TextSecondary
                    )
                }
            }
        }
    }
}
