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
import kotlin.test.assertNotNull
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
        stateService.workbench.markDraftWorkbenchState(
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

    fun testRequestGenerationPlanNoLongerRejectsWhenNoConfirmedDraftChangesExist() {
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
        val plan = snapshot.generationPlan
        assertTrue(plan != null, "即使当前草稿为空，也应该允许生成实现建议。")
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanRequestState.phase)
        assertEquals("实现计划", snapshot.generationPlanRequestState.scene)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    fun testRequestGenerationPlanNoLongerBlocksOnUnresolvedRiskThreads() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentContext",
            selectedMethodSignature = "com.example.OrderController.submit():void",
        )
        stateService.asyncRequests.markAuditResult(
            com.charmnight.linkgraph.llm.GraphPatchResult(
                source = com.charmnight.linkgraph.llm.LlmResultSource.MOCK,
                question = "这里是否还有默认兜底分支？",
                answer = "仍有待验证风险。",
                promptPreview = "prompt",
                investigationThreads = listOf(
                    com.charmnight.linkgraph.workbench.InvestigationThread(
                        threadId = "thread-fallback",
                        status = com.charmnight.linkgraph.workbench.InvestigationThreadStatus.OPEN,
                        title = "默认兜底待确认",
                    ),
                ),
            ),
            com.charmnight.linkgraph.ui.AsyncRequestState.succeeded(scene = "问答"),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlan()

        val snapshot = stateService.snapshot()
        assertTrue(snapshot.generationPlan != null, "实现建议不应再被风险线程阻塞。")
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanRequestState.phase)
    }

    fun testRequestGenerationPlanDiscussionStaysInsideSuggestionStage() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentContext",
            selectedMethodSignature = "com.example.OrderController.submit():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlan()
        service.requestGenerationPlanDiscussion("为什么建议先改这里？")

        val snapshot = stateService.snapshot()
        val discussionSession = requireNotNull(snapshot.generationPlanDiscussionSession)
        assertEquals(2, discussionSession.messages.size)
        assertEquals("USER", discussionSession.messages[0].role.name)
        assertEquals("ASSISTANT", discussionSession.messages[1].role.name)
        assertTrue(discussionSession.messages[1].content.contains("实现建议"))
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanDiscussionRequestState.phase)
        assertNull(snapshot.auditResult, "实现建议追问不应把用户重新导向风险问答结果。")
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
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED, snapshot.codeDraftRequestState.phase)
        assertEquals("代码草稿", snapshot.codeDraftRequestState.scene)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("先在问答结果中确认候选变更") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
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
