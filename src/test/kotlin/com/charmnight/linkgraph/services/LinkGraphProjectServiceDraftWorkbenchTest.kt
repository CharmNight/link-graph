package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                        claimType = "CODE_FACT",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-upload-condition",
                                targetNodeId = "flow-action:upload-condition",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
                                startLine = 21,
                                endLine = 34,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                                supportingFindingIds = listOf("finding-upload-condition"),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
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
        assertEquals("CODE_FACT", snapshot.draftWorkbenchState.draftChanges.first().claimType)
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.first().evidence.size)
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.first().editScopes.size)
        assertEquals(
            "scope-upload-condition",
            snapshot.draftWorkbenchState.draftChanges.first().editScopes.first().scopeId,
        )
        assertEquals(
            CandidateDraftChangeStatus.CONFIRMED,
            snapshot.auditResult?.candidateChanges?.firstOrNull()?.status,
        )
        assertTrue(snapshot.workingGraphDirty)
        assertTrue(snapshot.workingGraph?.nodes?.any { it.id == "draft-entry:draft-change-upload-condition" } == true)
    }

    fun testConfirmAuditCandidateChangeRequestsFrontendSyncImmediately() {
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
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        var syncRequestedCount = 0
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(
            GraphEditorSyncNotifier.TOPIC,
            object : GraphEditorSyncNotifier.Listener {
                override fun onSyncRequested() {
                    syncRequestedCount += 1
                }
            },
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-upload-condition")

        assertEquals(1, syncRequestedCount)
    }

    fun testUnconfirmAuditCandidateChangeRestoresPendingCandidateAndRemovesProjectedDraft() {
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
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val service = project.getService(LinkGraphProjectService::class.java)
        service.confirmAuditCandidateChange("change-upload-condition")

        val removed = service.unconfirmAuditCandidateChange("change-upload-condition")

        assertNotNull(removed)
        val snapshot = stateService.snapshot()
        assertTrue(snapshot.draftWorkbenchState.draftChanges.isEmpty())
        assertEquals(
            CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            snapshot.auditResult?.candidateChanges?.firstOrNull()?.status,
        )
        assertNull(snapshot.workingGraph?.nodes?.firstOrNull { it.id == "draft-entry:draft-change-upload-condition" })
    }

    fun testConfirmAuditCandidateChangeRejectsWeakEvidenceSuggestions() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "上传方法",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "这里是否有路径问题？",
                answer = "当前只能确认调用点，需要继续看上传工具实现。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-path-risk",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "补充路径风险说明",
                        targetNodeIds = listOf("method:upload-file"),
                        beforeState = "当前图中未确认上传工具内部路径校验",
                        afterState = "补充说明这里只是调用点，需继续核对被调实现",
                        reason = "当前只有调用点证据。",
                        impactSummary = "会影响这条审计建议是否可直接进入草稿。",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-callsite-only",
                                claim = "这里只看到 MultipartFile 被传给上传工具。",
                                evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                                references = listOf(ResultEvidenceReference(nodeId = "method:upload-file")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-path-risk")

        assertNull(entry)
        val snapshot = stateService.snapshot()
        assertTrue(snapshot.draftWorkbenchState.draftChanges.isEmpty())
        assertEquals(
            CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            snapshot.auditResult?.candidateChanges?.firstOrNull()?.status,
        )
        assertNull(snapshot.workingGraph?.nodes?.firstOrNull { it.id == "draft-entry:draft-change-path-risk" })
    }

    fun testConfirmAuditCandidateChangeDoesNotInjectDraftProjectionIntoFlowchartView() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-action:upload-condition",
                    type = NodeType.FLOW_ACTION,
                    title = "上传条件判断",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "PROCESS",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge:upload-condition->upload-condition",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "flow-action:upload-condition",
                    toNodeId = "flow-action:upload-condition",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
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
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-upload-condition")

        val snapshot = stateService.snapshot()
        assertEquals(
            listOf("flow-action:upload-condition"),
            snapshot.flowchartView?.visibleGraph?.nodes?.map { it.id },
        )
        assertTrue(
            snapshot.flowchartView?.visibleGraph?.edges?.none { edge -> edge.metadata["draft.entryId"] != null } == true,
        )
    }
}
