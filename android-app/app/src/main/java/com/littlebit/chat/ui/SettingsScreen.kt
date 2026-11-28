package com.littlebit.chat.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.SettingsStore
import com.littlebit.chat.engine.SamplerConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RamInfo(val total: Long, val avail: Long)

private fun queryRam(context: Context): RamInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return RamInfo(info.totalMem, info.availMem)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = vm.settings
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val crashFile = remember { File(context.filesDir, "last_crash.txt") }
    var crashLog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashLog = runCatching { crashFile.takeIf { it.isFile }?.readText() }
            .getOrNull()?.let(com.littlebit.chat.data.CrashLog::normalize)
    }

    var lastStage by remember { mutableStateOf("") }
    LaunchedEffect(advanced, vm.busy) {
        if (advanced) lastStage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { vm.diagnosticFile.readText() }.getOrDefault("")
        }
    }
    LaunchedEffect(settings.saveKvCache) { vm.refreshKvCacheSize() }
    var totalRam by remember { mutableLongStateOf(0L) }
    var availRam by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val ram = queryRam(context)
            totalRam = ram.total
            availRam = ram.avail
            delay(2000)
        }
    }

    var threads by remember { mutableFloatStateOf(settings.threads.toFloat()) }
    var ctxSize by remember { mutableFloatStateOf(settings.contextSize.toFloat()) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var vulkanEnabled by remember { mutableStateOf(settings.vulkanOffload) }
    var gpuLayers by remember { mutableFloatStateOf(settings.vulkanGpuLayers.toFloat()) }

    val contexts = listOf(1024, 2048, 3072, 4096, 6144, 8192, 16384)
    var reloadNeeded by remember(vm.modelLoadSuccessVersion) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "ram") { RamBanner(totalRam, availRam) }
            if (crashLog != null) {
                item(key = "last-crash-log") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Last crash log", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(crashLog.orEmpty(), modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 14, overflow = TextOverflow.Ellipsis)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("LittleBit crash log", crashLog.orEmpty()),
                                    )
                                }) { Text("Copy") }
                                TextButton(onClick = {
                                    val report = android.content.Intent(android.content.Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "LittleBit Chat crash log")
                                        .putExtra(android.content.Intent.EXTRA_TEXT, crashLog.orEmpty())
                                    context.startActivity(
                                        android.content.Intent.createChooser(report, "Report crash"),
                                    )
                                }) { Text("Report") }
                                TextButton(onClick = { crashFile.delete(); crashLog = null }) {
                                    Text("Dismiss and delete")
                                }
                            }
                        }
                    }
                }
            }
            item(key = "privacy") {
                SettingPanel("Privacy & permissions") {
                    val requested = remember {
                        runCatching {
                            context.packageManager
                                .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                                .requestedPermissions?.toList().orEmpty()
                        }.getOrDefault(emptyList())
                    }
                    val network = requested.filter {
                        it == android.Manifest.permission.INTERNET ||
                            it == android.Manifest.permission.ACCESS_NETWORK_STATE
                    }
                    Text(
                        if (network.isEmpty()) "Zero network permissions — verified on this install"
                        else "Network permission present (Standard edition): ${network.joinToString()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (network.isEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (requested.isEmpty()) "This build declares no runtime permissions at all."
                        else requested.joinToString("\n") { it.substringAfterLast('.') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "history-import") {
                var importStatus by remember { mutableStateOf<String?>(null) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val text = runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                                val limit = com.littlebit.chat.data.ChatImport.MAX_TEXT_CHARS + 1
                                val buf = CharArray(8192)
                                val out = StringBuilder()
                                while (out.length < limit) {
                                    val n = reader.read(buf, 0, minOf(buf.size, limit - out.length))
                                    if (n < 0) break
                                    out.append(buf, 0, n)
                                }
                                out.toString()
                            }
                        }.getOrNull()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            importStatus = if (text.isNullOrBlank()) {
                                "Could not read that file."
                            } else {
                                vm.importChat(text) ?: "Chat imported."
                            }
                        }
                    }
                }
                SettingPanel("Chat history") {
                    Text(
                        "Re-import a Chat JSON export. Imported chats appear in the drawer like any other chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Import chat JSON")
                    }
                    importStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { SettingPanel("Backend & Model") {
                    SliderRow(
                        label = "CPU Threads",
                        value = threads,
                        range = 2f..8f,
                        steps = 5,
                        display = threads.roundToInt().toString(),
                        onChange = { threads = it },
                        onChangeFinished = { vm.onThreadsChanged(threads.roundToInt()) },
                    )
                run {
                    SliderRow(
                        label = "Context Size",
                        value = contexts.indices.minBy { kotlin.math.abs(contexts[it] - ctxSize) }.toFloat(),
                        range = 0f..6f, steps = 5, display = ctxSize.roundToInt().toString(),
                        onChange = { ctxSize = contexts[it.roundToInt()].toFloat() },
                        onChangeFinished = { settings.contextSize = ctxSize.toInt(); reloadNeeded = true },
                    )
                    Choices("Quick context", listOf("2K","4K","8K","16K"), "${ctxSize.toInt()/1024}K") {
                        ctxSize = it.dropLast(1).toInt() * 1024f
                        settings.contextSize = ctxSize.toInt()
                        reloadNeeded = true
                    }
                }
                    SwitchRow(
                        title = "Vulkan GPU Offload",
                        subtitle = "Enabled by default. Reload after changing backend.",
                        checked = vulkanEnabled,
                        onCheckedChange = {
                            vulkanEnabled = it
                            settings.vulkanOffload = it
                            reloadNeeded = true
                        },
                    )
                if (vulkanEnabled) {
                    run {
                        Column {
                            SliderRow(
                                label = "GPU Layers (n_gpu_layers)",
                                value = gpuLayers,
                                range = 0f..99f,
                                steps = 98,
                                display = gpuLayers.roundToInt().toString(),
                                onChange = { gpuLayers = it },
                                onChangeFinished = {
                                    reloadNeeded = true
                                    settings.vulkanGpuLayers = gpuLayers.roundToInt()
                                    gpuLayers = settings.vulkanGpuLayers.toFloat()
                                },
                            )
                            Choices("Quick offload", listOf("CPU 0","Half","All 99"), when (gpuLayers.toInt()) {
                                0 -> "CPU 0"; 50 -> "Half"; 99 -> "All 99"; else -> ""
                            }) { gpuLayers = when(it) { "CPU 0" -> 0f; "Half" -> 50f; else -> 99f }
                                settings.vulkanGpuLayers = gpuLayers.toInt(); reloadNeeded = true }
                            Text(
                                "Reload model after changing GPU offload. On Mali GPUs, compare t/s against CPU KleidiAI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                SwitchRow(
                    title = "Save KV cache on disk",
                    subtitle = "On by default above 1K tokens. Reuses the prompt cache between turns; only the two most recent chats are kept.",
                    checked = settings.saveKvCache,
                ) { enabled ->
                    settings.saveKvCache = enabled
                    vm.invalidateDraft()
                    vm.refreshKvCacheSize()
                }
                if (settings.saveKvCache) {
                    TextButton(enabled = !vm.busy, onClick = vm::clearKvCache) {
                        Text("Clear KV cache (${com.littlebit.chat.engine.KvCacheStore.formatBytes(vm.kvCacheBytes)})")
                    }
                }
            } }
            if (vm.isLoaded && reloadNeeded) item { SettingPanel("Engine settings changed") {
                TextButton(enabled = !vm.busy, onClick = vm::reloadLastModel) { Text("Reload now") }
            } }
            item { SettingPanel("Background behavior") {
                Choices("Thinking budget", listOf("Off","256","512","1024","Unlimited"), settings.thinkingBudget) { settings.thinkingBudget = it; vm.invalidateDraft() }
                SwitchRow("Generation notification", "Show speed and Stop during replies", settings.notifyGeneration) { settings.notifyGeneration = it }
                Choices("Auto-unload in background", listOf("Off","After 1 min","Immediately"), settings.unloadPolicy) { settings.unloadPolicy = it }
                Choices("Unload idle model", listOf("Off","2 min","5 min","10 min"), settings.idleUnload) {
                    settings.idleUnload = it
                    vm.scheduleIdleUnload()
                }
                SwitchRow("Keep screen awake", "During generation", settings.keepAwake) { settings.keepAwake = it }
                SwitchRow(
                    title = "RAM safety warnings",
                    subtitle = "Warn before a load that may swap. Off bypasses the pre-load modal entirely.",
                    checked = settings.ramSafetyWarnings,
                ) { settings.ramSafetyWarnings = it }
            } }
            item { SettingPanel("Chat display") {
                SwitchRow(
                    title = "Show context usage bar",
                    subtitle = "Context used / max under the composer",
                    checked = settings.showContextBar,
                ) { settings.showContextBar = it }
            } }
            if (vm.cloudSupported) item { SettingPanel("Cloud API · Standard edition") { CloudSettings(vm) } }
            item { SettingPanel("Appearance") {
                    Choices("Theme", listOf("System","Dark","Light","AMOLED Pure Black"), settings.theme) { settings.theme = it }
                    Choices("Accent", listOf("Emerald","Ocean Blue","Violet","Amber","Rose"), settings.accent) { settings.accent = it }
                    Choices("Chat text", listOf("Compact 13sp","Normal 15sp","Large 17sp"),
                when(settings.font) { 13 -> "Compact 13sp"; 17 -> "Large 17sp"; else -> "Normal 15sp" }) {
                settings.font = it.substringAfter(' ').removeSuffix("sp").toInt()
            }
                    SwitchRow("Haptics", "Feedback on chat actions", settings.haptics) { settings.haptics = it }
                    Choices("Performance caption", listOf("Off", "Minimal", "Detailed"), settings.captionMode) { settings.captionMode = it }
            } }
            item { SettingPanel("Persona & Prompt") {
                val presets = mapOf("Concise" to SettingsStore.DEFAULT_SYSTEM_PROMPT,
                    "Coder" to "Write correct code. Explain choices briefly.",
                    "Study Tutor" to "Teach step by step, with examples and checks for understanding.",
                    "Creative" to "Tell vivid stories with original details and distinctive voices.")
                Choices("System persona", presets.keys.toList() + "Custom", settings.persona) {
                    settings.persona = it
                    systemPrompt = presets[it] ?: settings.customPrompt
                    settings.systemPrompt = systemPrompt
                    vm.invalidateDraft()
                }
                val templateLabels = com.littlebit.chat.engine.ChatTemplate.entries.associateBy { it.id }
                var templateId by remember { mutableStateOf(settings.chatTemplate) }
                var customTemplate by remember { mutableStateOf(settings.customTemplate) }
                LaunchedEffect(settings.chatTemplate, settings.customTemplate) {
                    templateId = settings.chatTemplate
                    customTemplate = settings.customTemplate
                }
                Choices("Chat template", templateLabels.keys.sorted().map { id ->
                    val t = templateLabels[id]!!
                    if (t.id == com.littlebit.chat.engine.ChatTemplate.MODEL_DEFAULT.id) "Model default" else t.label
                }, templateLabels.entries.firstOrNull { it.key == templateId }?.value?.let {
                    if (it.id == com.littlebit.chat.engine.ChatTemplate.MODEL_DEFAULT.id) "Model default" else it.label
                } ?: "Model default") { label ->
                    val id = templateLabels.entries.firstOrNull { it.value.label == label && it.value.id != com.littlebit.chat.engine.ChatTemplate.MODEL_DEFAULT.id }?.key
                        ?: com.littlebit.chat.engine.ChatTemplate.MODEL_DEFAULT.id
                    templateId = id
                    settings.chatTemplate = id
                    vm.invalidateDraft()
                }
                if (templateId == com.littlebit.chat.engine.ChatTemplate.CUSTOM.id) {
                    Column {
                        Text("Custom template", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customTemplate,
                            onValueChange = {
                                customTemplate = it
                                settings.customTemplate = it
                                settings.chatTemplate = com.littlebit.chat.engine.ChatTemplate.CUSTOM.id
                                vm.invalidateDraft()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("{{system}}, {{history}}, {{prompt}} placeholders") },
                        )
                    }
                }
                Column {
                    Text("System Prompt", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = {
                            systemPrompt = it
                            settings.persona = "Custom"
                            settings.customPrompt = it
                            settings.systemPrompt = it
                            vm.invalidateDraft()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }
            }
            if (vm.isLoaded || settings.benchmarkHistory != "[]") item {
                Card { Column(Modifier.padding(14.dp)) {
                    Text("Quick benchmark", style = MaterialTheme.typography.titleMedium)
                    Text("Runs a fixed Fast prompt on the loaded backend. Reload with the other backend to compare.",
                        style = MaterialTheme.typography.bodySmall)
                    var benchmarkSetup by remember { mutableStateOf(false) }
                    TextButton(enabled = vm.isLoaded && !vm.busy, onClick = { benchmarkSetup = true }) {
                        Text(if (vm.benchmarking) "Benchmark running…" else "Benchmark…")
                    }
                    if (vm.benchmarking) TextButton(onClick = vm::cancelBenchmark) { Text("Cancel benchmark") }
                    if (benchmarkSetup) BenchmarkSetupDialog(vm, totalRam, availRam) { benchmarkSetup = false }
                    TextButton(enabled = vm.isLoaded && !vm.busy, onClick = vm::compareBackends) { Text("Auto-pick fastest backend") }
                    vm.comparison?.let { (cpu,gpu) ->
                        Text("CPU %.1f t/s · ".format(cpu) + if (gpu > 0) "Vulkan %.1f t/s".format(gpu) else "Vulkan unavailable")
                        TextButton(enabled = !vm.busy, onClick = vm::applyWinner) { Text("Apply faster backend") }
                    }
                    val rows = org.json.JSONArray(settings.benchmarkHistory)
                    for (i in 0 until rows.length()) Text(rows.getString(i), modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall)
                } }
            }
            item(key = "features-header") {
                Text("Features", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "instant") {
                SwitchRow(
                    title = "Instant Reply (Background Pre-Warm)",
                    subtitle = "Drafts your reply while you type — shows a “Pre-warming…” dot in the chat bar.",
                    checked = vm.instantReply,
                    onCheckedChange = vm::updateInstantReply,
                )
            }
            item(key = "fastmode") {
                SwitchRow(
                    title = "Fast Mode by default",
                    subtitle = "Skip the reasoning chain (/no_think) — faster answers, less heat.",
                    checked = vm.fastMode,
                    onCheckedChange = vm::updateFastMode,
                )
            }
            item {
                androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) { TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Advanced Sampling & Engine  ▴" else "Advanced Sampling & Engine  ▾")
                }
                }
            }
            if (advanced) {
                item { TextButton(onClick = {
                    settings.batch = 512; settings.flash = true
                    settings.maxTokens = 1024; reloadNeeded = true
                }) { Text("Reset engine defaults") } }
                item(key = "sampler-suite") { SamplerSuite(settings) }
                item { Choices("Prefill batch · reload model", listOf("256","512"), settings.batch.coerceAtMost(512).toString()) { settings.batch = it.toInt(); reloadNeeded = true } }
                item { SwitchRow("Vulkan Flash Attention", "Try Q8 KV cache on reload. CPU uses F16.", settings.flash) { settings.flash = it; reloadNeeded = true } }
                if (lastStage.isNotBlank()) item {
                    SettingPanel("Last inference stage") {
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(lastStage) }
                    }
                }
                item { Choices("Output token limit", com.littlebit.chat.engine.outputLimits.map { it.toString() }, settings.maxTokens.toString()) { settings.maxTokens = it.toInt() } }
                item { SettingPanel("Stop strings") {
                    var stopText by remember { mutableStateOf(settings.stopStrings) }
                    OutlinedTextField(
                        value = stopText,
                        onValueChange = { stopText = it; settings.stopStrings = it },
                        label = { Text("One hard stop per line (max 8 · 64 chars each; enforced in the generation loop)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                } }
            }
            item(key = "footer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun RamBanner(total: Long, avail: Long) {
    Card(border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Device & Memory", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            if (total > 0) {
                Text(
                    "%.1f GB free of %.1f GB".format(avail / 1e9, total / 1e9),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { (avail.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                Text(
                    when {
                        total >= 12L * 1024 * 1024 * 1024 ->
                            "Compact, 8B spotlight and Large 2-bit tiers are all safe on this device."
                        total >= 8L * 1024 * 1024 * 1024 ->
                            "Compact and 8B spotlight tiers fit. Large 2-bit models fit with a 2K context."
                        else ->
                            "Stick to Compact models (0.6B–3B) — and close background apps before loading."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Reading memory info…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        LittleBitSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            display = display,
            contentDescription = label,
            onValueChangeFinished = onChangeFinished,
        )
    }
}

@Composable
private fun SamplerSuite(settings: SettingsStore) {
    var sampler by remember { mutableStateOf(settings.samplerConfig) }
    var seedText by remember { mutableStateOf(settings.samplerConfig.seed.toString()) }
    var showAdvanced by remember { mutableStateOf(false) }

    val parsedSeed = seedText.trim().toLongOrNull()
    val seedValid = parsedSeed == SamplerConfig.DEFAULT_SEED ||
        (parsedSeed != null && parsedSeed in 0L..SamplerConfig.MAX_SEED)

    fun persist(value: SamplerConfig) {
        val safe = value.clamped()
        sampler = safe
        settings.samplerConfig = safe
    }

    SettingPanel("Sampler suite") {
        Text(
            "These controls apply to local generation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderRow(
            label = "Temperature",
            value = sampler.temperature.toFloat(),
            range = 0f..2f,
            steps = 39,
            display = "%.2f".format(sampler.temperature),
            onChange = { sampler = sampler.copy(temperature = it.toDouble()) },
            onChangeFinished = { persist(sampler) },
        )
        SliderRow(
            label = "Repeat Penalty",
            value = sampler.repeatPenalty.toFloat(),
            range = 1f..2f,
            steps = 49,
            display = "%.2f".format(sampler.repeatPenalty),
            onChange = { sampler = sampler.copy(repeatPenalty = it.toDouble()) },
            onChangeFinished = { persist(sampler) },
        )
        SliderRow(
            label = "DRY Multiplier",
            value = sampler.dryMultiplier.toFloat(),
            range = 0f..2f,
            steps = 39,
            display = "%.2f".format(sampler.dryMultiplier),
            onChange = { sampler = sampler.copy(dryMultiplier = it.toDouble()) },
            onChangeFinished = { persist(sampler) },
        )
        val topKChoices = listOf("0", "20", "40", "80", "160", "320", "640", "1000")
        Choices(
            title = "Top K",
            choices = topKChoices,
            selected = sampler.topK.toString(),
        ) { value -> persist(sampler.copy(topK = value.toInt())) }
        Choices(
            title = "Top P (%)",
            choices = listOf("80", "90", "95", "100"),
            selected = (sampler.topP * 100.0).roundToInt().toString(),
            enabled = !sampler.mirostatEnabled,
        ) { value -> persist(sampler.copy(topP = value.toInt() / 100.0)) }
        Choices(
            title = "Min P (%)",
            choices = listOf("0", "5", "10"),
            selected = (sampler.minP * 100.0).roundToInt().toString(),
            enabled = !sampler.mirostatEnabled,
        ) { value -> persist(sampler.copy(minP = value.toInt() / 100.0)) }

        OutlinedTextField(
            value = seedText,
            onValueChange = { text ->
                seedText = text
                val seed = text.trim().toLongOrNull()
                if (seed != null &&
                    (seed == SamplerConfig.DEFAULT_SEED || seed in 0L..SamplerConfig.MAX_SEED)
                ) {
                    persist(sampler.copy(seed = seed))
                }
            },
            label = { Text("Seed lock (−1 = random)") },
            supportingText = {
                if (!seedValid) Text("Enter −1 or an integer from 0 to ${SamplerConfig.MAX_SEED}.")
            },
            isError = !seedValid,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide optional samplers" else "Show optional samplers")
        }

        if (showAdvanced) {
            SliderRow(
                label = "Penalty Window",
                value = sampler.penaltyLastN.toFloat(),
                range = 0f..512f,
                steps = 127,
                display = sampler.penaltyLastN.toString(),
                onChange = { sampler = sampler.copy(penaltyLastN = it.toInt()) },
                onChangeFinished = { persist(sampler) },
            )
            SliderRow(
                label = "Frequency Penalty",
                value = sampler.frequencyPenalty.toFloat(),
                range = -2f..2f,
                steps = 79,
                display = "%.2f".format(sampler.frequencyPenalty),
                onChange = { sampler = sampler.copy(frequencyPenalty = it.toDouble()) },
                onChangeFinished = { persist(sampler) },
            )
            SliderRow(
                label = "Presence Penalty",
                value = sampler.presencePenalty.toFloat(),
                range = -2f..2f,
                steps = 79,
                display = "%.2f".format(sampler.presencePenalty),
                onChange = { sampler = sampler.copy(presencePenalty = it.toDouble()) },
                onChangeFinished = { persist(sampler) },
            )
            SliderRow(
                label = "DRY Base",
                value = sampler.dryBase.toFloat(),
                range = 1f..4f,
                steps = 59,
                display = "%.2f".format(sampler.dryBase),
                onChange = { sampler = sampler.copy(dryBase = it.toDouble()) },
                onChangeFinished = { persist(sampler) },
            )
            SliderRow(
                label = "DRY Allowed Length",
                value = sampler.dryAllowedLength.toFloat(),
                range = 0f..128f,
                steps = 31,
                display = sampler.dryAllowedLength.toString(),
                onChange = { sampler = sampler.copy(dryAllowedLength = it.toInt()) },
                onChangeFinished = { persist(sampler) },
            )
            SliderRow(
                label = "DRY Window",
                value = sampler.dryPenaltyLastN.toFloat(),
                range = 0f..512f,
                steps = 127,
                display = sampler.dryPenaltyLastN.toString(),
                onChange = { sampler = sampler.copy(dryPenaltyLastN = it.toInt()) },
                onChangeFinished = { persist(sampler) },
            )

            val typicalEnabled = sampler.typicalP < 1.0
            SwitchRow(
                title = "Typical-p",
                subtitle = "Keep locally typical tokens before nucleus filtering.",
                checked = typicalEnabled,
                enabled = !sampler.mirostatEnabled,
            ) { enabled ->
                persist(sampler.copy(typicalP = if (enabled) 0.90 else 1.0))
            }
            if (typicalEnabled) {
                SliderRow(
                    label = "Typical-p",
                    value = sampler.typicalP.toFloat(),
                    range = 0f..1f,
                    steps = 19,
                    enabled = !sampler.mirostatEnabled,
                    display = "%.2f".format(sampler.typicalP),
                    onChange = { sampler = sampler.copy(typicalP = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
            }

            val topNSigmaEnabled = sampler.topNSigma > 0.0
            SwitchRow(
                title = "Top-N-Sigma",
                subtitle = "Discard logits far below the leading distribution.",
                checked = topNSigmaEnabled,
                enabled = !sampler.mirostatEnabled,
            ) { enabled ->
                persist(sampler.copy(topNSigma = if (enabled) 1.0 else 0.0))
            }
            if (topNSigmaEnabled) {
                SliderRow(
                    label = "Top-N-Sigma",
                    value = sampler.topNSigma.toFloat(),
                    range = 0f..100f,
                    steps = 99,
                    enabled = !sampler.mirostatEnabled,
                    display = "%.2f".format(sampler.topNSigma),
                    onChange = { sampler = sampler.copy(topNSigma = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
            }

            val xtcEnabled = sampler.xtcProbability > 0.0
            SwitchRow(
                title = "XTC",
                subtitle = "Remove likely tokens to counter repetition.",
                checked = xtcEnabled,
                enabled = !sampler.mirostatEnabled,
            ) { enabled ->
                persist(sampler.copy(xtcProbability = if (enabled) 0.20 else 0.0))
            }
            if (xtcEnabled) {
                SliderRow(
                    label = "XTC Probability",
                    value = sampler.xtcProbability.toFloat(),
                    range = 0f..1f,
                    steps = 19,
                    enabled = !sampler.mirostatEnabled,
                    display = "%.2f".format(sampler.xtcProbability),
                    onChange = { sampler = sampler.copy(xtcProbability = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
                SliderRow(
                    label = "XTC Threshold",
                    value = sampler.xtcThreshold.toFloat(),
                    range = 0f..0.5f,
                    steps = 9,
                    enabled = !sampler.mirostatEnabled,
                    display = "%.2f".format(sampler.xtcThreshold),
                    onChange = { sampler = sampler.copy(xtcThreshold = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
            }

            val dynatempEnabled = sampler.dynatempRange > 0.0
            SwitchRow(
                title = "Dynamic Temperature",
                subtitle = "Replaces fixed temperature scaling with entropy-aware scaling.",
                checked = dynatempEnabled,
            ) { enabled ->
                persist(sampler.copy(dynatempRange = if (enabled) 0.50 else 0.0))
            }
            if (dynatempEnabled) {
                SliderRow(
                    label = "Dynamic Temperature Range",
                    value = sampler.dynatempRange.toFloat(),
                    range = 0f..4f,
                    steps = 39,
                    display = "%.2f".format(sampler.dynatempRange),
                    onChange = { sampler = sampler.copy(dynatempRange = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
                SliderRow(
                    label = "Dynamic Temperature Exponent",
                    value = sampler.dynatempExponent.toFloat(),
                    range = 0.01f..4f,
                    steps = 398,
                    display = "%.2f".format(sampler.dynatempExponent),
                    onChange = { sampler = sampler.copy(dynatempExponent = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
            }

            SwitchRow(
                title = "Mirostat v2",
                subtitle = "Adaptive surprise targeting. Bypasses Typical, Top-N-Sigma, Top P, Min P, and XTC.",
                checked = sampler.mirostatEnabled,
            ) { enabled -> persist(sampler.copy(mirostatEnabled = enabled)) }
            if (sampler.mirostatEnabled) {
                SliderRow(
                    label = "Mirostat Tau",
                    value = sampler.mirostatTau.toFloat(),
                    range = 0.01f..10f,
                    steps = 998,
                    display = "%.2f".format(sampler.mirostatTau),
                    onChange = { sampler = sampler.copy(mirostatTau = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
                SliderRow(
                    label = "Mirostat Eta",
                    value = sampler.mirostatEta.toFloat(),
                    range = 0.001f..1f,
                    steps = 998,
                    display = "%.3f".format(sampler.mirostatEta),
                    onChange = { sampler = sampler.copy(mirostatEta = it.toDouble()) },
                    onChangeFinished = { persist(sampler) },
                )
            }
        }

        TextButton(onClick = {
            persist(SamplerConfig.DEFAULT)
            seedText = SamplerConfig.DEFAULT_SEED.toString()
        }) { Text("Reset samplers to defaults") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CloudSettings(vm: ChatViewModel) {
    val settings = vm.settings
    var preset by remember { mutableStateOf(com.littlebit.chat.net.CloudPreset.byLabel(settings.cloudPreset)) }
    var endpoint by remember { mutableStateOf(settings.cloudEndpoint) }
    var model by remember { mutableStateOf(settings.cloudModel) }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var keyVersion by remember { mutableIntStateOf(0) }
    val hasKey = remember(keyVersion) { vm.cloudHasKey() }
    val valid = remember(endpoint) { com.littlebit.chat.net.CloudEndpointValidator.isAllowed(endpoint) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in com.littlebit.chat.net.CloudPreset.entries) {
                androidx.compose.material3.FilterChip(
                    selected = preset == option,
                    onClick = {
                        vm.applyCloudPreset(option)
                        preset = option
                        endpoint = settings.cloudEndpoint
                        model = settings.cloudModel
                        status = null
                    },
                    label = { Text(option.label) },
                )
            }
        }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; settings.cloudEndpoint = it },
            label = { Text("Base URL") },
            singleLine = true,
            isError = !valid,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!valid) {
            Text(
                "Public hosts need https://. http:// is allowed only for localhost and private LAN addresses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; settings.cloudModel = it },
            label = { Text("Model ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !vm.cloudBusy && valid, onClick = vm::fetchCloudModels) {
                Text(if (vm.cloudBusy) "Fetching…" else "Fetch models")
            }
            if (hasKey) Text(
                "Key saved",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (vm.cloudModels.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in vm.cloudModels) {
                    androidx.compose.material3.FilterChip(
                        selected = model == id,
                        onClick = { model = id; settings.cloudModel = id },
                        label = { Text(id) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (hasKey) "Replace API key" else "API key") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(enabled = key.isNotBlank(), onClick = {
                status = vm.saveCloudKey(key)
                key = ""
                keyVersion++
            }) { Text("Save key") }
            TextButton(enabled = hasKey, onClick = {
                vm.clearCloudKey()
                status = "Key removed"
                keyVersion++
            }) { Text("Remove key") }
        }
        SwitchRow(
            title = "Use Cloud for new messages",
            subtitle = "Streams to the provider instead of the local engine. Off by default.",
            checked = vm.cloudMode,
        ) { vm.updateCloudMode(it) }
        Text(
            "Cloud mode sends the visible chat turns to this endpoint. Keys are encrypted with AndroidKeyStore and never logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("fail", true) || it.contains("error", true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val buzz = hapticAction()
    Card(border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = { buzz(); onCheckedChange(it) })
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Choices(
    title: String,
    choices: List<String>,
    selected: String,
    enabled: Boolean = true,
    choose: (String) -> Unit,
) {
    val buzz = hapticAction()
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { item ->
                if (title == "Accent") {
                    val swatches = mapOf("Emerald" to 0xff31795e, "Ocean Blue" to 0xff376e9d,
                        "Violet" to 0xff7854a1, "Amber" to 0xff8a631a, "Rose" to 0xffa04d69)
                    androidx.compose.material3.IconButton(enabled = enabled, onClick = { buzz(); choose(item) },
                        modifier = Modifier.semantics { contentDescription = if (selected == item) "$item, selected" else item }) {
                        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape,
                            color = androidx.compose.ui.graphics.Color(swatches.getValue(item))) {
                            androidx.compose.foundation.layout.Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                if (selected == item) Text("✓", color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                } else androidx.compose.material3.FilterChip(
                    shape = RoundedCornerShape(24.dp),
                    selected = selected == item,
                    enabled = enabled,
                    onClick = { buzz(); choose(item) },
                    label = { Text(item) },
                )
            }
        }
    }
}

@Composable
private fun SettingPanel(title: String, content: @Composable () -> Unit) {
    Card(border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
