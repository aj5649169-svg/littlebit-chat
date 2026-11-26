package com.littlebit.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.Assistant
import com.littlebit.chat.data.ContextNote
import java.util.UUID

/**
 * Optional, one-tap assistants plus pinned context notes. Chat stays a blank
 * scratchpad by default: this screen only changes the active persona when the
 * user explicitly picks one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Assistant?>(null) }
    var editingNote by remember { mutableStateOf<ContextNote?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistants & Notes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "assistants-title") {
                Text("Assistants", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "assistants-hint") {
                Text(
                    "Pick one to apply its persona and optional sampling overrides. Chat stays blank until you send a message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(vm.assistants, key = { it.id }) { assistant ->
                AssistantCard(
                    assistant = assistant,
                    selected = assistant.id == vm.activeAssistantId,
                    canDelete = assistant.id != com.littlebit.chat.data.Assistants.GENERAL_ID,
                    onSelect = { vm.selectAssistant(assistant.id) },
                    onEdit = { editing = assistant },
                    onDelete = { vm.deleteAssistant(assistant.id) },
                )
            }
            item(key = "create-assistant") {
                TextButton(onClick = { editing = Assistant(UUID.randomUUID().toString(), "", "") }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Create Assistant")
                }
            }
            item(key = "notes-title") {
                Spacer(Modifier.height(6.dp))
                Text("Pinned context notes", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "notes-hint") {
                Text(
                    "Global notes apply to every chat; per-chat notes only to the current one. Enabled notes are added to the system context.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(vm.notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    chatTitle = vm.currentSession?.title,
                    onToggle = { vm.toggleNote(note.id, it) },
                    onEdit = { editingNote = note },
                    onDelete = { vm.deleteNote(note.id) },
                )
            }
            item(key = "create-note") {
                TextButton(onClick = {
                    editingNote = ContextNote(UUID.randomUUID().toString(), "", "", enabled = true, chatId = null)
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Add pinned note")
                }
            }
            item(key = "footer") { Spacer(Modifier.height(20.dp)) }
        }
    }

    editing?.let { assistant ->
        AssistantEditor(
            assistant = assistant,
            onDismiss = { editing = null },
            onSave = { vm.saveAssistant(it); editing = null },
        )
    }
    editingNote?.let { note ->
        NoteEditor(
            note = note,
            currentChatId = vm.currentSession?.id,
            onDismiss = { editingNote = null },
            onSave = { vm.saveNote(it); editingNote = null },
        )
    }
}

@Composable
private fun AssistantCard(
    assistant: Assistant,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(assistant.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        assistant.systemPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) BadgeChip("Active", MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onSelect, enabled = !selected) { Text(if (selected) "Selected" else "Use") }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: ContextNote,
    chatTitle: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(note.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        note.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (note.isGlobal) "Global" else "This chat" + (chatTitle?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = note.enabled, onCheckedChange = onToggle)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AssistantEditor(assistant: Assistant, onDismiss: () -> Unit, onSave: (Assistant) -> Unit) {
    var name by remember(assistant.id) { mutableStateOf(assistant.name) }
    var prompt by remember(assistant.id) { mutableStateOf(assistant.systemPrompt) }
    var customTemperature by remember(assistant.id) { mutableStateOf(assistant.temperature != null) }
    var temperature by remember(assistant.id) { mutableStateOf(assistant.temperature?.toFloat() ?: 0.7f) }
    var customTokens by remember(assistant.id) { mutableStateOf(assistant.maxTokens != null) }
    var maxTokens by remember(assistant.id) { mutableStateOf((assistant.maxTokens ?: 1024).toFloat()) }
    var think by remember(assistant.id) { mutableStateOf(assistant.preferThinkMode) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(if (assistant.name.isBlank()) "New assistant" else "Edit assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Reasoning mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = think == null, onClick = { think = null }, label = { Text("Model default") })
                    FilterChip(selected = think == true, onClick = { think = true }, label = { Text("Think") })
                    FilterChip(selected = think == false, onClick = { think = false }, label = { Text("Fast") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom temperature", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = customTemperature, onCheckedChange = { customTemperature = it })
                }
                if (customTemperature) {
                    LittleBitSlider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 39,
                        display = "%.2f".format(temperature),
                        contentDescription = "Temperature",
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Limit output tokens", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = customTokens, onCheckedChange = { customTokens = it })
                }
                if (customTokens) {
                    LittleBitSlider(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        valueRange = 128f..4096f,
                        steps = 30,
                        display = maxTokens.toInt().toString(),
                        contentDescription = "Output token limit",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    onSave(
                        assistant.copy(
                            name = name.trim().take(60),
                            systemPrompt = prompt.trim(),
                            temperature = if (customTemperature) temperature.toDouble() else null,
                            maxTokens = if (customTokens) maxTokens.toInt() else null,
                            preferThinkMode = think,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoteEditor(
    note: ContextNote,
    currentChatId: String?,
    onDismiss: () -> Unit,
    onSave: (ContextNote) -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var text by remember(note.id) { mutableStateOf(note.text) }
    var global by remember(note.id) { mutableStateOf(note.chatId == null) }
    var enabled by remember(note.id) { mutableStateOf(note.enabled) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(if (note.text.isBlank()) "New pinned note" else "Edit pinned note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Context") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = global, onClick = { global = true }, label = { Text("Global") })
                    FilterChip(
                        selected = !global,
                        enabled = currentChatId != null,
                        onClick = { global = false },
                        label = { Text("This chat") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onSave(
                        note.copy(
                            title = title.trim().take(80),
                            text = text.trim().take(4000),
                            enabled = enabled,
                            chatId = if (global) null else currentChatId,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
