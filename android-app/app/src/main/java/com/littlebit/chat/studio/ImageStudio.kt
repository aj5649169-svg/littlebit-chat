package com.littlebit.chat.studio

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlebit.chat.ui.ChatViewModel
import com.littlebit.chat.ui.FitLabel
import com.littlebit.chat.ui.LittleBitSlider
import com.littlebit.chat.ui.freeMemory
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudio(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val slots = remember { mutableStateListOf<Weight?>(null, null, null, null) }
    var slot by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var qwen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("-1") }
    var steps by remember { mutableIntStateOf(20) }
    var strength by remember { mutableFloatStateOf(.75f) }
    var brush by remember { mutableFloatStateOf(.04f) }
    var shape by remember { mutableStateOf(512 to 512) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    val strokes = remember { mutableStateListOf<List<Dab>>() }
    var stage by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Weight?>(null) }
    var pendingSlot by remember { mutableIntStateOf(0) }
    var staging by remember { mutableStateOf(false) }
    var stageProgress by remember { mutableFloatStateOf(0f) }
    // Fast VAE (TAESD): optional staged decoder for 1-3 s VAE decoding.
    var taesd by remember { mutableStateOf(TaesdModel.find(ImageModelStaging.directory(context))) }
    var taesdError by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val index = slot
            runCatching { withContext(Dispatchers.IO) { inspectWeight(resolver, uri) } }
                .onSuccess { weight ->
                    error = ""
                    val existing = withContext(Dispatchers.IO) {
                        ImageModelStaging.findStaged(ImageModelStaging.directory(context), weight.name, weight.bytes)
                    }
                    if (existing != null) {
                        slots[index] = weight.copy(file = existing)
                        if (index == 0 && weight.name.contains("turbo", true)) steps = if (qwen) 8 else 4
                    } else {
                        pendingSlot = index
                        pending = weight
                    }
                }
                .onFailure { error = it.message.orEmpty() }
        }
    }
    val photoPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { photo(resolver, uri) } }
                .onSuccess {
                    source = it
                    strokes.clear()
                    val scale = 512.0 / maxOf(it.width, it.height)
                    shape = align64((it.width * scale).toInt()) to align64((it.height * scale).toInt())
                }
                .onFailure { error = it.message.orEmpty() }
        }
    }
    val taesdPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = resolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
                    val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    TaesdModel.validate(name, bytes)?.let { throw IllegalArgumentException(it) }
                    ImageModelStaging.stage(context, uri, TaesdModel.FILE_NAME, bytes)
                }
            }.onSuccess { file ->
                taesd = file
                vm.settings.taesdEnabled = true
                taesdError = ""
            }.onFailure { taesdError = it.message.orEmpty() }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = vm.studioResult
        if (uri != null && image != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }.onFailure { error = it.message.orEmpty() }
        }
    }

    DisposableEffect(Unit) { onDispose { if (vm.studioBusy) SdNative.cancel() } }
    LaunchedEffect(vm.studioBusy) {
        while (vm.studioBusy) {
            stage = SdNative.status()
            delay(250)
        }
    }

    pending?.let { weight ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { if (!staging) pending = null },
            title = { Text("Stage this model?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${weight.name} · ${ImageModelStaging.formatBytes(weight.bytes)}")
                    Text(
                        "stable-diffusion.cpp needs a real app-private path. The file is copied once into Image Studio storage and reused while the name and size still match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (staging) {
                        LinearProgressIndicator(
                            progress = { stageProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${ImageModelStaging.formatBytes((weight.bytes * stageProgress).toLong())} / ${ImageModelStaging.formatBytes(weight.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !staging, onClick = {
                    staging = true
                    stageProgress = 0f
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                ImageModelStaging.stage(
                                    context = context,
                                    uri = weight.uri,
                                    displayName = weight.name,
                                    bytes = weight.bytes,
                                    cancelled = { !staging },
                                    onProgress = { copied, total ->
                                        stageProgress = if (total > 0) copied.toFloat() / total else 0f
                                    },
                                )
                            }
                        }.onSuccess { file ->
                            slots[pendingSlot] = weight.copy(file = file)
                            if (pendingSlot == 0 && weight.name.contains("turbo", true)) steps = if (qwen) 8 else 4
                            pending = null
                            error = ""
                        }.onFailure {
                            if (it !is CancellationException) error = it.message.orEmpty()
                        }
                        staging = false
                        stageProgress = 0f
                    }
                }) { Text(if (staging) "Copying…" else "Stage ${ImageModelStaging.formatBytes(weight.bytes)}") }
            },
            dismissButton = {
                TextButton(onClick = { if (staging) staging = false else pending = null }) {
                    Text(if (staging) "Cancel copy" else "Cancel")
                }
            },
        )
    }

    val free = freeMemory()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Image Studio") }, navigationIcon = {
                TextButton(onClick = onBack) { Text("Back") }
            })
        },
    ) { padding ->
        Column(
            // Padding sits inside the scroll container so the top staged-model
            // card starts below the TopAppBar and never clips under it.
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                listOf(false to "SD 1.5 / Turbo", true to "Qwen Image").forEach { (mode, label) ->
                    FilterChip(
                        selected = mode == qwen,
                        enabled = !vm.busy,
                        onClick = {
                            qwen = mode
                            for (i in slots.indices) slots[i] = null
                            steps = if (mode) 8 else 20
                        },
                        label = { Text(label) },
                    )
                }
            }
            for (i in 0 until if (qwen) 4 else 1) {
                val label = listOf("Image model", "Text encoder", "VAE", "Vision projector (photo edits)")[i]
                val weight = slots[i]
                Card(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    weight?.name ?: "Not selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (weight != null) {
                                    Text(
                                        (if (weight.staged) "Staged · " else "Needs staging · ") +
                                            ImageModelStaging.formatBytes(weight.bytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (weight.staged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                enabled = !vm.busy && !staging,
                                onClick = {
                                    slot = i
                                    picker.launch(arrayOf("*/*"))
                                },
                            ) { Text(if (weight == null) "Select" else "Replace") }
                        }
                        if (weight != null && weight.file != null) {
                            TextButton(
                                enabled = !vm.busy,
                                onClick = {
                                    ImageModelStaging.delete(requireNotNull(weight.file))
                                    slots[i] = weight.copy(file = null)
                                    error = "Staged copy deleted."
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.padding(3.dp))
                                Text("Delete staged copy")
                            }
                        }
                    }
                }
            }
            Card(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fast VAE (TAESD)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                taesd?.let { "Staged · ${ImageModelStaging.formatBytes(it.length())}" }
                                    ?: "Not staged · ${TaesdModel.DISPLAY_SIZE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (taesd != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = vm.settings.taesdEnabled,
                            onCheckedChange = { vm.settings.taesdEnabled = it; taesdError = "" },
                        )
                    }
                    Text(
                        "taesd.safetensors (madebyollin/taesd, MIT, ~9.8 MB) cuts VAE decoding to " +
                            "about 1–3 s with lower RAM. Off, or without a staged file, Image Studio " +
                            "decodes with the tiled full VAE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (vm.settings.taesdEnabled && taesd == null) {
                        Text(
                            "No taesd.safetensors staged — falling back to tiled full VAE.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !vm.busy, onClick = { taesdPick.launch(arrayOf("*/*")) }) {
                            Text(if (taesd == null) "Select taesd.safetensors" else "Replace")
                        }
                        TextButton(enabled = !vm.busy, onClick = {
                            // Same ACTION_VIEW hand-off as Model Hub: the browser
                            // downloads it, so the offline build still declares no
                            // INTERNET permission.
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TaesdModel.URL)))
                            }.onFailure {
                                taesdError = "No browser app available. Download taesd.safetensors elsewhere and select it here."
                            }
                        }) { Text("Get taesd.safetensors (${TaesdModel.DISPLAY_SIZE})") }
                        if (taesd != null) {
                            TextButton(enabled = !vm.busy, onClick = {
                                ImageModelStaging.delete(requireNotNull(taesd))
                                taesd = null
                                vm.settings.taesdEnabled = false
                            }) { Text("Delete") }
                        }
                    }
                    if (taesdError.isNotEmpty()) {
                        Text(
                            taesdError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            val peak = (slots.filterNotNull().maxOfOrNull { it.bytes } ?: 0L) + 1024L * 1024 * 1024
            if (slots[0] != null) {
                FitLabel(
                    when {
                        peak > free -> "High OOM risk"
                        peak > free * .70 -> "Tight — lower resolution"
                        else -> "Fits well"
                    },
                )
            }
            Row {
                TextButton(enabled = !vm.busy, onClick = { photoPick.launch(arrayOf("image/*")) }) { Text("Choose photo") }
                if (source != null) {
                    TextButton(enabled = !vm.busy, onClick = { source = null; strokes.clear() }) { Text("Remove") }
                }
            }
            source?.let { image ->
                Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
                    Image(image.asImageBitmap(), "Input photo", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    if (!qwen) {
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(brush, vm.busy) {
                                if (!vm.busy) {
                                    detectDragGestures(
                                        onDragStart = { p -> strokes.add(listOf(Dab(p.x / size.width, p.y / size.height, brush))) },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            if (strokes.isNotEmpty()) {
                                                strokes[strokes.lastIndex] = strokes.last() +
                                                    Dab(change.position.x / size.width, change.position.y / size.height, brush)
                                            }
                                        },
                                    )
                                }
                            },
                        ) {
                            for (stroke in strokes) {
                                for ((i, p) in stroke.withIndex()) {
                                    val center = Offset(p.x * size.width, p.y * size.height)
                                    val red = Color.Red.copy(alpha = .5f)
                                    drawCircle(red, p.radius * size.width, center)
                                    if (i > 0) {
                                        drawLine(
                                            red,
                                            Offset(stroke[i - 1].x * size.width, stroke[i - 1].y * size.height),
                                            center,
                                            2 * p.radius * size.width,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!qwen) {
                    Text("Brush size")
                    LittleBitSlider(
                        value = brush,
                        onValueChange = { brush = it },
                        valueRange = .005f..0.14f,
                        display = "%.3f".format(brush),
                        contentDescription = "Brush size",
                    )
                    Row {
                        TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
                        TextButton(onClick = strokes::clear) { Text("Clear mask") }
                    }
                }
            }
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Describe the image or edit") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(negative, { negative = it }, label = { Text("Negative prompt (optional)") }, modifier = Modifier.fillMaxWidth())
            if (source == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (s in listOf(512 to 512, 512 to 768, 768 to 512, 1024 to 1024)) {
                        FilterChip(shape == s, { shape = s }, { Text("${s.first}×${s.second}") })
                    }
                }
            }
            Text("Steps · $steps")
            LittleBitSlider(
                value = steps.toFloat(),
                onValueChange = { steps = it.roundToInt() },
                valueRange = 1f..40f,
                steps = 38,
                display = steps.toString(),
                contentDescription = "Steps",
            )
            ImageStepHint.forModel(slots[0]?.name.orEmpty(), steps)?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Denoise · %.2f".format(strength))
            LittleBitSlider(
                value = strength,
                onValueChange = { strength = it },
                valueRange = 0f..1f,
                display = "%.2f".format(strength),
                contentDescription = "Denoise strength",
            )
            OutlinedTextField(seed, { seed = it }, label = { Text("Seed (−1 for random)") }, singleLine = true)
            vm.studioNote?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (vm.studioBusy) {
                Text(stage)
                Button(onClick = vm::cancelImage) { Text("Cancel") }
            } else {
                val ready = slots[0]?.file != null &&
                    (!qwen || (slots[1]?.file != null && slots[2]?.file != null && (source == null || slots[3]?.file != null)))
                Button(
                    enabled = !vm.busy && ready && prompt.isNotBlank() && seed.toLongOrNull() != null,
                    onClick = {
                        val paths = List(4) { slots.getOrNull(it)?.file?.absolutePath }
                        val input = source
                        val mask = strokes.toList()
                        val p = prompt
                        val n = negative
                        val (w, h) = shape
                        val count = steps
                        val denoise = strength
                        val number = seed.toLongOrNull() ?: -1L
                        // Only pass TAESD when the toggle is on and a staged copy exists;
                        // otherwise the native side keeps the tiled full VAE.
                        val taesdPath = taesd?.absolutePath?.takeIf { vm.settings.taesdEnabled }
                        vm.renderImage {
                            generate(paths, input, mask, p, n, w, h, count, denoise, number, taesdPath)
                        }
                    },
                ) { Text(if (vm.isLoaded) "Unload chat model & generate" else "Generate") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            vm.studioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.studioResult?.let { image ->
                Image(
                    image.asImageBitmap(),
                    "Generated image",
                    Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height),
                )
                Row {
                    TextButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT < 29) {
                            saveAs.launch("LittleBit.png")
                        } else {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val values = ContentValues().apply {
                                            put("display_name", "LittleBit-${System.currentTimeMillis()}.png")
                                            put("mime_type", "image/png")
                                            put("is_pending", 1)
                                        }
                                        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                                        try {
                                            requireNotNull(resolver.openOutputStream(uri)).use {
                                                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                                            }
                                            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
                                        } catch (e: Exception) {
                                            resolver.delete(uri, null, null)
                                            throw e
                                        }
                                    }
                                }.onSuccess { error = "Saved to Photos" }.onFailure { error = it.message.orEmpty() }
                            }
                        }
                    }) { Text("Save") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val uri = withContext(Dispatchers.IO) {
                                    val file = File(context.cacheDir, "exports/LittleBit.png")
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".exports", file)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("image/png")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Share image",
                                    ),
                                )
                            }.onFailure { error = it.message.orEmpty() }
                        }
                    }) { Text("Share") }
                }
            }
        }
    }
}
