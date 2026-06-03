package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphAnchor
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.ui.bridge.BridgeCommandParser
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphBrowserPayloadParserTest {
    @Test
    fun bridgeCommandEnvelopeDefaultsQaModeToAuto() {
        val parsed = BridgeCommandParser.parse(
            command("requestQa", mapOf("question" to "这个方法是如何触发的？")),
        )

        val message = assertIs<GraphEditorMessage.RequestQa>(parsed.message)
        assertEquals("这个方法是如何触发的？", message.question)
        assertEquals(emptyList(), message.selectedNodeIds)
        assertEquals(null, message.sourceThreadId)
        assertEquals(QaMode.AUTO, message.mode)
        assertTrue(parsed.async)
    }

    @Test
    fun bridgeCommandEnvelopeParsesAssistantTask() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "CHECK_CHANGE",
                    "prompt" to "检查这次改动影响哪些调用方",
                    "selectedNodeIds" to listOf("method:submit-order"),
                    "selectedDiffItemIds" to listOf("diff:OrderController.kt"),
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestAssistantTask>(parsed.message)
        assertEquals(AssistantIntent.CHECK_CHANGE, message.intent)
        assertEquals("检查这次改动影响哪些调用方", message.prompt)
        assertEquals(listOf("method:submit-order"), message.selectedNodeIds)
        assertEquals(listOf("diff:OrderController.kt"), message.selectedDiffItemIds)
        assertTrue(parsed.async)
    }

    @Test
    fun bridgeCommandEnvelopeParsesStructuredCommandsWithoutDelimiters() {
        val qa = BridgeCommandParser.parse(
            command(
                "requestQa",
                mapOf(
                    "question" to "风险 & 证据?",
                    "selectedNodeIds" to listOf("method:upload,file", "node/二"),
                    "sourceThreadId" to "thread:1",
                    "mode" to "INVESTIGATE",
                ),
            ),
        )
        val discussion = BridgeCommandParser.parse(
            command(
                "requestGenerationPlanDiscussion",
                mapOf(
                    "question" to "继续解释第 2 步",
                    "focusItemId" to "plan:item/2",
                ),
            ),
        )
        val layout = BridgeCommandParser.parse(
            command(
                "layoutChanged",
                mapOf(
                    "positions" to listOf(
                        mapOf("nodeId" to "node:一", "x" to 12.5, "y" to -4.0),
                        mapOf("nodeId" to "node,two", "x" to 0.0, "y" to 9.25),
                    ),
                ),
            ),
        )

        val qaMessage = assertIs<GraphEditorMessage.RequestQa>(qa.message)
        val discussionMessage = assertIs<GraphEditorMessage.RequestGenerationPlanDiscussion>(discussion.message)
        val layoutMessage = assertIs<GraphEditorMessage.LayoutChanged>(layout.message)
        assertEquals("风险 & 证据?", qaMessage.question)
        assertEquals(listOf("method:upload,file", "node/二"), qaMessage.selectedNodeIds)
        assertEquals("thread:1", qaMessage.sourceThreadId)
        assertEquals(QaMode.INVESTIGATE, qaMessage.mode)
        assertEquals("继续解释第 2 步", discussionMessage.question)
        assertEquals("plan:item/2", discussionMessage.focusItemId)
        assertEquals(12.5, layoutMessage.positions["node:一"]?.x)
        assertEquals(9.25, layoutMessage.positions["node,two"]?.y)
    }

    @Test
    fun bridgeCommandEnvelopeParsesBeautificationFollowUpAndFocus() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestGraphBeautification",
                mapOf(
                    "goal" to "讲解展开链路",
                    "preferredStyle" to "汇报版",
                    "explanationFocus" to "请重点讲解展开方法",
                    "focusNodeId" to "method:create-info",
                    "granularity" to "METHOD_CALL",
                    "followUp" to mapOf(
                        "stepId" to "step-create-info",
                        "stepTitle" to "展开 createInfo",
                        "question" to "展开方法做了什么？",
                    ),
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestGraphBeautification>(parsed.message)
        assertEquals("讲解展开链路", message.goal)
        assertEquals("汇报版", message.preferredStyle)
        assertEquals("请重点讲解展开方法", message.explanationFocus)
        assertEquals("method:create-info", message.focusNodeId)
        assertEquals(StepGranularity.METHOD_CALL, message.granularity)
        assertEquals("step-create-info", message.followUp?.stepId)
        assertTrue(parsed.async)
    }

    @Test
    fun bridgeCommandEnvelopeRejectsIndexedFullRequestsWithoutPreset() {
        val error = assertFailsWith<IllegalStateException> {
            BridgeCommandParser.parse(
                command(
                    "requestIndexedGraph",
                    mapOf(
                        "view" to "CLASS_DIAGRAM",
                        "anchor" to mapOf("kind" to "ARCHITECTURE_NODE", "nodeId" to "component:orders"),
                        "scope" to mapOf("kind" to "ARCHITECTURE_NODE", "nodeId" to "component:orders"),
                        "depth" to 1,
                        "includeExternalLibraries" to false,
                        "includeJdk" to false,
                        "classDiagram" to mapOf("neighborhoodLimit" to 18, "memberLimit" to 7),
                    ),
                ),
            )
        }

        assertTrue(error.message?.contains("preset") == true)
    }

    @Test
    fun bridgeCommandEnvelopeExpandsIndexedPresetRequestsOnBackend() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestIndexedGraph",
                mapOf(
                    "preset" to "CLASS_DIAGRAM",
                    "scopeNodeId" to "component:orders",
                    "classDiagram" to mapOf("neighborhoodLimit" to 36),
                    "viewport" to mapOf("maxVisibleNodes" to 72),
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestIndexedGraph>(parsed.message)
        assertEquals(IndexedGraphView.CLASS_DIAGRAM, message.request.view)
        assertEquals("component:orders", assertIs<IndexedGraphAnchor.ArchitectureNode>(message.request.anchor).nodeId)
        assertEquals("component:orders", assertIs<IndexedGraphScope.ArchitectureNode>(message.request.scope).nodeId)
        assertEquals(false, message.request.includeExternalLibraries)
        assertEquals(false, message.request.includeJdk)
        assertEquals(36, message.request.classDiagram.neighborhoodLimit)
        assertEquals(5, message.request.classDiagram.memberLimit)
        assertEquals(72, message.request.viewport.maxVisibleNodes)
    }

    @Test
    fun bridgeCommandEnvelopeParsesArtifactRequestsSeparatelyFromEditorMessages() {
        val parsed = BridgeCommandParser.parse(
            command("requestArtifact", mapOf("artifactIds" to listOf("artifact:1", "artifact:2"))),
        )

        assertEquals(null, parsed.message)
        assertEquals(listOf("artifact:1", "artifact:2"), parsed.artifactIds)
    }

    @Test
    fun bridgeCommandEnvelopeParsesRiskResolution() {
        val parsed = BridgeCommandParser.parse(
            command(
                "resolveInvestigationThread",
                mapOf(
                    "threadId" to "thread-risk-1",
                    "resolutionStatus" to "ACCEPTED_RISK",
                    "note" to "已确认",
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.ResolveInvestigationThread>(parsed.message)
        assertEquals("thread-risk-1", message.threadId)
        assertEquals(RiskResolutionStatus.ACCEPTED_RISK, message.resolutionStatus)
        assertEquals("已确认", message.note)
    }

    @Test
    fun rejectsBridgeCommandWithoutSupportedSchemaVersion() {
        val error = assertFailsWith<IllegalArgumentException> {
            BridgeCommandParser.parse("""{"schemaVersion":2,"type":"requestQa","payload":{}}""")
        }

        assertTrue(error.message?.contains("schemaVersion") == true)
    }

    @Test
    fun rejectsOversizedStructuredPayloadBeforeParsing() {
        val payload = "x".repeat(GraphBrowserPayloadLimits.STRUCTURED_PAYLOAD_MAX_CHARS + 1)

        val error = assertFailsWith<IllegalArgumentException> {
            BridgeCommandParser.parse(payload)
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

    private fun command(
        type: String,
        payload: Map<String, Any?> = emptyMap(),
    ): String = JsonCodec.toJson(
        linkedMapOf(
            "schemaVersion" to 1,
            "type" to type,
            "payload" to payload,
        ),
    )
}
