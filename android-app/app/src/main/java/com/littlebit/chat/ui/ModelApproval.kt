package com.littlebit.chat.ui

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.littlebit.chat.data.GgufHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun freeMemory(): Long {
    val context = LocalContext.current
    var available by remember { mutableLongStateOf(0L) }
    LaunchedEffect(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            available = ActivityManager.MemoryInfo().also(am::getMemoryInfo).availMem
            kotlinx.coroutines.delay(4000)
        }
    }
    return available
}

@Composable
fun FitLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = when (label) {
            "High OOM risk", "May OOM" -> MaterialTheme.colorScheme.error
            "Fits well", "Great fit" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
}

@Composable
private fun Summary(h: GgufHeader, ctx: Int) {
    Text("${h.arch} · ${h.quant} · ${h.layers} layers · ${h.context} context")
    FitLabel(h.fit(ctx, freeMemory()))
}

@Composable
fun HeaderSummary(vm: ChatViewModel, source: String) {
    val context = LocalContext.current
    val header by produceState<GgufHeader?>(null, source) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (source.startsWith("content://")) context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use {
                vm.inspectFile(File("/proc/self/fd/${it.fd}"))
            } else vm.inspectFile(File(source))
        }.getOrNull() }
    }
    header?.let { Summary(it, vm.settings.contextSize) }
}

@Composable
fun ModelApproval(vm: ChatViewModel) {
    val item = vm.approval ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickModel) }
    AlertDialog(onDismissRequest = vm::declineModel, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (item.copy) "Make a private copy?" else "Load directly") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.name)
            Text(if (item.bytes >= 0) "%.3f GB (%,d bytes)".format(item.bytes / 1e9, item.bytes) else "Size unavailable from this provider")
            if (item.copy) Text("This provider cannot offer a seekable file. Copying uses additional device storage.")
            else Text("Direct · 0 B copied")
            item.header?.let { Summary(it, vm.settings.contextSize) }
        } }, confirmButton = { TextButton(onClick = vm::approveModel) { Text(if (item.copy) "Copy and load" else "Load") } },
        dismissButton = { TextButton(onClick = { vm.declineModel(); picker.launch(arrayOf("*/*")) }) { Text("Pick another") } })
}
