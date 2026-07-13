package com.charmnight.linkgraph.llm.tools

import kotlin.test.Test
import kotlin.test.assertTrue

class CodeReadToolFacadeTest {
    @Test
    fun boundsFallbackSnippetByUtf8Bytes() {
        val oversized = "汉".repeat(30_000)

        val result = requireNotNull(
            CodeReadToolFacade().readSourceSnippetRich(
                filePath = "src/main/java/com/example/Large.java",
                fallbackSnippet = oversized,
            ),
        )

        assertTrue(result.snippet.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        assertTrue(result.snippet.contains("片段已截断"))
        assertTrue(result.snippet.substringBefore("\n\n片段已截断").all { it == '汉' })
    }
}
