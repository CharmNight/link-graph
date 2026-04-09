package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkGraphProjectServiceDraftWorkbenchTest : BasePlatformTestCase() {
    fun testConfirmAuditCandidateChangeWritesToDraftWorkbenchStateAndProjectsGraph() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-action:upload-condition",
                    type = NodeType.FLOW_ACTION,
                    title = "上传条件判断",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条逻辑调整",
                answer = "建议修改条件判断。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-upload-condition",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "修改上传条件判断",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                        beforeState = "if (a > 10)",
                        afterState = "if (a < 100)",
                        reason = "原条件错误。",
                        impactSummary = "会影响上传分支。",
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-upload-condition")

        assertNotNull(entry)
        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals("change-upload-condition", snapshot.draftWorkbenchState.draftChanges.first().sourceChangeId)
        assertTrue(snapshot.workingGraphDirty)
        assertTrue(snapshot.workingGraph?.nodes?.any { it.id == "draft-entry:draft-change-upload-condition" } == true)
    }
}
