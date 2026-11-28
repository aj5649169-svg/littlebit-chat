package com.littlebit.chat.engine

/**
 * Chat templates rendered in Kotlin instead of by llama.cpp's GGUF jinja
 * template.
 *
 * Why this exists: the GGUF template is whatever the model author shipped, it
 * cannot be inspected or fixed from the app, and a malformed one crashes turn 2
 * (`std::out_of_range` in the pinned llama.cpp). Rendering here gives the user a
 * deterministic, testable prompt and a custom escape hatch.
 *
 * [MODEL_DEFAULT] is the sentinel for "keep using the GGUF template" and is
 * never rendered in Kotlin — [render] throws for it so a missing branch fails
 * loudly instead of silently producing the wrong prompt.
 *
 * Pure Kotlin: no Android types, fully JVM-testable.
 */
enum class ChatTemplate(val id: String, val label: String) {
    MODEL_DEFAULT("model", "Model default"),
    CHATML("chatml", "ChatML"),
    LLAMA3("llama3", "Llama 3"),
    MISTRAL("mistral", "Mistral [INST]"),
    GEMMA("gemma", "Gemma"),
    ALPACA("alpaca", "Alpaca"),
    VICUNA("vicuna", "Vicuna"),
    CUSTOM("custom", "Custom");

    /** One rendered turn. Roles are llama.cpp's: "system", "user", "assistant". */
    data class Turn(val role: String, val content: String)

    val isModelDefault: Boolean get() = this == MODEL_DEFAULT

    /**
     * Render the prompt for one generation.
     *
     * @param system the effective system prompt (already includes notes/clock).
     * @param messages prior turns plus the new user turn, oldest first.
     * @param custom user-edited template, only read by [CUSTOM].
     */
    fun render(system: String, messages: List<Turn>, custom: String = ""): String = when (this) {
        MODEL_DEFAULT -> throw UnsupportedOperationException(
            "MODEL_DEFAULT is rendered by llama.cpp; branch on isModelDefault."
        )
        CHATML -> renderChatMl(system, messages)
        LLAMA3 -> renderLlama3(system, messages)
        MISTRAL -> renderMistral(system, messages)
        GEMMA -> renderGemma(system, messages)
        ALPACA -> renderAlpaca(system, messages)
        VICUNA -> renderVicuna(system, messages)
        CUSTOM -> renderCustom(system, messages, custom)
    }

