package com.tricreta.scopewa.ui.templates.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tricreta.scopewa.brain.template.TemplateVariable
import com.tricreta.scopewa.ui.theme.ScopeAmber

/**
 * Screenshot 11's variable reference, as tappable chips that insert at the
 * caret instead of a copy-to-clipboard list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariableChipGroup(
    title: String,
    subtitle: String,
    variables: List<TemplateVariable>,
    onInsert: (TemplateVariable) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            variables.forEach { variable ->
                AssistChip(
                    onClick = { onInsert(variable) },
                    label = { Text(variable.placeholder) }
                )
            }
        }
    }
}

/** Plain-language legend for the chips above — what each one turns into. */
@Composable
fun VariableReference(variables: List<TemplateVariable>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide what each variable means" else "What does each variable mean?")
        }
        if (!expanded) return@Column

        variables.forEach { variable ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = variable.placeholder,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(variable.description, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "e.g. ${variable.sampleValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The editable set of CSV column names.
 *
 * This is not a nicety. `TemplateEngine` only treats `{name|there}` as
 * "the CSV value, or *there* if it's blank" when `name` is in this set —
 * otherwise it is spintax and half the campaign gets greeted "Hi name".
 * If the client's CSV uses a column this list doesn't know about, they add it
 * here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnownVariableEditor(
    knownVariables: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Columns in your CSV", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "A block like {name|there} only means \"name, or there if it's blank\" " +
                    "when the app knows name is one of your columns. Anything not on this list " +
                    "is treated as spintax and picked at random.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                knownVariables.forEach { name ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(name) },
                        label = { Text(name) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $name")
                        }
                    )
                }
            }

            if (knownVariables.isEmpty()) {
                Text(
                    text = "⚠ With no columns listed, every {a|b} block is spintax.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ScopeAmber
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Add a column") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onAdd(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add")
                }
            }
        }
    }
}
