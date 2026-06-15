package com.charmnight.linkgraph.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphBrowserBridgeRegistrarTest {
    private val projectRoot: Path = Path.of("").toAbsolutePath()

    @Test
    fun browserBridgeUsesSingleCommandQueryForFrontendActions() {
        val source = readRegistrar()
        val commandQueries = Regex("""private val ([A-Za-z0-9_]+Query): JBCefJSQuery""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filterNot { it == "debugTraceQuery" }
            .toList()

        assertEquals(
            listOf("bridgeCommandQuery"),
            commandQueries,
            "frontend actions must enter through one bridgeCommandQuery; debug trace may keep a separate low-risk query",
        )
        assertFalse(source.contains("PAYLOAD_SEPARATOR"))
        assertFalse(source.contains("\\u001f"))
        assertFalse(source.contains("URLDecoder"))
    }

    @Test
    fun injectedBridgeConvenienceMethodsSendJsonCommandEnvelopes() {
        val source = readRegistrar()

        assertTrue(source.contains("sendCommand: (command)"))
        assertTrue(source.contains("schemaVersion: 1"))
        assertTrue(source.contains("type: type"))
        assertTrue(source.contains("payload: payload"))
        assertTrue(source.contains("requestAssistantTask: (request) => sendCommand(\"requestAssistantTask\""))
        assertFalse(source.contains("actionId: request && request.actionId ? request.actionId :"))
        assertFalse(source.contains("intent: request && request.intent ? request.intent :"))
        assertTrue(source.contains("target: request && request.target ? request.target : { kind: \"NewTask\" }"))
        assertTrue(source.contains("requestIndexedGraph: (request) => sendCommand(\"requestIndexedGraph\""))
        assertTrue(source.contains("applyGraphEditScript: (payload) => sendCommand(\"applyGraphEditScript\""))
        listOf(
            "requestQa",
            "requestDiffReview",
            "requestGraphBeautification",
            "requestGenerationPlan",
            "requestGenerationPlanDiscussion",
        ).forEach { oldCommand ->
            assertFalse(
                source.contains("""$oldCommand:""") || source.contains("""sendCommand("$oldCommand""""),
                "$oldCommand must not remain as a natural-language assistant entrypoint",
            )
        }
    }

    @Test
    fun ideUiAndIndexingCommandsRemainAsynchronouslyDispatched() {
        val source = readRegistrar()

        listOf(
            "requestAssistantTask",
            "requestIndexedGraph",
            "requestOpenSettings",
            "applyCodeDrafts",
            "applySingleCodeDraft",
            "openCodeDraftNativeDiff",
            "requestDraftNavigation",
        ).forEach { commandType ->
            assertTrue(
                source.contains(""""$commandType""""),
                "$commandType must be represented in the async command set",
            )
        }
        assertTrue(source.contains("dispatchBridgeAsync(parsed.actionLabel)"))
    }

    private fun readRegistrar(): String =
        Files.readString(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt"))
}
