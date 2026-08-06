package com.example.aiglasses.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.ui.theme.BleBlue
import com.example.aiglasses.ui.theme.ErrorRed
import com.example.aiglasses.ui.theme.MonoData
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary
import com.example.aiglasses.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

// Severity = tinted first token only (spec §6): ERR red, WARN amber,
// voice/user activity orange, link telemetry blue, the rest tertiary.
private fun tagColor(tag: String): Color = when (tag) {
    "ERROR" -> ErrorRed
    "WARN" -> WarnAmber
    "USER", "AI", "VOICE" -> SignalOrange
    "LINK", "CAMERA", "VISION" -> BleBlue
    else -> TextTertiary
}

/** Mono log tail on the Ink inset card (Developer screen). */
@Composable
fun LogStream(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No log entries yet"
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    if (entries.isEmpty()) {
        Box(
            modifier = modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                style = MonoData,
                color = TextTertiary
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries) { entry ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = TextTertiary)) {
                            append("${timeFormat.format(Date(entry.timestamp))} ")
                        }
                        withStyle(SpanStyle(color = tagColor(entry.tag))) {
                            append(entry.tag)
                        }
                        withStyle(SpanStyle(color = TextSecondary)) {
                            append(" ${entry.message}")
                        }
                    },
                    style = MonoData
                )
            }
        }
    }
}
