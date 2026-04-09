package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LinkGraphProjectServiceNavigationTest {
    @Test
    fun findNavigationNodeFallsBackToDraftGraphAndDesignBaseline() {
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
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(),
            workingGraph = GraphDocument(nodes = listOf(draftNode)),
            designBaselineGraph = GraphDocument(nodes = listOf(baselineNode)),
        )

        assertEquals(draftNode, findNavigationNode(snapshot, draftNode.id))
        assertNotNull(findNavigationNode(snapshot, baselineNode.id))
        assertEquals("OrderDraftDto", findNavigationNode(snapshot, baselineNode.id)?.title)
    }
}
