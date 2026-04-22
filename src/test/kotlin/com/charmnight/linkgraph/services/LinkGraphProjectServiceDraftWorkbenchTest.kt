package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.outcome.AnalysisProjectionStats
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.CandidatePatchIntent
import com.charmnight.linkgraph.workbench.CandidatePatchIntentMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkGraphProjectServiceDraftWorkbenchTest : BasePlatformTestCase() {
    fun testConfirmAuditCandidateChangeUpdatesExistingDecisionNodeInWorkingGraph() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-scope:delete-guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
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
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "调整删除判断",
                        targetNodeIds = listOf("flow-scope:delete-guard"),
                        beforeState = "if (delete)",
                        afterState = "if (delete == true)",
                        reason = "需要显式判断布尔值。",
                        impactSummary = "影响删除分支。",
                        claimType = "CODE_FACT",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-delete-guard",
                                targetNodeId = "flow-scope:delete-guard",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "FLOW_SCOPE",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,boolean):void",
                                startLine = 21,
                                endLine = 34,
                                allowedChangeKinds = listOf("REPLACE_CONDITION_EXPRESSION"),
                                supportingFindingIds = listOf("finding-delete-guard"),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-scope:delete-guard")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        assertNotNull(entry)
        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals("change-delete-guard", snapshot.draftWorkbenchState.draftChanges.first().sourceChangeId)
        assertEquals("CODE_FACT", snapshot.draftWorkbenchState.draftChanges.first().claimType)
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.first().evidence.size)
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.first().editScopes.size)
        assertEquals(
            "scope-delete-guard",
            snapshot.draftWorkbenchState.draftChanges.first().editScopes.first().scopeId,
        )
        assertEquals(
            CandidateDraftChangeStatus.CONFIRMED,
            snapshot.auditResult?.candidateChanges?.firstOrNull()?.status,
        )
        assertEquals(
            listOf("flow-scope:delete-guard"),
            snapshot.workingGraph?.nodes?.map { it.id },
        )
        assertEquals(
            "if (delete == true)",
            snapshot.workingGraph?.nodes?.singleOrNull()?.title,
        )
        assertEquals(
            GraphPatchAction.UPDATE_NODE,
            snapshot.draftWorkbenchState.draftChanges.first().graphPatch?.operations?.singleOrNull()?.action,
        )
        assertTrue(snapshot.workingGraph?.edges?.isEmpty() == true)
        assertEquals(true, snapshot.workingGraphDirty)
    }

    fun testConfirmAuditCandidateChangeExtractsReadableDecisionTitleFromLongAfterStateExplanation() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条删除条件调整",
                answer = "建议只在 delete 显式为 true 时才删除。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "将 if(delete) 改为只在 delete 显式为 true 时删除（避免 Boolean 自动拆箱 NPE）",
                        targetNodeIds = listOf("scope:file-download-if"),
                        beforeState = "if (delete)",
                        afterState = "控制器使用 if (Boolean.TRUE.equals(delete))（或等价的 delete != null && delete）判断是否删除，避免 delete == null 时 NPE，并确保仅 delete == true 才删除。",
                        reason = "delete 为包装类型 Boolean，直接 if(delete) 存在运行时边界。",
                        impactSummary = "影响删除分支是否进入。",
                        claimType = "CODE_FACT",
                        graphPatch = GraphPatch(
                            summary = "收紧删除条件",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-delete-guard",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:file-download-if",
                                    title = "将 if(delete) 改为只在 delete 显式为 true 时删除（避免 Boolean 自动拆箱 NPE）",
                                    summary = "把删除条件改为显式 true 判断。",
                                    node = GraphNode(
                                        id = "scope:file-download-if",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "控制器使用 if (Boolean.TRUE.equals(delete))（或等价的 delete != null && delete）判断是否删除，避免 delete == null 时 NPE。",
                                        doc = "删除条件说明",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf("flowchart.kind" to "DECISION"),
                                    ),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到 if (delete) 删除条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        assertNotNull(entry)
        val snapshot = stateService.snapshot()
        assertEquals(
            "if (Boolean.TRUE.equals(delete))",
            snapshot.draftWorkbenchState.draftChanges.first().graphPatch?.operations?.singleOrNull()?.node?.title,
        )
        assertEquals(
            "if (Boolean.TRUE.equals(delete))",
            snapshot.workingGraph?.nodes?.singleOrNull()?.title,
        )
    }

    fun testConfirmAuditCandidateChangeInsertsExplicitDecisionNodeInsteadOfRewritingExistingNode() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val downloadSignature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void"
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = downloadSignature,
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "ENTRY",
                        "flow.ownerMethod" to downloadSignature,
                    ),
                ),
                GraphNode(
                    id = "action:delete-file",
                    type = NodeType.FLOW_ACTION,
                    title = "FileUtils.deleteFile(filePath)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "PROCESS",
                        "flow.ownerMethod" to downloadSignature,
                    ),
                ),
                GraphNode(
                    id = "terminal:return",
                    type = NodeType.TERMINAL,
                    title = "return",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "TERMINAL",
                        "flow.ownerMethod" to downloadSignature,
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-delete",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "action:delete-file",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "edge:delete-return",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "action:delete-file",
                    toNodeId = "terminal:return",
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
                answer = "建议在删除前增加文件存在性判断。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-insert-file-exists-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "在删除前增加文件存在性判断",
                        targetNodeIds = listOf("action:delete-file", "terminal:return"),
                        beforeState = "直接执行删除动作",
                        afterState = "if (fileExists(filePath))",
                        reason = "文件不存在时应跳过删除。",
                        impactSummary = "新增一个显式决策节点。",
                        claimType = "STRUCTURAL_SUGGESTION",
                        patchIntent = CandidatePatchIntent(
                            mode = CandidatePatchIntentMode.INSERT_NEW_DECISION,
                            attachEdgeId = "edge:entry-delete",
                            falseBranchTargetNodeId = "terminal:return",
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-file",
                                claim = "当前源码里直接能看到删除动作及后续 return。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "action:delete-file")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-insert-file-exists-guard")

        assertNotNull(entry)
        val snapshot = stateService.snapshot()
        val workingGraph = requireNotNull(snapshot.workingGraph)
        assertTrue(workingGraph.nodes.any { node -> node.id != "method:file-download" && node.id != "action:delete-file" && node.id != "terminal:return" && node.title == "if (fileExists(filePath))" })
        assertTrue(workingGraph.edges.none { edge -> edge.id == "edge:entry-delete" })
        assertTrue(workingGraph.edges.any { edge -> edge.label == "TRUE" && edge.toNodeId == "action:delete-file" })
        assertTrue(workingGraph.edges.any { edge -> edge.label == "FALSE" && edge.toNodeId == "terminal:return" })
    }

    fun testConfirmAuditCandidateChangeAdvancesDraftVersionAndPreservesDerivedArtifacts() {
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
        stateService.markGenerationPlan(
            GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "旧实现建议",
                warnings = emptyList(),
                promptPreview = "plan prompt",
            ),
        )
        stateService.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "flow-action:upload-condition",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    content = "class CommonController {}",
                ),
            ),
            warnings = listOf("旧代码 diff"),
            source = LlmResultSource.MOCK,
            promptPreview = "code prompt",
        )
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
        assertEquals(1L, snapshot.draftVersion)
        assertEquals("旧实现建议", snapshot.generationPlan?.summary)
        assertEquals(0L, snapshot.generationPlanDraftVersion)
        assertEquals(1, snapshot.generatedCodeDrafts.size)
        assertEquals(0L, snapshot.generatedCodeDraftVersion)
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

    fun testUnconfirmAuditCandidateChangeRebuildsWorkingGraphFromConfirmedEntries() {
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
                        graphPatch = GraphPatch(
                            summary = "补充上传路径调整说明",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-upload-note",
                                    action = GraphPatchAction.ADD_ANNOTATION,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "draft-note:change-upload-condition",
                                    title = "新增路径调整说明节点",
                                    node = GraphNode(
                                        id = "draft-note:change-upload-condition",
                                        type = NodeType.DOC_PAGE,
                                        title = "上传路径改为 /data/upload",
                                        doc = "原路径已废弃，固定改为 /data/upload。",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                    ),
                                ),
                                GraphPatchOperation(
                                    id = "patch-edge-upload-note",
                                    action = GraphPatchAction.ADD_EDGE,
                                    elementKind = GraphDiffElementKind.EDGE,
                                    elementId = "draft-edge:upload-condition->change-note",
                                    edge = GraphEdge(
                                        id = "draft-edge:upload-condition->change-note",
                                        type = EdgeType.LINKS_DOC,
                                        fromNodeId = "flow-action:upload-condition",
                                        toNodeId = "draft-note:change-upload-condition",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                    ),
                                ),
                            ),
                            addedNodeIds = listOf("draft-note:change-upload-condition"),
                            addedEdgeIds = listOf("draft-edge:upload-condition->change-note"),
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
        assertEquals(baseGraph, snapshot.workingGraph)
        assertEquals(false, snapshot.workingGraphDirty)
    }

    fun testUnconfirmAuditCandidateChangeAdvancesDraftVersionAndPreservesDerivedArtifacts() {
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

        val service = project.getService(LinkGraphProjectService::class.java)
        service.confirmAuditCandidateChange("change-upload-condition")
        stateService.markGenerationPlan(
            GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "确认后的实现建议",
                warnings = emptyList(),
                promptPreview = "plan prompt",
            ),
        )
        stateService.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "flow-action:upload-condition",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    content = "class CommonController {}",
                ),
            ),
            warnings = listOf("确认后的代码 diff"),
            source = LlmResultSource.MOCK,
            promptPreview = "code prompt",
        )

        service.unconfirmAuditCandidateChange("change-upload-condition")

        val snapshot = stateService.snapshot()
        assertEquals(2L, snapshot.draftVersion)
        assertTrue(snapshot.draftWorkbenchState.draftChanges.isEmpty())
        assertEquals("确认后的实现建议", snapshot.generationPlan?.summary)
        assertEquals(1L, snapshot.generationPlanDraftVersion)
        assertEquals(1, snapshot.generatedCodeDrafts.size)
        assertEquals(1L, snapshot.generatedCodeDraftVersion)
    }

    fun testConfirmAndUnconfirmCandidateSyncConfirmedIntentArtifactStore() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "flow-action:upload-condition",
                        type = NodeType.FLOW_ACTION,
                        title = "上传条件判断",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            "currentMethod",
        )
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
                        beforeState = "上传条件判断",
                        afterState = "上传条件判断（已调整）",
                        reason = "原条件错误。",
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
        val service = project.getService(LinkGraphProjectService::class.java)
        val artifactStore = project.getService(AgentArtifactStoreService::class.java).artifactStore

        service.confirmAuditCandidateChange("change-upload-condition")

        assertTrue(
            artifactStore.byType(ArtifactType.CONFIRMED_INTENT)
                .any { artifact -> artifact.artifactId == "confirmed-draft-change-upload-condition" },
        )

        service.unconfirmAuditCandidateChange("change-upload-condition")

        assertTrue(
            artifactStore.byType(ArtifactType.CONFIRMED_INTENT)
                .none { artifact -> artifact.artifactId == "confirmed-draft-change-upload-condition" },
        )
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
                        impactSummary = "会影响这条问答建议是否可直接进入草稿。",
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
        assertEquals(baseGraph, snapshot.workingGraph)
        assertEquals(false, snapshot.workingGraphDirty)
    }

    fun testConfirmAuditCandidateChangeRebuildsFlowchartViewWithModifiedDecisionNode() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-scope:delete-guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge:delete-guard->delete-guard",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "flow-scope:delete-guard",
                    toNodeId = "flow-scope:delete-guard",
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
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "调整删除判断",
                        targetNodeIds = listOf("flow-scope:delete-guard"),
                        beforeState = "if (delete)",
                        afterState = "if (delete == true)",
                        reason = "需要显式判断布尔值。",
                        impactSummary = "影响删除分支。",
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-scope:delete-guard")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        val snapshot = stateService.snapshot()
        assertEquals(
            listOf("flow-scope:delete-guard"),
            snapshot.flowchartView?.visibleGraph?.nodes?.map { it.id },
        )
        assertEquals(
            "if (delete == true)",
            snapshot.flowchartView?.visibleGraph?.nodes?.singleOrNull()?.title,
        )
        assertEquals(
            listOf("flow-edge:delete-guard->delete-guard"),
            snapshot.flowchartView?.visibleGraph?.edges?.map { it.id },
        )
    }

    fun testConfirmingMultipleAuditCandidateChangesPreservesFlowchartOrderAndLayout() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val selectedMethodSignature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void"
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = selectedMethodSignature,
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "ENTRY",
                        "flow.ownerMethod" to selectedMethodSignature,
                        "ui.x" to "32",
                        "ui.y" to "24",
                    ),
                ),
                GraphNode(
                    id = "scope:allow-download",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (!FileUtils.checkAllowDownload(fileName))",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to selectedMethodSignature,
                        "ui.x" to "192",
                        "ui.y" to "24",
                    ),
                ),
                GraphNode(
                    id = "scope:delete-file",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to selectedMethodSignature,
                        "ui.x" to "352",
                        "ui.y" to "24",
                    ),
                ),
                GraphNode(
                    id = "terminal:return",
                    type = NodeType.TERMINAL,
                    title = "return",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "TERMINAL",
                        "flow.ownerMethod" to selectedMethodSignature,
                        "ui.x" to "512",
                        "ui.y" to "24",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-allow",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:allow-download",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "edge:allow-delete",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:allow-download",
                    toNodeId = "scope:delete-file",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "edge:delete-return",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:delete-file",
                    toNodeId = "terminal:return",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        stateService.loadGraph(baseGraph, selectedMethodSignature)
        stateService.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这两条流程调整",
                answer = "先收紧 delete 判断，再补一个文件存在校验节点。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件",
                        targetNodeIds = listOf("scope:delete-file"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete))",
                        reason = "delete 为包装类型，需要显式布尔判断。",
                        impactSummary = "影响删除分支进入条件。",
                        claimType = "CODE_FACT",
                        graphPatch = GraphPatch(
                            summary = "更新删除判断节点",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-delete-guard",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:delete-file",
                                    node = GraphNode(
                                        id = "scope:delete-file",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "if (Boolean.TRUE.equals(delete))",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf(
                                            "flowchart.kind" to "DECISION",
                                            "flow.ownerMethod" to selectedMethodSignature,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到 delete 条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:delete-file")),
                            ),
                        ),
                    ),
                    CandidateDraftChange(
                        changeId = "change-file-exists-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "插入文件存在校验节点",
                        targetNodeIds = listOf("scope:delete-file"),
                        beforeState = "if (Boolean.TRUE.equals(delete)) -> return",
                        afterState = "if (Boolean.TRUE.equals(delete)) -> Files.exists(filePath) -> return",
                        reason = "删除前需要明确文件是否存在。",
                        impactSummary = "在删除判断后增加一个校验步骤。",
                        claimType = "CODE_FACT",
                        graphPatch = GraphPatch(
                            summary = "插入文件存在校验节点并重连边",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-add-exists-node",
                                    action = GraphPatchAction.ADD_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "draft:aaa-file-exists",
                                    node = GraphNode(
                                        id = "draft:aaa-file-exists",
                                        type = NodeType.FLOW_ACTION,
                                        title = "Files.exists(Path.of(filePath))",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf(
                                            "flowchart.kind" to "PROCESS",
                                            "flow.ownerMethod" to selectedMethodSignature,
                                        ),
                                    ),
                                ),
                                GraphPatchOperation(
                                    id = "patch-op-delete-delete-return",
                                    action = GraphPatchAction.DELETE_EDGE,
                                    elementKind = GraphDiffElementKind.EDGE,
                                    elementId = "edge:delete-return",
                                ),
                                GraphPatchOperation(
                                    id = "patch-op-add-delete-exists",
                                    action = GraphPatchAction.ADD_EDGE,
                                    elementKind = GraphDiffElementKind.EDGE,
                                    elementId = "edge:delete-exists",
                                    edge = GraphEdge(
                                        id = "edge:delete-exists",
                                        type = EdgeType.CONTROL_FLOW,
                                        fromNodeId = "scope:delete-file",
                                        toNodeId = "draft:aaa-file-exists",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                    ),
                                ),
                                GraphPatchOperation(
                                    id = "patch-op-add-exists-return",
                                    action = GraphPatchAction.ADD_EDGE,
                                    elementKind = GraphDiffElementKind.EDGE,
                                    elementId = "edge:exists-return",
                                    edge = GraphEdge(
                                        id = "edge:exists-return",
                                        type = EdgeType.CONTROL_FLOW,
                                        fromNodeId = "draft:aaa-file-exists",
                                        toNodeId = "terminal:return",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                    ),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-file-exists-guard",
                                claim = "当前删除分支直接返回，没有额外文件存在校验。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:delete-file")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.confirmAuditCandidateChange("change-delete-guard")
        service.confirmAuditCandidateChange("change-file-exists-guard")

        val snapshot = stateService.snapshot()
        val expectedNodeOrder = listOf(
            "method:file-download",
            "scope:allow-download",
            "scope:delete-file",
            "terminal:return",
            "draft:aaa-file-exists",
        )
        assertEquals(expectedNodeOrder, snapshot.workingGraph?.nodes?.map { it.id })
        assertEquals(expectedNodeOrder, snapshot.flowchartView?.visibleGraph?.nodes?.map { it.id })
        assertEquals(
            "if (Boolean.TRUE.equals(delete))",
            snapshot.flowchartView?.visibleGraph?.nodes?.firstOrNull { it.id == "scope:delete-file" }?.title,
        )
        assertEquals(
            listOf("edge:entry-allow", "edge:allow-delete", "edge:delete-exists", "edge:exists-return"),
            snapshot.flowchartView?.visibleGraph?.edges?.map { it.id },
        )
        assertEquals(32.0, snapshot.layoutState.positions["method:file-download"]?.x)
        assertEquals(24.0, snapshot.layoutState.positions["method:file-download"]?.y)
        assertEquals(352.0, snapshot.layoutState.positions["scope:delete-file"]?.x)
        assertEquals(24.0, snapshot.layoutState.positions["scope:delete-file"]?.y)
    }

    fun testConfirmAuditCandidateChangeInFlowchartModeRebuildsFromFlowchartBaseInsteadOfFactGraph() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val selectedMethodSignature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void"
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = selectedMethodSignature,
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val flowchartVisibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to selectedMethodSignature,
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge:entry->delete-guard",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:file-download-if",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val flowchartFullGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = selectedMethodSignature,
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to selectedMethodSignature,
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge:entry->delete-guard",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:file-download-if",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        stateService.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartVisibleGraph,
                fullGraph = flowchartFullGraph,
                anchorNodeId = "scope:file-download-if",
                selectedMethodSignature = selectedMethodSignature,
                displayName = "CommonController.fileDownload",
                feedbackLevel = GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已加载流程图",
                projectionStats = AnalysisProjectionStats(),
                factGraphView = FactGraphViewDocument(
                    visibleGraph = factGraph,
                    fullGraph = factGraph,
                    anchorNodeId = "method:file-download",
                ),
                flowchartView = FlowchartViewDocument(
                    visibleGraph = flowchartVisibleGraph,
                    fullGraph = flowchartFullGraph,
                    anchorNodeId = "scope:file-download-if",
                ),
                resourceRelationView = ResourceRelationViewDocument(),
            ),
            source = "currentSubject",
        )
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条逻辑调整",
                answer = "建议收紧删除条件。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件",
                        targetNodeIds = listOf("scope:file-download-if"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete))",
                        reason = "delete 为包装类型，需要显式布尔判断。",
                        impactSummary = "影响删除分支。",
                        claimType = "CODE_FACT",
                        graphPatch = GraphPatch(
                            summary = "更新删除判断节点",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-delete-guard",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:file-download-if",
                                    node = GraphNode(
                                        id = "scope:file-download-if",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "if (Boolean.TRUE.equals(delete))",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf("flowchart.kind" to "DECISION"),
                                    ),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        val snapshot = stateService.snapshot()
        assertEquals(
            listOf("method:file-download", "scope:file-download-if"),
            snapshot.workingGraph?.nodes?.map { it.id }?.sorted(),
        )
        assertEquals(
            "if (Boolean.TRUE.equals(delete))",
            snapshot.workingGraph?.nodes?.firstOrNull { it.id == "scope:file-download-if" }?.title,
        )
        assertEquals(
            listOf("flow-edge:entry->delete-guard"),
            snapshot.workingGraph?.edges?.map { it.id },
        )
        assertEquals(
            listOf("flow-edge:entry->delete-guard"),
            snapshot.flowchartView?.visibleGraph?.edges?.map { it.id },
        )
    }

    fun testConfirmAuditCandidateChangeUsesNormalizedDecisionPatchInsteadOfTryScopePatch() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "scope:file-download-try",
                    type = NodeType.FLOW_SCOPE,
                    title = "try",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "SCOPE",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条逻辑调整",
                answer = "建议收紧删除条件。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件并在删除前校验文件存在",
                        targetNodeIds = listOf("scope:file-download-if"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                        reason = "delete 为包装类型，删除前缺少文件存在校验。",
                        impactSummary = "删除分支需要更严格的进入条件。",
                        claimType = "STRUCTURAL_SUGGESTION",
                        graphPatch = GraphPatch(
                            summary = "更新删除分支条件",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-if",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:file-download-if",
                                    title = "更新删除判断节点",
                                    summary = "收紧删除条件",
                                    node = GraphNode(
                                        id = "scope:file-download-if",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                    ),
                                    metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        val snapshot = stateService.snapshot()
        val visibleNodesById = snapshot.flowchartView?.visibleGraph?.nodes?.associateBy { it.id }.orEmpty()
        assertEquals("try", visibleNodesById["scope:file-download-try"]?.title)
        assertEquals(
            "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            visibleNodesById["scope:file-download-if"]?.title,
        )
    }

    fun testConfirmAuditCandidateChangeNormalizesStoredCandidateFromTryScopeToDecisionNode() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "scope:file-download-try",
                    type = NodeType.FLOW_SCOPE,
                    title = "try",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "SCOPE",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条逻辑调整",
                answer = "建议收紧删除条件。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件并在删除前校验文件存在",
                        targetNodeIds = listOf("scope:file-download-try"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                        reason = "delete 为包装类型，删除前缺少文件存在校验。",
                        impactSummary = "删除分支需要更严格的进入条件。",
                        claimType = "STRUCTURAL_SUGGESTION",
                        graphPatch = GraphPatch(
                            summary = "更新当前 try 作用域节点中的删除分支逻辑",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-try",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:file-download-try",
                                    title = "更新 try 作用域节点",
                                    summary = "收紧删除条件",
                                    node = GraphNode(
                                        id = "scope:file-download-try",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "try",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                    ),
                                    metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        val snapshot = stateService.snapshot()
        val storedCandidate = snapshot.auditResult?.candidateChanges?.singleOrNull()
        assertEquals(listOf("scope:file-download-if"), storedCandidate?.targetNodeIds)
        assertEquals("scope:file-download-if", storedCandidate?.graphPatch?.operations?.singleOrNull()?.elementId)
        val visibleNodesById = snapshot.flowchartView?.visibleGraph?.nodes?.associateBy { it.id }.orEmpty()
        assertEquals("try", visibleNodesById["scope:file-download-try"]?.title)
        assertEquals(
            "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            visibleNodesById["scope:file-download-if"]?.title,
        )
    }

    fun testConfirmAuditCandidateChangeRejectsCandidateFromAnotherSelectedMethod() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val uploadSignature = "CommonController.uploadFile(org.springframework.web.multipart.MultipartFile):void"
        val downloadSignature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void"
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = uploadSignature,
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = downloadSignature,
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to downloadSignature,
                    ),
                ),
            ),
        )
        stateService.loadGraph(baseGraph, "currentMethod")
        stateService.pushSelectedMethod(uploadSignature)
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "这里是否需要调整删除逻辑？",
                answer = "建议先收紧 fileDownload 里的 delete 判断。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件",
                        targetNodeIds = listOf("scope:file-download-if"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete))",
                        reason = "delete 为包装类型，需要显式布尔判断。",
                        impactSummary = "影响 fileDownload 删除分支。",
                        claimType = "CODE_FACT",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-delete-guard",
                                targetNodeId = "scope:file-download-if",
                                filePath = "src/main/java/com/example/CommonController.java",
                                language = "JAVA",
                                symbolKind = "FLOW_SCOPE",
                                symbolSignature = downloadSignature,
                                startLine = 48,
                                endLine = 50,
                                allowedChangeKinds = listOf("REPLACE_CONDITION_EXPRESSION"),
                                supportingFindingIds = listOf("finding-delete-guard"),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "当前源码里直接能看到 fileDownload 的删除条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val entry = project.getService(LinkGraphProjectService::class.java)
            .confirmAuditCandidateChange("change-delete-guard")

        assertNull(entry)
        val snapshot = stateService.snapshot()
        assertEquals(uploadSignature, snapshot.selectedMethodSignature)
        assertTrue(snapshot.draftWorkbenchState.draftChanges.isEmpty())
        assertEquals(
            CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            snapshot.auditResult?.candidateChanges?.singleOrNull()?.status,
        )
        assertEquals(baseGraph, snapshot.workingGraph)
        assertEquals(false, snapshot.workingGraphDirty)
    }
}
