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
    fun parsesFrontendEncodedBridgePayloadExamples() {
        val qaPayload = listOf(
            encode("风险 & 证据?"),
            listOf("method:upload,file", "node/二").joinToString(",") { encode(it) },
            encode("thread:1"),
            encode("INVESTIGATE"),
        ).joinToString("\u001F")
        val discussionPayload = listOf(
            encode("继续解释第 2 步"),
            encode("plan:item/2"),
        ).joinToString("\u001F")
        val layoutPayload = listOf(
            listOf(encode("node:一"), "12.5", "-4.0").joinToString("\u001F"),
            listOf(encode("node,two"), "0.0", "9.25").joinToString("\u001F"),
        ).joinToString("\u001E")

        val qa = GraphBrowserPayloadParser.parseQaRequestPayload(qaPayload)
        val discussion = GraphBrowserPayloadParser.parseGenerationPlanDiscussionPayload(discussionPayload)
        val layout = GraphBrowserPayloadParser.parseLayoutPositions(layoutPayload)

        assertEquals("风险 & 证据?", qa.question)
        assertEquals(listOf("method:upload,file", "node/二"), qa.selectedNodeIds)
        assertEquals("thread:1", qa.sourceThreadId)
        assertEquals(QaMode.INVESTIGATE, qa.mode)
        assertEquals("继续解释第 2 步", discussion.question)
        assertEquals("plan:item/2", discussion.focusItemId)
        assertEquals(12.5, layout["node:一"]?.x)
        assertEquals(9.25, layout["node,two"]?.y)
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
