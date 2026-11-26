package com.littlebit.chat.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline read-aloud. The engine is created lazily on the first Speak tap and
 * all calls are serialized. A device without a TTS engine or a matching voice
 * gets a readable message, never a crash. [shutdown] is called from
 * `ChatViewModel.onCleared()`.
 */
class OfflineTtsController(context: Context) {

    private val appContext = context.applicationContext
    private val engine = AtomicReference<TextToSpeech?>(null)
    private val lock = Any()

    @Volatile private var ready = false
    @Volatile private var initFailed = false

    private var pending: Pair<String, List<String>>? = null

    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    fun speak(messageId: String, rawText: String) {
        val chunks = TtsTextSanitizer.chunks(TtsTextSanitizer.sanitize(rawText))
        if (chunks.isEmpty()) {
            _message.value = "There is nothing to read aloud in this reply."
            return
        }
        synchronized(lock) {
            val tts = engine.get() ?: createEngine()
            if (initFailed || tts == null) {
                _message.value = NO_VOICE
                return
            }
            if (!ready) {
                pending = messageId to chunks
                _speakingId.value = messageId
                return
            }
            start(tts, messageId, chunks)
        }
    }

    fun stop() {
        synchronized(lock) {
            pending = null
            _speakingId.value = null
            runCatching { engine.get()?.stop() }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            pending = null
            _speakingId.value = null
            ready = false
            val tts = engine.getAndSet(null)
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }

    private fun createEngine(): TextToSpeech? {
        initFailed = false
        val created = TextToSpeech(appContext) { status -> onInit(status) }
        engine.set(created)
        return created
    }

    private fun onInit(status: Int) {
        val tts = engine.get()
        if (status != TextToSpeech.SUCCESS || tts == null) {
            initFailed = true
            _speakingId.value = null
            _message.value = NO_VOICE
            return
        }
        val language = tts.setLanguage(Locale.getDefault())
        ready = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            _speakingId.value = null
            _message.value = "No offline voice matches this language. Install one in Android Settings → Text-to-speech."
            return
        }
        runCatching {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.contains(LAST_MARKER) == true) _speakingId.value = null
                }

                override fun onError(utteranceId: String?) {
                    _speakingId.value = null
                }
            })
        }
        val job = pending
        pending = null
        if (job != null) start(tts, job.first, job.second)
    }

    private fun start(tts: TextToSpeech, messageId: String, chunks: List<String>) {
        runCatching { tts.stop() }
        _message.value = null
        _speakingId.value = messageId
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        for ((index, chunk) in chunks.withIndex()) {
            val utterance = if (index == chunks.lastIndex) "$messageId$LAST_MARKER$index" else "$messageId:$index"
            val result = tts.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                utterance,
            )
            if (result != TextToSpeech.SUCCESS) {
                _speakingId.value = null
                _message.value = NO_VOICE
                return
            }
        }
    }

    private companion object {
        const val LAST_MARKER = ":last"
        const val NO_VOICE =
            "No offline text-to-speech voice is available. Install or enable one in Android Settings → Text-to-speech."
    }
}
