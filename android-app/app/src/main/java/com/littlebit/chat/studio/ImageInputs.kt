package com.littlebit.chat.studio

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

internal fun align64(value: Int) = ((value / 64.0).roundToInt() * 64).coerceIn(64, 1024)

/**
 * A picked image/VAE/text-encoder weight. [file] is the app-private staged
 * copy handed to stable-diffusion.cpp; null means "needs staging".
 */
internal data class Weight(val uri: Uri, val name: String, val bytes: Long, val file: File? = null) {
    val staged: Boolean get() = file != null
}

internal fun validWeight(name: String) = name.substringAfterLast('.').lowercase() in setOf("gguf", "safetensors")

/**
 * Validates a SAF document without ever opening `/proc/self/fd/<N>` by path.
 * Headers are read through `contentResolver.openInputStream(uri)`, and the
 * size comes from the provider metadata (falling back to `statSize` on the
 * descriptor, which does not reopen it by name).
 */
internal fun inspectWeight(resolver: ContentResolver, uri: Uri): Weight {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty()
    require(name.isNotBlank()) { "Could not read the file name." }
    require(validWeight(name)) {
        "Select GGUF or SafeTensors weights. Pickle checkpoints (.ckpt) are unsupported."
    }
    val reported = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
    } ?: -1L
    val bytes = if (reported > 0L) reported else {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }
    require(bytes > 0L) { "The selected file has no readable size." }

    resolver.openInputStream(uri)?.use { raw ->
        val input = BufferedInputStream(raw, 64 * 1024)
        if (name.endsWith(".gguf", true)) {
            require(readIntLe(input) == 0x46554747) { "Invalid GGUF header" }
            require(readIntLe(input) in 2..3) { "Unsupported GGUF version" }
        } else {
            val headerLength = readLongLe(input)
            require(headerLength in 2L..minOf(100_000_000L, bytes - 8L)) { "Invalid SafeTensors header" }
            require(input.read() == '{'.code) { "Invalid SafeTensors header" }
        }
    } ?: error("The selected file could not be opened.")
    return Weight(uri, name, bytes)
}

private fun readByte(input: InputStream): Int {
    val value = input.read()
    require(value >= 0) { "Unexpected end of file" }
    return value
}

private fun readIntLe(input: InputStream): Int {
    var value = 0
    for (i in 0 until 4) value = value or (readByte(input) shl (8 * i))
    return value
}

private fun readLongLe(input: InputStream): Long {
    var value = 0L
    for (i in 0 until 8) value = value or (readByte(input).toLong() shl (8 * i))
    return value
}

internal data class Dab(val x: Float, val y: Float, val radius: Float)

internal fun mask(width: Int, height: Int, strokes: List<List<Dab>>): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        strokeCap = android.graphics.Paint.Cap.ROUND
        style = android.graphics.Paint.Style.STROKE
    }
    for (stroke in strokes) {
        for ((i, dab) in stroke.withIndex()) {
            paint.strokeWidth = 2 * dab.radius * width
            canvas.drawCircle(dab.x * width, dab.y * height, dab.radius * width, paint)
            if (i > 0) {
                canvas.drawLine(stroke[i - 1].x * width, stroke[i - 1].y * height, dab.x * width, dab.y * height, paint)
            }
        }
    }
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    return ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
}

internal fun photo(resolver: ContentResolver, uri: Uri): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    require(opts.outWidth > 0 && opts.outHeight > 0) { "Cannot read this photo" }
    opts.inSampleSize = 1
    while (maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2048) opts.inSampleSize *= 2
    opts.inJustDecodeBounds = false
    return requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) })
}

/**
 * Renders from staged absolute paths. stable-diffusion.cpp opens weights with
 * std::ifstream, which cannot read `/proc/self/fd/<N>` on Android 11–16 FUSE, so
 * Image Studio never passes descriptor paths here.
 */
internal fun generate(
    modelPaths: List<String?>,
    input: Bitmap?,
    strokes: List<List<Dab>>,
    text: String,
    negative: String,
    width: Int,
    height: Int,
    steps: Int,
    strength: Float,
    seed: Long,
): Bitmap {
    val files = Array(4) { "" }
    for (i in 0 until minOf(4, modelPaths.size)) modelPaths[i]?.let { files[i] = it }
    val qwen = files[1].isNotEmpty()
    require(files[0].isNotEmpty()) { "Stage an image model first." }
    require(!qwen || (files[2].isNotEmpty() && (input == null || files[3].isNotEmpty()))) {
        "Qwen Image needs a text encoder, a VAE and (for photo edits) a vision projector."
    }
    val mask = if (!qwen && input != null) {
        mask(width, height, strokes).also { bytes -> require(bytes.any { it != 0.toByte() }) { "Paint the area to edit." } }
    } else {
        null
    }
    val inputPixels = input?.let {
        val resized = Bitmap.createScaledBitmap(it, width, height, true)
        IntArray(width * height).also { array ->
            resized.getPixels(array, 0, width, 0, 0, width, height)
            if (resized !== input) resized.recycle()
        }
    }
    val rgb = inputPixels?.let { pixels -> ByteArray(pixels.size * 3) { i -> (pixels[i / 3] ushr (16 - 8 * (i % 3))).toByte() } }
    val out = SdNative.render(
        files,
        text,
        negative,
        width,
        height,
        steps,
        strength,
        seed,
        files[0].contains("turbo", true) || files[1].contains("turbo", true),
        rgb,
        mask,
    )
    val image = Bitmap.createBitmap(
        IntArray(width * height) { i ->
            android.graphics.Color.rgb(
                out[i * 3].toInt() and 255,
                out[i * 3 + 1].toInt() and 255,
                out[i * 3 + 2].toInt() and 255,
            )
        },
        width,
        height,
        Bitmap.Config.ARGB_8888,
    )
    if (input == null || mask == null) return image
    val layer = image.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(width * height)
    layer.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) pixels[i] = (pixels[i] and 0xffffff) or ((mask[i].toInt() and 255) shl 24)
    layer.setPixels(pixels, 0, width, 0, 0, width, height)
    return input.copy(Bitmap.Config.ARGB_8888, true).also { destination ->
        android.graphics.Canvas(destination).drawBitmap(
            layer,
            null,
            android.graphics.Rect(0, 0, destination.width, destination.height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        image.recycle()
        layer.recycle()
    }
}
