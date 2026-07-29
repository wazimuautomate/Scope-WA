package com.tricreta.scopewa.ui.templates.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tricreta.scopewa.brain.uniqueness.UniquenessResult
import com.tricreta.scopewa.brain.uniqueness.summaryLine
import com.tricreta.scopewa.brain.uniqueness.warningLine
import com.tricreta.scopewa.ui.theme.ScopeAmber

/**
 * The uniqueness meter from architecture doc section 6, layer 1 — the last
 * thing the client reads before saving a template.
 *
 * It reports a *floor*, not a promise: [com.tricreta.scopewa.brain.template.TemplateAnalyzer.estimateUniqueness]
 * holds CSV values constant so the number reflects only the variation the
 * template itself provides. The footnote says so, because a meter that
 * flatters the template is worse than no meter.
 */
@Composable
fun UniquenessMeter(
    result: UniquenessResult,
    combinations: Long,
    combinationsCapped: Boolean,
    campaignSize: Int,
    campaignSizes: List<Int>,
    onCampaignSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val warning = result.warningLine()
    val accent = if (warning == null) MaterialTheme.colorScheme.primary else ScopeAmber

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Uniqueness meter", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                campaignSizes.forEach { size ->
                    FilterChip(
                        selected = size == campaignSize,
                        onClick = { onCampaignSizeChange(size) },
                        label = { Text(size.toString()) }
                    )
                }
            }

            Text(
                text = result.summaryLine(),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            LinearProgressIndicator(
                progress = { result.uniquePercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = accent
            )

            if (warning != null) {
                Text(
                    text = "⚠ $warning",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    fontWeight = FontWeight.Medium
                )
            } else if (result.total > 0) {
                Text(
                    text = "Every recipient gets text nobody else does.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent
                )
            }

            Text(
                text = combinationsLine(combinations, combinationsCapped),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Counts spintax only — CSV values are held constant, so this is the " +
                    "worst case. Real per-person values can only add uniqueness.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "No invisible characters are ever added to fake this number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun combinationsLine(combinations: Long, capped: Boolean): String = when {
    capped -> "This template can produce over a billion different messages."
    combinations <= 1L -> "This template can only produce 1 message. Add spintax: {Hi|Hello|Habari}."
    else -> "This template can produce ${formatCount(combinations)} different messages."
}

private fun formatCount(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

/** Small inline read-out for the list screen, where a full meter would be noise. */
@Composable
fun CombinationBadge(combinations: Long, capped: Boolean, modifier: Modifier = Modifier) {
    val warn = combinations <= 1L
    Text(
        modifier = modifier,
        text = if (capped) "1B+ variations" else "${formatCount(combinations)} variations",
        style = MaterialTheme.typography.labelMedium,
        color = if (warn) ScopeAmber else Color.Unspecified,
        fontWeight = if (warn) FontWeight.Medium else FontWeight.Normal
    )
}
