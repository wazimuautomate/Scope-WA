package com.tricreta.scopewa.ui.templates.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_MS = 3_000L

/**
 * "Live preview cycling through 5 random renders" — architecture doc section 7.
 *
 * Every render uses the *same* sample recipient, so anything that changes
 * between cards is variation the template itself produces. That is the whole
 * point: if the five cards read identically, so will 200 real messages.
 */
@Composable
fun PreviewCarousel(
    previews: List<String>,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(previews) { index = 0 }

    LaunchedEffect(previews) {
        if (previews.size <= 1) return@LaunchedEffect
        while (true) {
            delay(AUTO_ADVANCE_MS)
            index = (index + 1) % previews.size
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Live preview", style = MaterialTheme.typography.titleMedium)

            if (previews.isEmpty()) {
                Text(
                    text = "Type a message above and it renders here, five different ways.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val safeIndex = index.coerceIn(0, previews.lastIndex)
            val text = previews[safeIndex]

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .defaultMinSize(minHeight = 72.dp)
                    .padding(12.dp)
            ) {
                Text(
                    text = text.ifBlank { "(empty message)" },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    previews.indices.forEach { dot ->
                        Box(
                            modifier = Modifier
                                .size(if (dot == safeIndex) 9.dp else 6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (dot == safeIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                    Text(
                        text = "  render ${safeIndex + 1} of ${previews.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    TextButton(onClick = { index = (safeIndex + 1) % previews.size }) { Text("Next") }
                    TextButton(onClick = onShuffle) { Text("Shuffle") }
                }
            }

            val distinct = previews.distinct().size
            Text(
                text = if (distinct == 1) {
                    "All ${previews.size} renders came out identical — this template has no variation yet."
                } else {
                    "$distinct of ${previews.size} renders came out different."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
