package com.littlebit.chat

import com.littlebit.chat.engine.SamplerConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val kotlinDecode = sourceBlock(
            samplerConfigSource,
            "fun fromDoubleArray(values: DoubleArray): SamplerConfig {",
            "private fun Double.finiteOr",
        )
        assertTrue(kotlinDecode.contains("require(values.size == SLOT_COUNT)"))

        val nativeRead = sourceBlock(
            nativeSource,
            "bool readSamplerOpts(",
            "// Canonical chain:",
        )
        val nativeBuild = sourceBlock(
            nativeSource,
            "llama_sampler * buildSampler(",
            "// Sampling loop shared",
        )
        val kotlinRequire = kotlinDecode.indexOf("require(values.size == SLOT_COUNT)")
        val nativeLengthCheck = nativeRead.indexOf("env->GetArrayLength(sampler) != kSamplerSlotCount")
        val nativeArrayAccess = nativeRead.indexOf("DoubleArrayView view")
        val nativeReadCall = nativeBuild.indexOf("readSamplerOpts(env, sampler, o)")
        val nativeChainInit = nativeBuild.indexOf("llama_sampler_chain_init(sparams)")
        assertTrue(kotlinRequire >= 0)
        assertTrue(nativeLengthCheck >= 0)
        assertTrue(nativeLengthCheck < nativeArrayAccess)
        assertTrue(nativeReadCall >= 0)
        assertTrue(nativeReadCall < nativeChainInit)
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

    @Test fun explicitMaximumSeedSelectsForcedDistXtcAndMirostatPaths() {
        val buildSampler = sourceBlock(
            nativeSource,
            "llama_sampler * buildSampler(",
            "// Sampling loop shared",
        )
        val seedBlock = buildSampler
            .substringAfter("const bool locked_seed")
            .substringBefore("if (!o.mirostat_enabled && o.xtc_probability > 0.0)")
        assertTrue(seedBlock.contains("force_explicit_seed = locked_seed && useed == LLAMA_DEFAULT_SEED"))

        val xtcBlock = sourceBlock(
            buildSampler,
            "if (!o.mirostat_enabled && o.xtc_probability > 0.0) {",
            "if (o.dynatemp_range > 0.0)",
        )
        val forcedXtc = normalizedParameters(xtcBlock)
            .substringAfter("force_explicit_seed ?")
            .substringBefore(": llama_sampler_init_xtc")
        assertTrue(forcedXtc.contains("initForcedSeedSampler("))
        assertTrue(forcedXtc.contains("ForcedSamplerKind::Xtc"))

        val mirostatChoice = sourceBlock(
            buildSampler,
            "if (o.mirostat_enabled) {",
            "} catch (...) {",
        )
        val normalizedMirostatChoice = normalizedParameters(mirostatChoice)
        val elseIndex = normalizedMirostatChoice.indexOf("} else {")
        assertTrue(elseIndex > 0)
        val forcedMirostat = normalizedMirostatChoice
            .substring(0, elseIndex)
            .substringAfter("force_explicit_seed ?")
            .substringBefore(": llama_sampler_init_mirostat_v2")
        assertTrue(forcedMirostat.contains("initForcedSeedSampler("))
        assertTrue(forcedMirostat.contains("ForcedSamplerKind::MirostatV2"))

        val forcedDist = normalizedMirostatChoice
            .substring(elseIndex)
            .substringAfter("force_explicit_seed ?")
            .substringBefore(": llama_sampler_init_dist")
        assertTrue(forcedDist.contains("initForcedSeedSampler("))
        assertTrue(forcedDist.contains("ForcedSamplerKind::Dist"))
    }

    @Test fun forcedSeedSamplersMirrorPinnedUpstreamBehavior() {
        val forcedApply = sourceBlock(
            nativeSource,
            "void forcedSamplerApply(",
            "void forcedSamplerReset(",
        )
        val dist = sourceBlock(
            forcedApply,
            "if (ctx->kind == ForcedSamplerKind::Dist) {",
            "if (ctx->kind == ForcedSamplerKind::Xtc) {",
        )
        val normalizedDist = normalizedParameters(dist)
        assertTrue(normalizedDist.contains("if (cur_p->size == 1)"))
        assertTrue(normalizedDist.contains("double sum_cum = 0.0"))
        assertTrue(normalizedDist.contains("const double sum_tgt = sum_cum * rnd;"))
        assertTrue(normalizedDist.contains("cur_p->data[i].p /= sum_cum;"))
        assertFalse(normalizedDist.contains("forcedSamplerSoftmax(cur_p, false)"))

        val xtc = sourceBlock(
            forcedApply,
            "if (ctx->kind == ForcedSamplerKind::Xtc) {",
            "forcedSamplerSoftmax(cur_p, true);",
        )
        val normalizedXtc = normalizedParameters(xtc)
        val earlyReturn = normalizedXtc.indexOf("cur_p->size < 2")
        val rngDraw = normalizedXtc.indexOf("distribution(ctx->rng)")
        assertTrue(earlyReturn >= 0)
        assertTrue(rngDraw > earlyReturn)

        val forcedClone = sourceBlock(
            nativeSource,
            "llama_sampler * forcedSamplerClone(const llama_sampler * smpl) {",
            "void forcedSamplerFree(",
        )
        val forcedInit = sourceBlock(
            nativeSource,
            "llama_sampler * initForcedSeedSampler(ForcedSamplerKind kind, uint32_t seed,",
            "bool readSamplerOpts(",
        )
        for (block in listOf(forcedClone, forcedInit)) {
            val uniqueContext = block.indexOf("std::make_unique<ForcedSeedSampler>")
            val samplerInit = block.indexOf("llama_sampler_init(")
            val ownershipRelease = block.indexOf(".release()")
            assertTrue(uniqueContext >= 0)
            assertTrue(samplerInit > uniqueContext)
            assertTrue(ownershipRelease > samplerInit)
        }
    }

    private fun normalizedParameters(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().removeSuffix(",")

    private fun sourceBlock(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("Missing source marker: $start", startIndex >= 0)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("Missing source marker after $start: $end", endIndex > startIndex)
        return source.substring(startIndex + start.length, endIndex)
    }

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
