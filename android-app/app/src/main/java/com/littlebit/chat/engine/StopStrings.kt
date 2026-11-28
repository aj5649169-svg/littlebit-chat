package com.littlebit.chat.engine

/**
 * Stop-string enforcement for streaming text.
 *
 * llama.cpp at `6aee508` has no stop-string sampler in its public API, so the
 * filtering happens on the text stream. A stop string can be split across two
 * decoded pieces (`"</"` + `"s>"`), so the filter holds back the last
 * `longest - 1` characters and only releases text that cannot be the prefix of
 * a stop string.
 *
 * This class is the reference implementation. `llama_jni.cpp`'s `StopFilter`
 * mirrors it byte-for-byte for the on-device path, and the cloud streaming path
 * uses this one directly. Pure Kotlin: fully JVM-testable.
 */
class StopFilter(stops: List<String>) {

    /** Non-empty, de-duplicated, longest first so the earliest/longest match wins. */
    private val stops: List<String> = stops
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }

    private val holdback = StringBuilder()

    /** True once a stop string has been seen; nothing further is emitted. */
    var matched: Boolean = false
        private set

    /** The stop string that fired, for logging/telemetry. */
    var matchedOn: String? = null
        private set

    private val keep: Int = (stops.maxOfOrNull { it.length } ?: 0) - 1

    /**
     * Feed one decoded piece and return the text that is safe to display now.
     * May be empty while a potential stop string is still incomplete.
     */
    fun feed(piece: String): String {
        if (matched) return ""
        if (stops.isEmpty()) return piece
        holdback.append(piece)
        findStop()?.let { (at, stop) ->
            matched = true
            matchedOn = stop
            val out = holdback.substring(0, at)
            holdback.setLength(0)
            return out
        }
        if (holdback.length <= keep) return ""
        val cut = holdback.length - keep
        val out = holdback.substring(0, cut)
        holdback.delete(0, cut)
        return out
    }

    /** Flush whatever is still held back when generation ends without a match. */
    fun finish(): String {
        if (matched) return ""
        val out = holdback.toString()
        holdback.setLength(0)
        return out
    }

    private fun findStop(): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (stop in stops) {
            val at = holdback.indexOf(stop)
            if (at < 0) continue
            val current = best
            if (current == null || at < current.first) best = at to stop
        }
        return best
    }
}
