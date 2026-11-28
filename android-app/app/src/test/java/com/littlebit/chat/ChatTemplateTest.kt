package com.littlebit.chat

import com.littlebit.chat.engine.ChatTemplate
import org.junit.Assert.*
import org.junit.Test

class ChatTemplateTest {

    @Test fun modelDefaultThrows() {
        assertFailsWith<UnsupportedOperationException> {
            ChatTemplate.MODEL_DEFAULT.render("sys", emptyList())
        }
    }

    @Test fun byIdFallsBackToModelDefault() {
        assertSame(ChatTemplate.MODEL_DEFAULT, ChatTemplate.byId(null))
        assertSame(ChatTemplate.MODEL_DEFAULT, ChatTemplate.byId(""))
        assertSame(ChatTemplate.MODEL_DEFAULT, ChatTemplate.byId("nope"))
    }

    @Test fun suggestedForArchitecture() {
        assertSame(ChatTemplate.LLAMA3, ChatTemplate.suggestedFor("llama"))
        assertSame(ChatTemplate.LLAMA3, ChatTemplate.suggestedFor("Llama-3"))
        assertSame(ChatTemplate.CHATML, ChatTemplate.suggestedFor("qwen"))
        assertSame(ChatTemplate.GEMMA, ChatTemplate.suggestedFor("gemma"))
        assertSame(ChatTemplate.MISTRAL, ChatTemplate.suggestedFor("mistral"))
        assertSame(ChatTemplate.MISTRAL, ChatTemplate.suggestedFor("mixtral"))
        assertSame(ChatTemplate.CHATML, ChatTemplate.suggestedFor("phi"))
        assertSame(ChatTemplate.MODEL_DEFAULT, ChatTemplate.suggestedFor("unknown"))
        assertSame(ChatTemplate.MODEL_DEFAULT, ChatTemplate.suggestedFor(null))
    }

    @Test fun renderChatML() {
        val out = ChatTemplate.CHATML.render("sys", listOf(
            ChatTemplate.Turn("user", "hi"),
            ChatTemplate.Turn("assistant", "hello"),
        ))
        assertEquals(
            "<|im_start|>system\nsys<|im_end|>\n" +
            "<|im_start|>user\nhi<|im_end|>\n" +
            "<|im_start|>assistant\nhello<|im_end|>\n" +
            "<|im_start|>assistant\n",
            out,
        )
    }

    @Test fun renderLlama3() {
        val out = ChatTemplate.LLAMA3.render("sys", listOf(
            ChatTemplate.Turn("user", "hi"),
            ChatTemplate.Turn("assistant", "hello"),
        ))
        assertEquals(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nsys<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\nhi<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\nhello<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n",
            out,
        )
    }

    @Test fun renderMistral() {
        val out = ChatTemplate.MISTRAL.render("sys", listOf(
            ChatTemplate.Turn("user", "hi"),
            ChatTemplate.Turn("assistant", "hello"),
        ))
        assertEquals("INST sys\n\nhi /INST hello</s>INST ", out)
    }

    @Test fun renderMistralEmptyHistory() {
        assertEquals("INST ", ChatTemplate.MISTRAL.render("sys", emptyList()))
    }

    @Test fun renderGemma() {
        val out = ChatTemplate.GEMMA.render("sys", listOf(
            ChatTemplate.Turn("user", "hi"),
            ChatTemplate.Turn("assistant", "hello"),
        ))
        assertEquals(
            "<start_of_turn>user\nsys\n\nhi<end_of_turn>\n" +
            "<start_of_turn>model\nhello<end_of_turn>\n" +
            "<start_of_turn>model\n",
            out,
        )
    }

    @Test fun renderAlpaca() {
        val out = ChatTemplate.ALPACA.render("sys", listOf(ChatTemplate.Turn("user", "hi")))
        assertEquals("### System:\nsys\n\n### Instruction:\nhi\n\n### Response:\n", out)
    }

    @Test fun renderVicuna() {
        val out = ChatTemplate.VICUNA.render("sys", listOf(ChatTemplate.Turn("user", "hi")))
        assertEquals("sys\n\nUSER: hi\nASSISTANT:", out)
    }

    @Test fun renderCustomSubstitutesPlaceholders() {
        val out = ChatTemplate.CUSTOM.render("SYS", listOf(
            ChatTemplate.Turn("user", "prev"),
            ChatTemplate.Turn("assistant", "ok"),
            ChatTemplate.Turn("user", "hi"),
        ), "{{system}}\n{{history}}USER: {{prompt}}\nASSISTANT:")
        assertTrue(out.contains("SYS"))
        assertTrue(out.contains("user: prev\n"))
        assertTrue(out.contains("assistant: ok\n"))
        assertTrue(out.contains("USER: hi\nASSISTANT:"))
    }

    @Test fun renderCustomFallsBackToDefaultAndAppendsPrompt() {
        val out = ChatTemplate.CUSTOM.render("SYS", listOf(ChatTemplate.Turn("user", "hi")), "")
        assertTrue(out.contains("SYS"))
        assertTrue(out.endsWith("hi"))
    }
}
