package com.example.aiglasses.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.theme.MonoData
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary

/** Key/value telemetry row — all numerals mono (spec §6, Developer). */
@Composable
fun DevStatRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = key,
            style = MonoData,
            color = TextTertiary,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MonoData,
            color = TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f),
            maxLines = 2
        )
    }
}