    companion object {
        /** Unknown or blank ids fall back to the GGUF template. */
        fun byId(id: String?): ChatTemplate = entries.firstOrNull { it.id == id } ?: MODEL_DEFAULT

        /** Best-effort default for a newly loaded model; never changes an explicit choice. */
        fun suggestedFor(architecture: String?): ChatTemplate {
            val arch = architecture?.lowercase().orEmpty()
            return when {
                arch.startsWith("llama") -> LLAMA3
                arch.startsWith("qwen") -> CHATML
                arch.startsWith("gemma") -> GEMMA
                arch.startsWith("mistral") || arch.startsWith("mixtral") -> MISTRAL
                arch.startsWith("phi") || arch.startsWith("smollm") -> CHATML
                else -> MODEL_DEFAULT
            }
        }

        private const val IM_START = "<|im_start|>"
        private const val IM_END = "<|im_end|>"
        private const val BEGIN_OF_TEXT = "<|begin_of_text|>"
        private const val START_HEADER = "<|start_header_id|>"
        private const val END_HEADER = "<|end_header_id|>"
        private const val EOT_ID = "<|eot_id|>"
        private const val START_OF_TURN = "<start_of_turn>"
        private const val END_OF_TURN = "<end_of_turn>"

        private fun renderChatMl(system: String, messages: List<Turn>): String {
            val out = StringBuilder()
            if (system.isNotBlank()) out.append(IM_START).append("system\n").append(system).append(IM_END).append('\n')
            for (m in messages) {
                out.append(IM_START).append(m.role).append('\n').append(m.content).append(IM_END).append('\n')
            }
            out.append(IM_START).append("assistant\n")
            return out.toString()
        }

        private fun renderLlama3(system: String, messages: List<Turn>): String {
            val out = StringBuilder(BEGIN_OF_TEXT)
            if (system.isNotBlank()) {
                out.append(START_HEADER).append("system").append(END_HEADER).append("\n\n")
                    .append(system).append(EOT_ID)
            }
            for (m in messages) {
                out.append(START_HEADER).append(m.role).append(END_HEADER).append("\n\n")
                    .append(m.content).append(EOT_ID)
            }
            out.append(START_HEADER).append("assistant").append(END_HEADER).append("\n\n")
            return out.toString()
        }

        private fun renderMistral(system: String, messages: List<Turn>): String {
            val out = StringBuilder()
            var firstUser = true
            for (m in messages) {
                when (m.role) {
                    "assistant" -> out.append(' ').append(m.content).append("</s>")
                    else -> {
                        out.append("[INST] ")
                        if (firstUser && system.isNotBlank()) out.append(system).append("\n\n")
                        out.append(m.content).append(" [/INST]")
                        firstUser = false
                    }
                }
            }
            // A dangling assistant turn (or an empty history) still needs an opener.
            if (messages.lastOrNull()?.role == "assistant" || messages.isEmpty()) out.append("[INST] ")
            return out.toString()
        }

        private fun renderGemma(system: String, messages: List<Turn>): String {
            val out = StringBuilder()
            var firstUser = true
            for (m in messages) {
                when (m.role) {
                    "assistant" -> out.append(START_OF_TURN).append("model\n")
                        .append(m.content).append(END_OF_TURN).append('\n')
                    else -> {
                        out.append(START_OF_TURN).append("user\n")
                        if (firstUser && system.isNotBlank()) out.append(system).append("\n\n")
                        out.append(m.content).append(END_OF_TURN).append('\n')
                        firstUser = false
                    }
                }
            }
            out.append(START_OF_TURN).append("model\n")
            return out.toString()
        }

        private fun renderAlpaca(system: String, messages: List<Turn>): String {
            val out = StringBuilder()
            if (system.isNotBlank()) out.append("### System:\n").append(system).append("\n\n")
            val prior = messages.dropLast(1)
            if (prior.isNotEmpty()) {
                out.append("### Context:\n")
                for (m in prior) out.append(m.role).append(": ").append(m.content).append('\n')
                out.append('\n')
            }
            out.append("### Instruction:\n")
                .append(messages.lastOrNull()?.content.orEmpty())
                .append("\n\n### Response:\n")
            return out.toString()
        }

        private fun renderVicuna(system: String, messages: List<Turn>): String {
            val out = StringBuilder()
            if (system.isNotBlank()) out.append(system).append("\n\n")
            for (m in messages) {
                if (m.role == "assistant") out.append("ASSISTANT: ").append(m.content).append('\n')
                else out.append("USER: ").append(m.content).append('\n')
            }
            if (messages.lastOrNull()?.role != "assistant") out.append("ASSISTANT:")
            return out.toString()
        }

        /** Placeholders: `{{system}}`, `{{history}}`, `{{prompt}}` (the new user turn). */
        private fun renderCustom(system: String, messages: List<Turn>, custom: String): String {
            val prompt = messages.lastOrNull()?.content.orEmpty()
            val history = messages.dropLast(1).joinToString("") { "${it.role}: ${it.content}\n" }
            val template = custom.ifBlank { DEFAULT_CUSTOM }
            val hasPlaceholder = template.contains("{{system}}") ||
                template.contains("{{history}}") ||
                template.contains("{{prompt}}")
            val rendered = template
                .replace("{{system}}", system)
                .replace("{{history}}", history)
                .replace("{{prompt}}", prompt)
            return if (hasPlaceholder) rendered else rendered + prompt
        }

        /** A minimal, valid custom template; also what the settings field shows. */
        const val DEFAULT_CUSTOM = "{{system}}\n\n{{history}}USER: {{prompt}}\nASSISTANT:"
    }
}
