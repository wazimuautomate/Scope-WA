package com.tricreta.scopewa.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.brain.template.TemplateVariables
import com.tricreta.scopewa.ui.templates.components.KnownVariableEditor
import com.tricreta.scopewa.ui.templates.components.PreviewCarousel
import com.tricreta.scopewa.ui.templates.components.SpintaxToolbox
import com.tricreta.scopewa.ui.templates.components.UniquenessMeter
import com.tricreta.scopewa.ui.templates.components.VariableChipGroup
import com.tricreta.scopewa.ui.templates.components.VariableReference

/**
 * The Templates editor from architecture doc section 7: variable chips, a
 * spintax editor, a live preview cycling through five random renders, and the
 * uniqueness meter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(
    templateId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplateEditorViewModel = viewModel(
        key = "template-editor-$templateId",
        factory = TemplateEditorViewModel.factory(LocalContext.current, templateId)
    )
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.savedId, state.deleted) {
        if (state.savedId != null || state.deleted) onDone()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isExistingTemplate) "Edit template" else "New message template")
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isExistingTemplate) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete template")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        onClick = viewModel::save,
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save template")
                    }
                }
            }
        }
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChanged,
                label = { Text("Template name") },
                placeholder = { Text("Data Challenge receipt") },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = {
                    val error = state.nameError
                    if (error != null) Text(error)
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChanged,
                label = { Text("Message") },
                placeholder = { Text("Enter message") },
                isError = state.bodyError != null,
                supportingText = {
                    Text(
                        state.bodyError
                            ?: "${state.insights.analysis.characterCount} characters"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )

            VariableChipGroup(
                title = "Variables from your CSV",
                subtitle = "Tap to drop one in where the cursor is. Any column in your CSV works — " +
                    "these are just the common ones.",
                variables = TemplateVariables.csv,
                onInsert = { viewModel.insertAtCursor(it.placeholder) }
            )

            VariableChipGroup(
                title = "Filled in automatically",
                subtitle = "No CSV column needed — the app works these out when it sends.",
                variables = TemplateVariables.automatic,
                onInsert = { viewModel.insertAtCursor(it.placeholder) }
            )

            VariableReference(variables = TemplateVariables.all)

            HorizontalDivider()

            SpintaxToolbox(
                analysis = state.insights.analysis,
                onInsert = viewModel::insertAtCursor
            )

            KnownVariableEditor(
                knownVariables = state.knownVariables,
                onAdd = viewModel::addKnownVariable,
                onRemove = viewModel::removeKnownVariable
            )

            PreviewCarousel(
                previews = state.insights.previews,
                onShuffle = viewModel::shufflePreviews
            )

            UniquenessMeter(
                result = state.insights.uniqueness,
                combinations = state.insights.analysis.combinations,
                combinationsCapped = state.insights.analysis.isCombinationCountCapped,
                campaignSize = state.campaignSize,
                campaignSizes = TemplateEditorUiState.CAMPAIGN_SIZES,
                onCampaignSizeChange = viewModel::onCampaignSizeChanged
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this template?") },
            text = { Text("Campaigns already sent keep the exact text they used. This only removes the template.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
