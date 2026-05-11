package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.QaMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphBrowserPayloadParserTest {
    @Test
    fun auditPayloadDefaultsModeToAutoForLegacyBridgeCalls() {
        val payload = listOf(
            encode("这个方法是如何触发的？"),
            "",
            "",
        ).joinToString("\u001F")

        val parsed = GraphBrowserPayloadParser.parseQaRequestPayload(payload)

        assertEquals("这个方法是如何触发的？", parsed.question)
        assertEquals(QaMode.AUTO, parsed.mode)
    }

    @Test
    fun auditPayloadParsesExplicitMode() {
        val payload = listOf(
            encode("请继续取证"),
            encode("method:upload"),
            encode("thread-risk-1"),
            encode("INVESTIGATE"),
        ).joinToString("\u001F")

        val parsed = GraphBrowserPayloadParser.parseQaRequestPayload(payload)

        assertEquals(listOf("method:upload"), parsed.selectedNodeIds)
        assertEquals("thread-risk-1", parsed.sourceThreadId)
        assertEquals(QaMode.INVESTIGATE, parsed.mode)
    }

    @Test
    fun rejectsOversizedStructuredPayloadBeforeParsing() {
        val payload = "x".repeat(GraphBrowserPayloadLimits.STRUCTURED_PAYLOAD_MAX_CHARS + 1)

        val error = assertFailsWith<IllegalArgumentException> {
            GraphBrowserPayloadParser.parseQaRequestPayload(payload)
        }

        assertTrue(error.message?.contains("payload 过大") == true)
    }

    @Test
    fun rejectsOversizedMermaidPayloadBeforeDispatch() {
        val payload = "x".repeat(GraphBrowserPayloadLimits.MERMAID_PAYLOAD_MAX_CHARS + 1)

        val error = assertFailsWith<IllegalArgumentException> {
            GraphBrowserPayloadParser.validatePayloadSize(payload, GraphBrowserPayloadKind.MERMAID)
        }

        assertTrue(error.message?.contains("payload 过大") == true)
    }

    @Test
    fun rejectsOversizedGraphEditScriptPayloadBeforeJsonParsing() {
        val payload = "x".repeat(GraphBrowserPayloadLimits.GRAPH_EDIT_SCRIPT_MAX_CHARS + 1)

        val error = assertFailsWith<IllegalArgumentException> {
            GraphBrowserPayloadParser.parseGraphEditScript(payload)
        }

        assertTrue(error.message?.contains("payload 过大") == true)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
