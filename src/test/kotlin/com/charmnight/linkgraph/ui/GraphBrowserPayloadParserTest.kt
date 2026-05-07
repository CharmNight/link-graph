package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.QaMode
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphBrowserPayloadParserTest {
    @Test
    fun auditPayloadDefaultsModeToAutoForLegacyBridgeCalls() {
        val payload = listOf(
            encode("这个方法是如何触发的？"),
            "",
            "",
        ).joinToString("\u001F")

        val parsed = GraphBrowserPayloadParser.parseAuditRequestPayload(payload)

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

        val parsed = GraphBrowserPayloadParser.parseAuditRequestPayload(payload)

        assertEquals(listOf("method:upload"), parsed.selectedNodeIds)
        assertEquals("thread-risk-1", parsed.sourceThreadId)
        assertEquals(QaMode.INVESTIGATE, parsed.mode)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
