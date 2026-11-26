package com.littlebit.chat.studio

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.littlebit.chat.engine.ModelPath
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

internal fun align64(value: Int) = ((value / 64.0).roundToInt() * 64).coerceIn(64, 1024)
internal data class Weight(val uri: Uri, val name: String, val bytes: Long)
internal fun validWeight(name: String) = name.substringAfterLast('.').lowercase() in setOf("gguf","safetensors")
internal fun inspectWeight(resolver: ContentResolver, uri: Uri): Weight {
    val name = resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty()
    require(validWeight(name)) { "Select GGUF or SafeTensors weights. Pickle checkpoints are unsupported." }
    return requireNotNull(resolver.openFileDescriptor(uri,"r")).use { fd ->
        val length = RandomAccessFile(ModelPath.fromDescriptor(fd.fd), "r").use { file ->
            val n = file.length()
            require(n >= 12)
            if (name.endsWith(".gguf",true)) require(file.readInt() == 0x47475546) { "Invalid GGUF header" }
            else {
                val bytes = java.lang.Long.reverseBytes(file.readLong())
                require(bytes in 2..minOf(100_000_000L,n-8) && file.read() == 123) { "Invalid SafeTensors header" }
            }
            n
        }
        Weight(uri,name,length)
    }
}

internal data class Dab(val x: Float, val y: Float, val radius: Float)
internal fun mask(width: Int, height: Int, strokes: List<List<Dab>>): ByteArray {
    val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    for (stroke in strokes) for ((i,dab) in stroke.withIndex()) {
        val r = dab.radius * width
        canvas.drawCircle(dab.x*width,dab.y*height,r,paint)
        if (i > 0) {
            paint.strokeWidth = r*2
            canvas.drawLine(stroke[i-1].x*width,stroke[i-1].y*height,dab.x*width,dab.y*height,paint)
        }
    }
    val pixels = IntArray(width*height)
    bitmap.getPixels(pixels,0,width,0,0,width,height)
    bitmap.recycle()
    return ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
}

internal fun photo(resolver: ContentResolver, uri: Uri): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) }
    require(opts.outWidth > 0 && opts.outHeight > 0) { "Cannot read this photo" }
    opts.inSampleSize = 1
    while (maxOf(opts.outWidth,opts.outHeight)/opts.inSampleSize > 2048) opts.inSampleSize *= 2
    opts.inJustDecodeBounds = false
    return requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) })
}

internal fun generate(resolver: ContentResolver, slots: List<Weight?>, input: Bitmap?, strokes: List<List<Dab>>,
                      text: String, negative: String, width: Int, height: Int, steps: Int, strength: Float, seed: Long): Bitmap {
    val qwen = slots[1] != null
    require(slots[0] != null && (!qwen || slots[2] != null && (input == null || slots[3] != null)))
    val mask = if (!qwen && input != null) mask(width,height,strokes).also { require(it.any { b -> b != 0.toByte() }) { "Paint the area to edit." } } else null
    val inputPixels = input?.let {
        val resized = Bitmap.createScaledBitmap(it,width,height,true)
        IntArray(width*height).also { a -> resized.getPixels(a,0,width,0,0,width,height); if(resized !== input) resized.recycle() }
    }
    val rgb = inputPixels?.let { p -> ByteArray(p.size*3) { i -> (p[i/3] ushr (16-8*(i%3))).toByte() } }
    // stable-diffusion.cpp receives each weight as /proc/self/fd/<N>. Keep every
    // ParcelFileDescriptor open until render() returns: closing a provider
    // descriptor early makes the native open()/mmap() fail on Android 11+ FUSE.
    val fds = mutableListOf<ParcelFileDescriptor>()
    val out = try {
        val files = slots.map { w -> w?.let {
            val fd = requireNotNull(resolver.openFileDescriptor(it.uri,"r")); fds.add(fd)
            ModelPath.fromDescriptor(fd.fd)
        }.orEmpty() }.toTypedArray()
        SdNative.render(files,text,negative,width,height,steps,strength,seed,
            qwen && slots.first()!!.name.contains("turbo",true),rgb,mask)
    } finally { fds.forEach { it.close() } }
    val image = Bitmap.createBitmap(IntArray(width*height) { i ->
        android.graphics.Color.rgb(out[i*3].toInt() and 255,out[i*3+1].toInt() and 255,out[i*3+2].toInt() and 255)
    },width,height,Bitmap.Config.ARGB_8888)
    if(input == null || mask == null) return image
    val layer = image.copy(Bitmap.Config.ARGB_8888,true)
    val pixels = IntArray(width*height)
    layer.getPixels(pixels,0,width,0,0,width,height)
    for(i in pixels.indices) pixels[i] = (pixels[i] and 0xffffff) or ((mask[i].toInt() and 255) shl 24)
    layer.setPixels(pixels,0,width,0,0,width,height)
    return input.copy(Bitmap.Config.ARGB_8888,true).also { dst ->
        android.graphics.Canvas(dst).drawBitmap(layer,null,android.graphics.Rect(0,0,dst.width,dst.height),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        image.recycle(); layer.recycle()
    }
}
