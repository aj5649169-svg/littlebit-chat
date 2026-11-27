package com.littlebit.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.RamBudgetEstimator
import com.littlebit.chat.engine.BenchmarkPreset

/**
 * Pre-load RAM guard. Shown when [RamBudgetEstimator] rates the load TIGHT or
 * DANGER, before any native allocation can push the phone into kswapd thrashing.
 */
@Composable
fun RamGuardDialog(vm: ChatViewModel) {
    val warning = vm.ramWarning ?: return
    val budget = warning.budget
    AlertDialog(
        onDismissRequest = vm::dismissRamWarning,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (budget.risk == RamBudgetEstimator.Risk.DANGER) "Not enough free RAM" else "Tight on RAM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(budget.summary(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    warning.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (budget.risk == RamBudgetEstimator.Risk.DANGER)
                        "Loading this model at ${vm.settings.contextSize} tokens can freeze the phone. 2K context or CPU avoids most of the peak."
                    else "The phone may start swapping. 2K context keeps the same model lighter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = vm::loadAtSafeContext) { Text("Load at 2K Context (Recommended)") }
        },
        dismissButton = {
            Row {
                if (vm.settings.vulkanOffload) TextButton(onClick = vm::loadOnCpu) { Text("Load on CPU") }
                TextButton(onClick = vm::loadDespiteRamWarning) { Text("Load Anyway") }
            }
        },
    )
}

/** Pre-run setup for the benchmark: backend, size preset and a RAM warning banner. */
@Composable
fun BenchmarkSetupDialog(
    vm: ChatViewModel,
    totalRam: Long,
    availRam: Long,
    onDismiss: () -> Unit,
) {
    var backend by remember { mutableStateOf("Current") }
    var preset by remember { mutableStateOf(BenchmarkPreset.QUICK) }
    // Less than 30 % of RAM free is where a full-size run starts swapping.
    val tight = totalRam > 0 && availRam < totalRam * 3 / 10
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Benchmark setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    (vm.modelName ?: "No model loaded") + " · " +
                        "${RamBudgetEstimator.gb(availRam)} free / ${RamBudgetEstimator.gb(totalRam)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow("Backend", listOf("Current", "CPU", "Vulkan"), backend) { backend = it }
                ChoiceRow("Size", BenchmarkPreset.entries.map { it.label }, preset.label) {
                    preset = BenchmarkPreset.byLabel(it)
                }
                if (tight) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Low free RAM — a Stress run may throttle or kill the app. Quick is safer.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Text(
                    "Runs a fixed prompt on a scratch context. The loaded chat stays untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = vm.isLoaded && !vm.benchmarking,
                onClick = {
                    onDismiss()
                    vm.benchmark(preset, backend)
                },
            ) { Text("Start Benchmark") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Compact single-choice chip row used by the setup dialogs. */
@Composable
fun ChoiceRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            for (option in options) {
                androidx.compose.material3.FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}
