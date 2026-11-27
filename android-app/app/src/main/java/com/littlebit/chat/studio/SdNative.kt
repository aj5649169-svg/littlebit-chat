package com.littlebit.chat.studio

internal object SdNative {
    init { System.loadLibrary("sd_jni") }
    external fun prepare()
    external fun status(): String
    external fun cancel()
    external fun render(files: Array<String>, prompt: String, negative: String, width: Int, height: Int,
                        steps: Int, strength: Float, seed: Long, turbo: Boolean, photo: ByteArray?, mask: ByteArray?,
                        taesdPath: String?): ByteArray
}
