package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class LinkGraphProjectServicePlanningTest : BasePlatformTestCase() {
    fun testRequestGenerationPlanUsesCurrentWorkingGraphInsteadOfStaleFactGraph() {
        val methodSignature = "com.example.ShiroUtils.setSysUser(com.example.SysUser):void"
        val visibleMethodNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "ShiroUtils.setSysUser",
            signature = methodSignature,
            sourceTag = GraphSourceTag.FACT,
        )
        val staleFactOnlyNode = GraphNode(
            id = "method:legacy-fallback",
            type = NodeType.METHOD,
            title = "LegacyFallback.handle",
            signature = "com.example.LegacyFallback.handle():void",
            sourceTag = GraphSourceTag.FACT,
        )
        val currentDraftNode = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工补充说明",
            doc = "当前画布里新增的说明节点。",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val designBaseline = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "design:manual-note",
                    type = NodeType.DOC_PAGE,
                    title = "人工补充说明",
                    sourceTag = GraphSourceTag.DESIGN_BASELINE,
                ),
            ),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = GraphDocument(nodes = listOf(visibleMethodNode)),
            fullGraph = GraphDocument(
                nodes = listOf(visibleMethodNode, staleFactOnlyNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:stale-fact",
                        type = EdgeType.CALL,
                        fromNodeId = visibleMethodNode.id,
                        toNodeId = staleFactOnlyNode.id,
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            source = "currentMethod",
            selectedMethodSignature = methodSignature,
        )
        stateService.importMermaid("graph TD\nA-->B", designBaseline)
        stateService.markGraphChanged(
            graph = GraphDocument(nodes = listOf(visibleMethodNode, currentDraftNode)),
            selectedMethodSignature = methodSignature,
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlan()

        val snapshot = stateService.snapshot()
        val plan = snapshot.generationPlan
        assertTrue(plan != null, "应生成计划结果")
        assertTrue(plan.promptPreview.contains("人工补充说明"))
        assertTrue(!plan.promptPreview.contains("LegacyFallback.handle"))
    }
}
