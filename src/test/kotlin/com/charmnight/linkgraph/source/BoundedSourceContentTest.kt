package com.charmnight.linkgraph.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedSourceContentTest {
    @Test
    fun createTruncatesByUtf8BudgetAndRecordsDiagnostic() {
        val content = BoundedSourceContent.create(
            text = "汉".repeat(1_000),
            displayPath = "Large.java",
            virtualFileUrl = null,
            origin = SourceOrigin.PROJECT_SOURCE,
            language = "JAVA",
            maxUtf8Bytes = 1_024,
        )

        assertTrue(content.truncated)
        assertTrue(content.utf8ByteCount <= 1_024)
        assertEquals(content.utf8ByteCount, content.text.toByteArray(Charsets.UTF_8).size)
        assertTrue(content.diagnostic.orEmpty().contains("TRUNCATED"))
        assertTrue(content.text.all { it == '汉' })
    }
}
