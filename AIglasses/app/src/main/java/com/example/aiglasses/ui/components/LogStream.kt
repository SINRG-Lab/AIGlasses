package com.example.aiglasses.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun tagColor(tag: String): Color = when (tag) {
    "USER" -> Blue
    "AI" -> Purple
    "ERROR" -> Red
    "BLE" -> Green
    "AUDIO" -> Orange
    "PIPELINE" -> Pink
    "CAMERA" -> Blue
    "TTS" -> Pink
    else -> Color(0x66FFFFFF)
}

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
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                fontSize = 13.sp,
                color = TextTertiary,
                fontFamily = FontFamily.Monospace
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(entries) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val color = tagColor(entry.tag)
    val time = timeFormat.format(Date(entry.timestamp))

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = TextTertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append("[$time] ")
            }
            withStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)) {
                append("[${entry.tag}]")
            }
            withStyle(SpanStyle(color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append(" ${entry.message}")
            }
        },
        lineHeight = 16.sp
    )
}
