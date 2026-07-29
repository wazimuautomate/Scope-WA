package com.tricreta.scopewa.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.ui.templates.components.CombinationBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateListScreen(
    onOpenTemplate: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplateListViewModel = viewModel(
        factory = TemplateListViewModel.factory(LocalContext.current)
    )
) {
    val templates by viewModel.templates.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Templates") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenTemplate(TemplateEntity.NEW_TEMPLATE_ID) }) {
                Icon(Icons.Filled.Add, contentDescription = "New template")
            }
        }
    ) { padding ->
        if (templates.isEmpty()) {
            EmptyTemplates(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(templates, key = { it.id }) { template ->
                TemplateCard(template = template, onClick = { onOpenTemplate(template.id) })
            }
        }
    }
}

@Composable
private fun TemplateCard(template: TemplateListItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(template.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = template.bodyPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${template.variableCount} variables · ${template.spintaxBlocks} spintax",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CombinationBadge(
                    combinations = template.combinations,
                    capped = template.combinationsCapped
                )
            }
        }
    }
}

@Composable
private fun EmptyTemplates(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No templates yet", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "A template is one message with variables and spintax in it, so 200 people " +
                "get 200 different-looking messages. Tap + to write your first one.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
