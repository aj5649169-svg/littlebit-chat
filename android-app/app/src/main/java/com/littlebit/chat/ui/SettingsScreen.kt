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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    var temperature by remember { mutableFloatStateOf(settings.temperature.toFloat()) }
    var repeatPenalty by remember { mutableFloatStateOf(settings.repeatPenalty.toFloat()) }
    var dry by remember { mutableFloatStateOf(settings.dryMultiplier.toFloat()) }
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
                            TextButton(
                                onClick = { crashFile.delete(); crashLog = null },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Dismiss and delete") }
                        }
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
                    subtitle = "Opt-in. Reuses the prompt cache between turns; only the two most recent chats are kept.",
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
                    temperature = .7f; dry = .8f; repeatPenalty = 1.18f
                    settings.temperature = .7; settings.dryMultiplier = .8; settings.repeatPenalty = 1.18
                    settings.batch = 512; settings.flash = true; settings.topK = 40; settings.topPercent = 90
                    settings.minPercent = 5; settings.maxTokens = 1024; reloadNeeded = true
                }) { Text("Reset to defaults") } }
                item(key = "temp") {
                    SliderRow(
                        label = "Temperature",
                        value = temperature,
                        range = 0f..2f,
                        steps = 39,
                        display = "%.2f".format(temperature),
                        onChange = { temperature = it },
                        onChangeFinished = { settings.temperature = temperature.toDouble() },
                    )
                }
                item(key = "rep") {
                    SliderRow(
                        label = "Repeat Penalty",
                        value = repeatPenalty,
                        range = 1f..2f,
                        steps = 49,
                        display = "%.2f".format(repeatPenalty),
                        onChange = { repeatPenalty = it },
                        onChangeFinished = { settings.repeatPenalty = repeatPenalty.toDouble() },
                    )
                }
                item(key = "dry") {
                    SliderRow(
                        label = "DRY Multiplier",
                        value = dry,
                        range = 0f..2f,
                        steps = 39,
                        display = "%.2f".format(dry),
                        onChange = { dry = it },
                        onChangeFinished = { settings.dryMultiplier = dry.toDouble() },
                    )
                }
                item { Choices("Prefill batch · reload model", listOf("256","512"), settings.batch.coerceAtMost(512).toString()) { settings.batch = it.toInt(); reloadNeeded = true } }
                item { SwitchRow("Vulkan Flash Attention", "Try Q8 KV cache on reload. CPU uses F16.", settings.flash) { settings.flash = it; reloadNeeded = true } }
                item { Choices("Top K", listOf("0","20","40","80"), settings.topK.toString()) { settings.topK = it.toInt() } }
                item { Choices("Top P (%)", listOf("80","90","95","100"), settings.topPercent.toString()) { settings.topPercent = it.toInt() } }
                item { Choices("Min P (%)", listOf("0","5","10"), settings.minPercent.toString()) { settings.minPercent = it.toInt() } }
                if (lastStage.isNotBlank()) item {
                    SettingPanel("Last inference stage") {
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(lastStage) }
                    }
                }
                item { Choices("Output token limit", com.littlebit.chat.engine.outputLimits.map { it.toString() }, settings.maxTokens.toString()) { settings.maxTokens = it.toInt() } }
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
            display = display,
            contentDescription = label,
            onValueChangeFinished = onChangeFinished,
        )
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
private fun Choices(title: String, choices: List<String>, selected: String, choose: (String) -> Unit) {
    val buzz = hapticAction()
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { item ->
                if (title == "Accent") {
                    val swatches = mapOf("Emerald" to 0xff31795e, "Ocean Blue" to 0xff376e9d,
                        "Violet" to 0xff7854a1, "Amber" to 0xff8a631a, "Rose" to 0xffa04d69)
                    androidx.compose.material3.IconButton(onClick = { buzz(); choose(item) },
                        modifier = Modifier.semantics { contentDescription = if (selected == item) "$item, selected" else item }) {
                        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape,
                            color = androidx.compose.ui.graphics.Color(swatches.getValue(item))) {
                            androidx.compose.foundation.layout.Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                if (selected == item) Text("✓", color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                } else androidx.compose.material3.FilterChip(shape = RoundedCornerShape(24.dp), selected = selected == item,
                    onClick = { buzz(); choose(item) }, label = { Text(item) })
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
