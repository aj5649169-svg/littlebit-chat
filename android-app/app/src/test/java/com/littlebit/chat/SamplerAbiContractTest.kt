package com.littlebit.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks that keep the Kotlin and native sampler contracts synchronized. */
class SamplerAbiContractTest {

    private val repoRoot: File by lazy { findRepoRoot() }

    private val samplerConfigSource: String by lazy {
        readSource("android-app/app/src/main/java/com/littlebit/chat/engine/SamplerConfig.kt")
    }

    private val llamaJniSource: String by lazy {
        readSource("android-app/app/src/main/java/com/littlebit/chat/engine/LlamaJni.kt")
    }

    private val nativeSource: String by lazy {
        readSource("android-app/app/src/main/cpp/llama_jni.cpp")
    }

    @Test fun kotlinAndNativeSlotsMatch() {
        val kotlinSlots = Regex(
            """const\s+val\s+IDX_([A-Z0-9_]+)\s*=\s*(\d+)""",
        ).findAll(samplerConfigSource).associate {
            it.groupValues[1] to it.groupValues[2].toInt()
        }
        val nativeEnum = nativeSource
            .substringAfter("enum SamplerSlot {")
            .substringBefore("};")
        val nativeSlots = Regex(
            """SAMPLER_([A-Z0-9_]+)\s*=\s*(\d+)""",
        ).findAll(nativeEnum).associate {
            it.groupValues[1] to it.groupValues[2].toInt()
        }

        assertEquals(SamplerConfig.SLOT_COUNT, kotlinSlots.size)
        assertEquals(SamplerConfig.SLOT_COUNT, nativeSlots.size)
        assertEquals(kotlinSlots, nativeSlots)
        assertEquals((0 until SamplerConfig.SLOT_COUNT).toSet(), kotlinSlots.values.toSet())
    }

    @Test fun bothSidesRequireExactlyTwentyTwoSlots() {
        assertTrue(samplerConfigSource.contains("require(values.size == SLOT_COUNT)"))
        assertTrue(nativeSource.contains("constexpr jsize kSamplerSlotCount = 22"))
        assertTrue(nativeSource.contains("env->GetArrayLength(sampler) != kSamplerSlotCount"))
    }

    @Test fun jniSignaturesMatchKotlinDeclarations() {
        val generateKotlin = normalizedParameters(
            llamaJniSource.substringAfter("external fun nativeGenerate(").substringBefore("): Int"),
        )
        assertEquals(
            "handle: Long, prompt: String, sampler: DoubleArray, maxTokens: Int, " +
                "thinkingBudget: Int, stopStrings: Array<String>, callback: LlamaEngine.TokenCallback",
            generateKotlin,
        )

        val generateNative = normalizedParameters(
            nativeSource
                .substringAfter("Java_com_littlebit_chat_engine_LlamaJni_nativeGenerate(")
                .substringBefore(") {"),
        )
        assertTrue(
            generateNative.contains(
                "jlong handle, jstring jprompt, jdoubleArray sampler, jint max_tokens, " +
                    "jint budget, jobjectArray jstopStrings, jobject jcallback",
            ),
        )

        val continueKotlin = normalizedParameters(
            llamaJniSource.substringAfter("external fun nativeContinueGenerate(").substringBefore("): Int"),
        )
        assertEquals(
            "handle: Long, sampler: DoubleArray, maxTokens: Int, thinkingBudget: Int, " +
                "stopStrings: Array<String>, callback: LlamaEngine.TokenCallback",
            continueKotlin,
        )

        val continueNative = normalizedParameters(
            nativeSource
                .substringAfter("Java_com_littlebit_chat_engine_LlamaJni_nativeContinueGenerate(")
                .substringBefore(") {"),
        )
        assertTrue(
            continueNative.contains(
                "jlong handle, jdoubleArray sampler, jint max_tokens, jint budget, " +
                    "jobjectArray jstopStrings, jobject jcallback",
            ),
        )
    }

    @Test fun nativeChainUsesCanonicalSourceOrder() {
        val buildSampler = nativeSource
            .substringAfter("llama_sampler * buildSampler(")
            .substringBefore("// Sampling loop shared")

        val calls = listOf(
            "llama_sampler_init_penalties(",
            "llama_sampler_init_dry(",
            "llama_sampler_init_top_k(",
            "llama_sampler_init_typical(",
            "llama_sampler_init_top_n_sigma(",
            "llama_sampler_init_top_p(",
            "llama_sampler_init_min_p(",
            "llama_sampler_init_xtc(",
            "llama_sampler_init_temp_ext(",
            "llama_sampler_init_temp(",
            "llama_sampler_init_mirostat_v2(",
            "llama_sampler_init_dist(",
        )

        var previous = -1
        for (call in calls) {
            val index = buildSampler.indexOf(call)
            assertTrue("Missing native sampler call: $call", index >= 0)
            assertTrue("Native sampler call is out of canonical order: $call", index > previous)
            previous = index
        }
    }

    @Test fun nativeMirostatModeExcludesConflictingSamplers() {
        val buildSampler = nativeSource
            .substringAfter("llama_sampler * buildSampler(")
            .substringBefore("// Sampling loop shared")
        val exclusionBlock = buildSampler
            .substringAfter("if (!o.mirostat_enabled) {")
            .substringBefore("const uint32_t useed")

        for (call in listOf(
            "llama_sampler_init_typical(",
            "llama_sampler_init_top_n_sigma(",
            "llama_sampler_init_top_p(",
            "llama_sampler_init_min_p(",
        )) {
            assertTrue("Mirostat exclusion is missing: $call", exclusionBlock.contains(call))
        }
        assertTrue(
            buildSampler.contains("if (!o.mirostat_enabled && o.xtc_probability > 0.0)"),
        )
    }

    @Test fun explicitMaximumSeedHasAForcedSeedPath() {
        assertTrue(nativeSource.contains("initForcedSeedSampler"))
        assertTrue(nativeSource.contains("locked_seed && useed == LLAMA_DEFAULT_SEED"))
    }

    private fun normalizedParameters(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private fun readSource(relativePath: String): String =
        File(repoRoot, relativePath).readText()

    private fun findRepoRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (
                File(
                    current,
                    "android-app/app/src/main/java/com/littlebit/chat/engine/SamplerConfig.kt",
                ).isFile
            ) {
                return current
            }
            current = current.parentFile ?: throw AssertionError(
                "Could not locate the LittleBit Chat repository root",
            )
        }
    }
}
