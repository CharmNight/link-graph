package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
            id = "method:fallback-guard",
            type = NodeType.METHOD,
            title = "FallbackGuard.handle",
            signature = "com.example.FallbackGuard.handle():void",
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
        stateService.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-manual-note",
                        kind = DraftEntryKind.CHANGE,
                        sourceChangeId = "change-manual-note",
                        title = "补充人工说明节点",
                        targetNodeIds = listOf(currentDraftNode.id),
                        beforeState = "当前缺少人工补充说明",
                        afterState = "在工作图里补充说明节点",
                        reason = "计划应围绕已确认草稿层，而不是历史事实图。",
                        impactSummary = "影响实现计划的上下文聚焦范围。",
                    ),
                ),
            ),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlan()

        val snapshot = stateService.snapshot()
        val plan = snapshot.generationPlan
        assertTrue(plan != null, "应生成计划结果")
        assertTrue(plan.promptPreview.contains("人工补充说明"))
        assertTrue(!plan.promptPreview.contains("FallbackGuard.handle"))
    }

    fun testRequestGenerationPlanRejectsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentContext",
            selectedMethodSignature = "com.example.OrderController.submit():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlan()

        val snapshot = stateService.snapshot()
        assertNull(snapshot.generationPlan)
        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.generationPlanRequestState.phase)
        assertEquals("实现计划", snapshot.generationPlanRequestState.scene)
        assertTrue(snapshot.generationPlanRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertTrue(snapshot.generationPlanRequestState.detailMessage?.contains("先在问答结果中确认候选变更") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    fun testRequestCodeDraftsRejectsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentContext",
            selectedMethodSignature = "com.example.OrderController.submit():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestCodeDrafts()

        val snapshot = stateService.snapshot()
        assertTrue(snapshot.generatedCodeDrafts.isEmpty())
        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.codeDraftRequestState.phase)
        assertEquals("代码草稿", snapshot.codeDraftRequestState.scene)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("先在问答结果中确认候选变更") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    private fun sampleGraph(): GraphDocument {
        return GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit-order",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    signature = "com.example.OrderController.submit():void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
    }
}
