package com.tricreta.scopewa.ui.templates.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tricreta.scopewa.brain.template.TemplateAnalysis
import com.tricreta.scopewa.ui.theme.ScopeAmber

/** A ready-made spintax block the client can drop in and edit. */
data class SpintaxSnippet(val label: String, val snippet: String)

/**
 * Spintax is described in architecture doc section 6 as "the single biggest
 * anti-fingerprint lever, and none of his current tools have it" — so it gets
 * one-tap starters rather than a syntax the client has to memorise.
 *
 * The pools are Kenyan-English/Swahili mixed on purpose: they are meant to be
 * edited, not sent as-is, and openers the client would actually use are easier
 * to edit than placeholder text.
 */
val SPINTAX_STARTERS: List<SpintaxSnippet> = listOf(
    SpintaxSnippet("Greeting", "{Hi|Hello|Habari|Niaje}"),
    SpintaxSnippet("Lead-in", "{tuko na|kuna|we have}"),
    SpintaxSnippet("Sign-off", "{Asante|Thanks|Karibu}"),
    SpintaxSnippet("Empty block", "{option one|option two}")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpintaxToolbox(
    analysis: TemplateAnalysis,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Spintax", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "{Hi|Hello|Habari} picks one option per person. It is the strongest " +
                    "thing you can do to stop 200 messages looking like one message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPINTAX_STARTERS.forEach { starter ->
                    SuggestionChip(
                        onClick = { onInsert(starter.snippet) },
                        label = { Text(starter.label) }
                    )
                }
            }

            val blocks = analysis.spintax.size
            Text(
                text = if (blocks == 0) {
                    "No spintax blocks yet — every recipient would get the same wording."
                } else {
                    "$blocks spintax ${if (blocks == 1) "block" else "blocks"} in this template."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocks == 0) ScopeAmber else MaterialTheme.colorScheme.onSurfaceVariant
            )

            val unknown = analysis.unknownVariableNames
            if (unknown.isNotEmpty()) {
                Text(
                    text = "⚠ ${unknown.joinToString(", ") { "{$it}" }} " +
                        "${if (unknown.size == 1) "is" else "are"} not a column you've listed, so " +
                        "${if (unknown.size == 1) "it" else "they"} will be sent as literal braces. " +
                        "Add the column below, or give the block options: {a|b}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ScopeAmber
                )
            }
        }
    }
}
