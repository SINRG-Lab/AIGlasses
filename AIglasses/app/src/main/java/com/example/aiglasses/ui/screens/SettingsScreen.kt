package com.example.aiglasses.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.BuildConfig
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.ButtonVariant
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.components.GlassPillButton
import com.example.aiglasses.ui.theme.*

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
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

            // Account section
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

            // Glasses section
            item {
                SettingsSection(title = "Glasses") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsRow(
                            label = "Device",
                            value = if (glassesStatus.deviceName.isNotBlank())
                                glassesStatus.deviceName else "Not paired"
                        )
                        SettingsRow(
                            label = "Connection",
                            value = glassesStatus.connectionState.name
                        )
                        if (glassesStatus.mtu > 0) {
                            SettingsRow(label = "MTU", value = "${glassesStatus.mtu}")
                        }
                        if (isConnected) {
                            Spacer(Modifier.height(4.dp))
                            GlassPillButton(
                                text = "Disconnect",
                                onClick = { viewModel.stopScan() },
                                variant = ButtonVariant.Danger,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Notifications section
            item {
                SettingsSection(title = "Notifications") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsToggleRow(label = "Response alerts", value = true, onToggle = {})
                        SettingsToggleRow(label = "Connection status", value = true, onToggle = {})
                        SettingsToggleRow(label = "Error notifications", value = true, onToggle = {})
                    }
                }
            }

            // Privacy section
            item {
                SettingsSection(title = "Privacy") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsToggleRow(label = "Save conversation history", value = false, onToggle = {})
                        SettingsToggleRow(label = "Share diagnostics", value = false, onToggle = {})
                    }
                }
            }

            // About section
            item {
                SettingsSection(title = "About") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsRow(label = "App version", value = BuildConfig.VERSION_NAME)
                        SettingsRow(label = "Build", value = "${BuildConfig.VERSION_CODE}")
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

@Composable
private fun SettingsToggleRow(
    label: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Switch(
            checked = value,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Blue,
                checkedTrackColor = Blue.copy(alpha = 0.3f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = GlassSurface,
                uncheckedBorderColor = GlassBorder
            )
        )
    }
}
