package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.findTrustedNavigationNode
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.view.FlowchartSummary
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowNavigationModelTest {
    @Test
    fun findTrustedNavigationNodeRejectsDraftGraphAndDesignBaselineFallbacks() {
        val draftNode = GraphNode(
            id = "draft:manual-node",
            type = NodeType.DOC_PAGE,
            title = "人工说明",
            location = "docs/flow.md:1",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val baselineNode = GraphNode(
            id = "design:baseline-node",
            type = NodeType.CLASS,
            title = "OrderDraftDto",
            signature = "com.example.OrderDraftDto",
            sourceTag = GraphSourceTag.DESIGN_BASELINE,
        )
        val snapshot = testSnapshot(
            visibleGraph = GraphDocument(),
            workingGraph = GraphDocument(nodes = listOf(draftNode)),
            designBaselineGraph = GraphDocument(nodes = listOf(baselineNode)),
        )

        assertNull(findTrustedNavigationNode(snapshot.toWorkflowEditorSnapshot(), draftNode.id))
        assertNull(findTrustedNavigationNode(snapshot.toWorkflowEditorSnapshot(), baselineNode.id))
    }

    @Test
    fun findTrustedNavigationNodeReadsTrustedNavigationIndexForProjectedNodes() {
        val projectedDecisionNode = GraphNode(
            id = "decision:allowed",
            type = NodeType.FLOW_SCOPE,
            title = "if (!allowed)",
            sourceTag = GraphSourceTag.FACT,
            metadata = mapOf(
                "flowchart.kind" to "DECISION",
                "flowchart.projectedFromNodeIds" to "condition:allowed-check",
            ),
        )
        val hiddenConditionNode = GraphNode(
            id = "condition:allowed-check",
            type = NodeType.FLOW_ACTION,
            title = "!checkAllowDownload(fileName)",
            sourceTag = GraphSourceTag.FACT,
            metadata = mapOf(
                "flowchart.kind" to "PROCESS",
                "flow.kind" to "CONDITION",
            ),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART,
            visibleGraph = GraphDocument(nodes = listOf(projectedDecisionNode)),
            workingGraph = null,
            trustedNavigationNodes = mapOf(
                hiddenConditionNode.id to hiddenConditionNode,
                projectedDecisionNode.id to projectedDecisionNode,
            ),
            flowchartView = FlowchartViewDocument(
                visibleGraph = GraphDocument(nodes = listOf(projectedDecisionNode)),
                fullGraph = GraphDocument(nodes = listOf(hiddenConditionNode, projectedDecisionNode)),
                anchorNodeId = projectedDecisionNode.id,
                summary = FlowchartSummary(
                    nodeCount = 1,
                    fullNodeCount = 2,
                    hiddenNodeCount = 1,
                    truncated = true,
                ),
            ),
        )

        assertEquals(hiddenConditionNode, findTrustedNavigationNode(snapshot.toWorkflowEditorSnapshot(), hiddenConditionNode.id))
    }
}
