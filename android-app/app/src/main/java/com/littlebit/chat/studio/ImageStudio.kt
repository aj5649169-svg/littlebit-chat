package com.littlebit.chat.studio

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.littlebit.chat.ui.ChatViewModel
import com.littlebit.chat.ui.FitLabel
import com.littlebit.chat.ui.freeMemory
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudio(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val slots = remember { mutableStateListOf<Weight?>(null,null,null,null) }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            val index = slot
            runCatching { withContext(Dispatchers.IO) { inspectWeight(resolver,uri) } }
                .onSuccess { slots[index] = it; error = ""; if(index == 0 && it.name.contains("turbo",true)) steps = if(qwen) 8 else 4 }
                .onFailure { error = it.message.orEmpty() }
        }
    }
    val photoPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { photo(resolver,uri) } }
                .onSuccess { source = it; strokes.clear()
                    val scale = 512.0/maxOf(it.width,it.height)
                    shape = align64((it.width*scale).toInt()) to align64((it.height*scale).toInt())
                }.onFailure { error = it.message.orEmpty() }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = vm.studioResult
        if(uri != null && image != null) scope.launch { runCatching { withContext(Dispatchers.IO) {
            requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } }.onFailure { error = it.message.orEmpty() } }
    }
    DisposableEffect(Unit) { onDispose { if(vm.studioBusy) SdNative.cancel() } }
    LaunchedEffect(vm.studioBusy) { while(vm.studioBusy) { stage = SdNative.status(); delay(250) } }
    val free = freeMemory()
    Scaffold(topBar = { TopAppBar(title = { Text("Image Studio") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                listOf(false to "SD 1.5 / Turbo",true to "Qwen Image").forEach { (mode,label) ->
                    FilterChip(selected = mode == qwen, enabled = !vm.busy, onClick = {
                        qwen = mode; for(i in slots.indices) slots[i] = null; steps = if(mode) 8 else 20
                    }, label = { Text(label) })
                }
            }
            for(i in 0 until if(qwen) 4 else 1) {
                val label = listOf("Image model","Text encoder","VAE","Vision projector (photo edits)")[i]
                OutlinedButton(onClick = { slot = i; picker.launch(arrayOf("*/*")) }, enabled = !vm.busy) {
                    Text(slots[i]?.name ?: "Select $label")
                }
            }
            val peak = (slots.filterNotNull().maxOfOrNull { it.bytes } ?: 0L) + 1024L*1024*1024
            if(slots[0] != null) FitLabel(when { peak > free -> "High OOM risk"; peak > free*.70 -> "Tight — lower resolution"; else -> "Fits well" })
            Row {
                TextButton(enabled = !vm.busy, onClick = { photoPick.launch(arrayOf("image/*")) }) { Text("Choose photo") }
                if(source != null) TextButton(enabled = !vm.busy,onClick = { source = null; strokes.clear() }) { Text("Remove") }
            }
            source?.let { image ->
                Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height)) {
                    Image(image.asImageBitmap(),"Input photo",Modifier.fillMaxSize(),contentScale = ContentScale.FillBounds)
                    if(!qwen) Canvas(Modifier.fillMaxSize().pointerInput(brush,vm.busy) {
                        if (!vm.busy) detectDragGestures(onDragStart = { p -> strokes.add(listOf(Dab(p.x/size.width,p.y/size.height,brush))) },
                            onDrag = { change,_ -> change.consume(); if(strokes.isNotEmpty()) strokes[strokes.lastIndex] =
                                strokes.last() + Dab(change.position.x/size.width,change.position.y/size.height,brush) })
                    }) {
                        for(stroke in strokes) for((i,p) in stroke.withIndex()) {
                            val center = Offset(p.x*size.width,p.y*size.height)
                            val red = Color.Red.copy(alpha=.5f)
                            drawCircle(red,p.radius*size.width,center)
                            if(i>0) drawLine(red,Offset(stroke[i-1].x*size.width,stroke[i-1].y*size.height),center,2*p.radius*size.width)
                        }
                    }
                }
                if(!qwen) {
                    Text("Brush size"); Slider(value=brush,onValueChange={brush=it},valueRange=.005f..0.14f)
                    Row { TextButton(onClick={if(strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)}) { Text("Undo") }
                        TextButton(onClick=strokes::clear) { Text("Clear mask") } }
                }
            }
            OutlinedTextField(prompt,{prompt=it},label={Text("Describe the image or edit")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(negative,{negative=it},label={Text("Negative prompt (optional)")},modifier=Modifier.fillMaxWidth())
            if(source == null) Row(Modifier.horizontalScroll(rememberScrollState())) {
                for(s in listOf(512 to 512,512 to 768,768 to 512,1024 to 1024)) FilterChip(shape==s,{shape=s},{Text("${s.first}×${s.second}")})
            }
            Text("Steps · $steps"); Slider(steps.toFloat(),{steps=it.roundToInt()},valueRange=1f..40f)
            Text("Denoise · %.2f".format(strength)); Slider(strength,{strength=it},valueRange=0f..1f)
            OutlinedTextField(seed,{seed=it},label={Text("Seed (−1 for random)")},singleLine=true)
            if(vm.studioBusy) { Text(stage); Button(onClick=SdNative::cancel) { Text("Cancel") } }
            else Button(enabled=!vm.busy && slots[0]!=null && prompt.isNotBlank() && seed.toLongOrNull()!=null &&
                (!qwen || slots[1]!=null && slots[2]!=null && (source==null || slots[3]!=null)),
                onClick={
                    val weights=slots.toList(); val input=source; val mask=strokes.toList(); val p=prompt; val n=negative
                    val (w,h)=shape; val count=steps; val denoise=strength; val number=seed.toLong()
                    vm.renderImage { generate(resolver,weights,input,mask,p,n,w,h,count,denoise,number) }
                }) { Text(if(vm.isLoaded) "Unload chat model & generate" else "Generate") }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            vm.studioError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            vm.studioResult?.let { image ->
                Image(image.asImageBitmap(),"Generated image",Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height))
                Row {
                    TextButton(onClick={
                        if (android.os.Build.VERSION.SDK_INT < 29) saveAs.launch("LittleBit.png")
                        else scope.launch { runCatching { withContext(Dispatchers.IO) {
                            val values=ContentValues().apply { put("display_name","LittleBit-${System.currentTimeMillis()}.png");put("mime_type","image/png");put("is_pending",1) }
                            val uri=requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
                            try { requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                                resolver.update(uri,ContentValues().apply { put("is_pending",0) },null,null)
                            } catch(e:Exception) { resolver.delete(uri,null,null); throw e }
                        } }.onSuccess { error="Saved to Photos" }.onFailure { error=it.message.orEmpty() } }
                    }) { Text("Save") }
                    TextButton(onClick={ scope.launch { runCatching {
                        val uri=withContext(Dispatchers.IO) {
                            val file=File(context.cacheDir,"exports/LittleBit.png");file.parentFile?.mkdirs()
                            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                            androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".exports",file)
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share image"))
                    }.onFailure { error=it.message.orEmpty() } } }) { Text("Share") }
                }
            }
        }
    }
}
