package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphAnchor
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.ui.bridge.BridgeCommandParser
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphBrowserPayloadParserTest {
    @Test
    fun bridgeCommandEnvelopeParsesAssistantTask() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "CHECK_CHANGE",
                    "actionId" to "CHECK_CHANGE",
                    "sceneId" to "WORKSPACE_REVIEW_GRAPH",
                    "prompt" to "检查这次改动影响哪些调用方",
                    "selectedNodeIds" to listOf("method:submit-order"),
                    "selectedDiffItemIds" to listOf("diff:OrderController.kt"),
                    "target" to mapOf(
                        "kind" to "RiskInvestigation",
                        "threadId" to "risk-thread:1",
                        "targetNodeIds" to listOf("method:validate-order"),
                    ),
                    "explanationGranularity" to "CODE_SEMANTIC",
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestAssistantTask>(parsed.message)
        assertEquals(AssistantIntent.CHECK_CHANGE, message.intent)
        assertEquals(AssistantActionId.CHECK_CHANGE, message.actionId)
        assertEquals("WORKSPACE_REVIEW_GRAPH", message.sceneId)
        assertEquals("检查这次改动影响哪些调用方", message.prompt)
        assertEquals(listOf("method:submit-order"), message.selectedNodeIds)
        assertEquals(listOf("diff:OrderController.kt"), message.selectedDiffItemIds)
        val target = assertIs<AssistantComposerTarget.RiskInvestigation>(message.target)
        assertEquals("risk-thread:1", target.threadId)
        assertEquals(listOf("method:validate-order"), target.targetNodeIds)
        assertEquals(StepGranularity.CODE_SEMANTIC, message.explanationGranularity)
        assertTrue(parsed.async)
    }

    @Test
    fun bridgeCommandEnvelopeParsesClassDescriptionAssistantIntent() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "DESCRIBE_CLASS",
                    "actionId" to "DESCRIBE_CLASS",
                    "sceneId" to "WORKSPACE_CLASS_DIAGRAM",
                    "prompt" to "请介绍类图节点“ClientRequestQuotaManager”",
                    "selectedNodeIds" to listOf("class:quota-manager"),
                    "target" to mapOf("kind" to "NewTask"),
                    "explanationGranularity" to "BUSINESS",
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestAssistantTask>(parsed.message)
        assertEquals(AssistantIntent.DESCRIBE_CLASS, message.intent)
        assertEquals(AssistantActionId.DESCRIBE_CLASS, message.actionId)
        assertEquals("WORKSPACE_CLASS_DIAGRAM", message.sceneId)
        assertEquals("请介绍类图节点“ClientRequestQuotaManager”", message.prompt)
        assertEquals(listOf("class:quota-manager"), message.selectedNodeIds)
        assertIs<AssistantComposerTarget.NewTask>(message.target)
        assertEquals(StepGranularity.BUSINESS, message.explanationGranularity)
    }

    @Test
    fun bridgeCommandEnvelopeParsesAssistantTaskTargets() {
        val qaRecovery = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "ASK_CODE",
                    "actionId" to "ASK_CONTEXT",
                    "prompt" to "风险 & 证据?",
                    "selectedNodeIds" to listOf("method:upload,file", "node/二"),
                    "target" to mapOf(
                        "kind" to "QaRecovery",
                        "requestId" to "qa-1",
                        "selectedNodeIds" to listOf("method:upload,file", "node/二"),
                        "sourceThreadId" to "thread:1",
                        "mode" to "INVESTIGATE",
                    ),
                ),
            ),
        )
        val explanation = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "EXPLAIN_CODE",
                    "actionId" to "EXPLAIN_STRUCTURE",
                    "prompt" to "继续解释第 2 步",
                    "target" to mapOf(
                        "kind" to "ExplanationFollowUp",
                        "stepId" to "step:validate",
                        "stepTitle" to "校验订单",
                        "focusNodeId" to "method:validate-order",
                    ),
                ),
            ),
        )
        val discussion = BridgeCommandParser.parse(
            command(
                "requestAssistantTask",
                mapOf(
                    "intent" to "GENERATE_CODE",
                    "actionId" to "GENERATE_IMPLEMENTATION",
                    "prompt" to "继续解释第 2 步",
                    "target" to mapOf(
                        "kind" to "GenerationDiscussion",
                        "planItemId" to "plan:item/2",
                    ),
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

        val qaMessage = assertIs<GraphEditorMessage.RequestAssistantTask>(qaRecovery.message)
        val qaTarget = assertIs<AssistantComposerTarget.QaRecovery>(qaMessage.target)
        val explanationMessage = assertIs<GraphEditorMessage.RequestAssistantTask>(explanation.message)
        val explanationTarget = assertIs<AssistantComposerTarget.ExplanationFollowUp>(explanationMessage.target)
        val discussionMessage = assertIs<GraphEditorMessage.RequestAssistantTask>(discussion.message)
        val discussionTarget = assertIs<AssistantComposerTarget.GenerationDiscussion>(discussionMessage.target)
        val layoutMessage = assertIs<GraphEditorMessage.LayoutChanged>(layout.message)
        assertEquals("风险 & 证据?", qaMessage.prompt)
        assertEquals("qa-1", qaTarget.requestId)
        assertEquals(listOf("method:upload,file", "node/二"), qaTarget.selectedNodeIds)
        assertEquals("thread:1", qaTarget.sourceThreadId)
        assertEquals("INVESTIGATE", qaTarget.mode?.name)
        assertEquals("step:validate", explanationTarget.stepId)
        assertEquals("校验订单", explanationTarget.stepTitle)
        assertEquals("method:validate-order", explanationTarget.focusNodeId)
        assertEquals("继续解释第 2 步", discussionMessage.prompt)
        assertEquals("plan:item/2", discussionTarget.planItemId)
        assertEquals(12.5, layoutMessage.positions["node:一"]?.x)
        assertEquals(9.25, layoutMessage.positions["node,two"]?.y)
    }

    @Test
    fun bridgeCommandEnvelopeRequiresConcreteAssistantActionId() {
        val error = assertFailsWith<IllegalStateException> {
            BridgeCommandParser.parse(
                command(
                    "requestAssistantTask",
                    mapOf(
                        "intent" to "EXPLAIN_CODE",
                        "prompt" to "解释当前结构",
                    ),
                ),
            )
        }

        assertTrue(error.message?.contains("actionId is required") == true)
    }

    @Test
    fun bridgeCommandEnvelopeRejectsAssistantIntentAndActionMismatch() {
        val error = assertFailsWith<IllegalStateException> {
            BridgeCommandParser.parse(
                command(
                    "requestAssistantTask",
                    mapOf(
                        "intent" to "ASK_CODE",
                        "actionId" to "DESCRIBE_CLASS",
                        "prompt" to "解释当前结构",
                    ),
                ),
            )
        }

        assertTrue(error.message?.contains("does not match actionId") == true)
    }

    @Test
    fun bridgeCommandEnvelopeRejectsOldNaturalLanguageMainTaskCommands() {
        listOf(
            "requestQa",
            "requestDiffReview",
            "requestGraphBeautification",
            "requestGenerationPlan",
            "requestGenerationPlanDiscussion",
        ).forEach { type ->
            val error = assertFailsWith<IllegalStateException> {
                BridgeCommandParser.parse(command(type, mapOf("question" to "旧入口不应保留")))
            }

            assertTrue(error.message?.contains("unsupported bridge command type") == true)
        }
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
    fun bridgeCommandEnvelopeParsesClassUsageRequestsAsClassDiagramPreset() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestIndexedGraph",
                mapOf(
                    "preset" to "CLASS_DIAGRAM",
                    "scopeNodeId" to "jvm:class:com-example-order-service",
                    "usage" to mapOf(
                        "enabled" to true,
                        "targetNodeId" to "jvm:class:com-example-order-service",
                        "targetQualifiedName" to "com.example.OrderService",
                        "sourceVirtualFileUrl" to "file:///project/src/main/java/com/example/OrderService.java",
                        "sourcePath" to "src/main/java/com/example/OrderService.java",
                        "maxUsageGroups" to 12,
                        "maxUsageEntries" to 40,
                        "includeImports" to true,
                    ),
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestIndexedGraph>(parsed.message)
        assertEquals(IndexedGraphView.CLASS_DIAGRAM, message.request.view)
        assertEquals("jvm:class:com-example-order-service", assertIs<IndexedGraphAnchor.ArchitectureNode>(message.request.anchor).nodeId)
        assertEquals(true, message.request.usage.enabled)
        assertEquals("jvm:class:com-example-order-service", message.request.usage.targetNodeId)
        assertEquals("com.example.OrderService", message.request.usage.targetQualifiedName)
        assertEquals("file:///project/src/main/java/com/example/OrderService.java", message.request.usage.sourceVirtualFileUrl)
        assertEquals("src/main/java/com/example/OrderService.java", message.request.usage.sourcePath)
        assertEquals(12, message.request.usage.maxUsageGroups)
        assertEquals(40, message.request.usage.maxUsageEntries)
        assertEquals(true, message.request.usage.includeImports)
    }

    @Test
    fun bridgeCommandEnvelopeClampsOversizedClassUsageLimits() {
        val parsed = BridgeCommandParser.parse(
            command(
                "requestIndexedGraph",
                mapOf(
                    "preset" to "CLASS_DIAGRAM",
                    "scopeNodeId" to "jvm:class:com-example-order-service",
                    "usage" to mapOf(
                        "enabled" to true,
                        "targetNodeId" to "jvm:class:com-example-order-service",
                        "targetQualifiedName" to "com.example.OrderService",
                        "maxUsageGroups" to 9999,
                        "maxUsageEntries" to 99999,
                    ),
                ),
            ),
        )

        val message = assertIs<GraphEditorMessage.RequestIndexedGraph>(parsed.message)
        assertEquals(200, message.request.usage.maxUsageGroups)
        assertEquals(1000, message.request.usage.maxUsageEntries)
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
            BridgeCommandParser.parse("""{"schemaVersion":2,"type":"requestAssistantTask","payload":{}}""")
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
