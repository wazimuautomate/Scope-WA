package com.tricreta.scopewa.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.repository.contacts.ExportFormat

@Composable
internal fun ContactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}

/**
 * One contact row. [checked] is null for a plain row and non-null for the
 * bulk-select picker (reference screenshot 03).
 */
@Composable
internal fun ContactRow(
    contact: ContactEntity,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(contact.phoneE164)
                    if (contact.optedOut) append("  ·  blocked")
                    if (contact.isSaved) append("  ·  saved")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (contact.optedOut) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick?.invoke() })
        }
        trailing?.invoke()
    }
    HorizontalDivider()
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** New-list form from reference screenshot 04: name plus a free-text purpose. */
@Composable
internal fun NewListDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, purpose: String) -> Unit,
    initialName: String = "",
    initialPurpose: String = "",
    title: String = "New list"
) {
    var name by remember { mutableStateOf(initialName) }
    var purpose by remember { mutableStateOf(initialPurpose) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("List name") },
                    placeholder = { Text("e.g. Fifth 824") }
                )
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    singleLine = true,
                    label = { Text("Use for… (optional)") },
                    placeholder = { Text("e.g. Data Challenge receipts") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, purpose) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun AddNumberDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var number by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Blocked numbers are skipped by every campaign, permanently — " +
                        "even if the same number is imported again later.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    singleLine = true,
                    label = { Text("Phone number") },
                    placeholder = { Text("0712345678 or +254712345678") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(number) }, enabled = number.isNotBlank()) {
                Text("Block")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Export button plus its format menu. Every format writes a file, nothing else. */
@Composable
internal fun ExportMenuButton(
    enabled: Boolean,
    onExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("Export")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label) },
                    onClick = {
                        open = false
                        onExport(format)
                    }
                )
            }
        }
    }
}

/** A labelled dropdown for choosing a CSV column. */
@Composable
internal fun ColumnPicker(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    allowNone: Boolean = false,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected ?: "— none —", modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("— none —") },
                    onClick = {
                        open = false
                        onSelect(null)
                    }
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

/** `label — value` line used by the import summary. */
@Composable
internal fun SummaryLine(
    label: String,
    value: String,
    emphasis: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
            color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
