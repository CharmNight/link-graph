package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmJsonCodecTest {
    @Test
    fun parseObjectRejectsTrailingGarbageAndInvalidJson() {
        assertFailsWith<IllegalStateException> {
            LlmJsonCodec.parseObject("""{"ok":true} trailing""")
        }
        assertEquals(null, LlmJsonCodec.parseObjectOrNull("not json"))
    }

    @Test
    fun parseObjectHandlesExponentNumbersAndUnicode() {
        val root = LlmJsonCodec.parseObject("""{"value":1.2e3,"text":"\uD83D\uDE00"}""")

        assertEquals(1200.0, (root["value"] as Number).toDouble())
        assertEquals("😀", root["text"])
    }

    @Test
    fun parseObjectRejectsRawControlCharacters() {
        assertFailsWith<IllegalStateException> {
            LlmJsonCodec.parseObject("{\"bad\":\"\u0001\"}")
        }
    }

    @Test
    fun jsonObjectAccessorsPreserveNestedContent() {
        val root = LlmJsonCodec.parseJsonObject("""{"choices":[{"message":{"content":"hello"}}]}""")
        val choices = root.getAsJsonArray("choices")
        val first = choices[0].asJsonObject
        val message = first.getAsJsonObject("message")

        assertEquals("hello", message.get("content").asString)
    }

    @Test
    fun serializeObjectBuildsValidJson() {
        val payload = LlmJsonCodec.toJson(
            linkedMapOf(
                "model" to "gpt",
                "messages" to listOf(
                    linkedMapOf("role" to "user", "content" to "line\nnext"),
                ),
                "stream" to true,
            ),
        )

        val parsed = LlmJsonCodec.parseObject(payload)
        assertEquals("gpt", parsed["model"])
        assertTrue(parsed["messages"] is List<*>)
        assertEquals(true, parsed["stream"])
        assertFalse(payload.contains("line\nnext"))
    }
}
