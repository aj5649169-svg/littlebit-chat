package com.littlebit.chat.studio

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Every Image Studio input that must outlive the composable.
 *
 * These values used to be `remember`ed inside [ImageStudio], so navigating back
 * to Chat during an active run discarded the prompt (and the staged slots, the
 * photo and the mask). Holding them on the ViewModel means the back gesture is
 * a pure navigation event: the render keeps running and returning to the screen
 * shows the same inputs plus whatever progress the engine has reached.
 *
 * Compose snapshot state, not a data class, so every field stays individually
 * observable exactly like the previous `remember`-ed values.
 */
class StudioState {
    var prompt by mutableStateOf("")
    var negative by mutableStateOf("")
    var seed by mutableStateOf("-1")
    var steps by mutableIntStateOf(20)
    var strength by mutableFloatStateOf(.75f)
    var brush by mutableFloatStateOf(.04f)
    var shape by mutableStateOf(512 to 512)
    var qwen by mutableStateOf(false)

    /** Which slot the next document pick targets. */
    var slot by mutableIntStateOf(0)

    /** The four model slots (base, LLM, VAE, vision) exactly as staged. */
    val slots = mutableStateListOf<Weight?>(null, null, null, null)

    var source by mutableStateOf<Bitmap?>(null)
    val strokes = mutableStateListOf<List<Dab>>()

    /** Staged TAESD decoder, if any. */
    var taesd by mutableStateOf<File?>(null)
    var taesdResolved by mutableStateOf(false)

    /** Phase-aware progress text from [ImageProgress]; empty when idle. */
    var progress by mutableStateOf("")

    /** True between pressing Cancel and the native call returning. */
    var cancelling by mutableStateOf(false)

    /** True once a photo or mask exists, for the inpainting requirement. */
    val hasPhoto: Boolean get() = source != null

    /** True once at least one brush stroke has been painted. */
    val hasMask: Boolean get() = strokes.isNotEmpty()
}
