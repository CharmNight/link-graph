package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.outcome.AnalysisProjectionStats
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationStep
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphEditorStateServiceTest {
    @Test
    fun loadAnalysisOutcome同时更新展示模式与可见图() {
        val service = GraphEditorStateService()
        val visibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val fullGraph = GraphDocument(
            nodes = visibleGraph.nodes + GraphNode(
                id = "method:order-repository-save",
                type = NodeType.METHOD,
                title = "OrderRepository.save",
                signature = "com.example.OrderRepository.save(com.example.Order):void",
                sourceTag = GraphSourceTag.FACT,
            ),
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FACT_GRAPH,
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = "method:order-service-place",
                selectedMethodSignature = "com.example.OrderService.place(java.lang.String):void",
                displayName = "OrderService.place",
                feedbackLevel = GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已加载当前主体分析：OrderService.place",
                projectionStats = AnalysisProjectionStats(),
                factGraphView = FactGraphViewDocument(
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                    anchorNodeId = "method:order-service-place",
                ),
                flowchartView = FlowchartViewDocument(
                    visibleGraph = GraphDocument(
                        nodes = listOf(
                            GraphNode(
                                id = "method:order-service-place",
                                type = NodeType.METHOD,
                                title = "OrderService.place",
                                sourceTag = GraphSourceTag.FACT,
                                metadata = mapOf("flowchart.kind" to "ENTRY"),
                            ),
                        ),
                    ),
                    fullGraph = GraphDocument(),
                    anchorNodeId = "method:order-service-place",
                ),
                resourceRelationView = ResourceRelationViewDocument(
                    visibleGraph = GraphDocument(
                        nodes = listOf(
                            GraphNode(
                                id = "sql:order-repository-save",
                                type = NodeType.SQL,
                                title = "order_mapper.xml#save",
                                sourceTag = GraphSourceTag.FACT,
                                metadata = mapOf("resource.lane" to "DATA"),
                            ),
                        ),
                    ),
                    fullGraph = GraphDocument(),
                    anchorNodeId = "sql:order-repository-save",
                ),
            ),
            source = "currentSubject",
        )

        val snapshot = service.snapshot()
        assertEquals(AnalysisDisplayMode.FACT_GRAPH, snapshot.analysisDisplayMode)
        assertEquals("currentSubject", snapshot.lastGraphSource)
        assertEquals(visibleGraph, snapshot.visibleGraph)
        assertEquals(fullGraph, snapshot.referenceFactGraph)
        assertEquals("method:order-service-place", snapshot.selectedNodeId)
        assertEquals("com.example.OrderService.place(java.lang.String):void", snapshot.selectedMethodSignature)
        assertEquals("已加载当前主体分析：OrderService.place", snapshot.operationFeedback?.message)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals("method:order-service-place", snapshot.factGraphView?.anchorNodeId)
        assertEquals("method:order-service-place", snapshot.flowchartView?.anchorNodeId)
        assertEquals("sql:order-repository-save", snapshot.resourceRelationView?.anchorNodeId)
    }

    @Test
    fun layoutChangePreservesGenerationArtifactsAndDoesNotMarkSemanticDirty() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val plan = GenerationPlan(
            source = GenerationPlanSource.MOCK,
            summary = "补一个 DTO 并串起服务调用。",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-1",
                    title = "新增 OrderDraftDto",
                    description = "生成 DTO 草稿文件。",
                    risk = SyncPreviewRisk.LOW,
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                ),
            ),
            warnings = emptyList(),
            promptPreview = "plan prompt preview",
        )

        service.loadGraph(factGraph, "currentMethod")
        service.markGenerationPlan(plan)
        val beforeLayoutChange = service.snapshot()
        service.markLayoutChanged(
            mapOf(
                "method:order-service-place" to GraphLayoutPosition(
                    x = 520.0,
                    y = 240.0,
                ),
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(plan, snapshot.generationPlan)
        assertEquals(false, snapshot.workingGraphDirty)
        assertEquals(beforeLayoutChange.semanticRevision, snapshot.semanticRevision)
        assertEquals(beforeLayoutChange.layoutRevision + 1, snapshot.layoutRevision)
        assertEquals(beforeLayoutChange.snapshotRevision + 1, snapshot.snapshotRevision)
    }

    @Test
    fun loadGraph也会生成三视图文档而不是把前端留在兼容分支() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")

        val snapshot = service.snapshot()
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals(graph, snapshot.factGraphView?.visibleGraph)
        assertEquals(graph, snapshot.flowchartView?.visibleGraph)
        assertEquals(graph, snapshot.resourceRelationView?.visibleGraph)
    }

    @Test
    fun importMermaidUpdatesDesignBaselineWithoutReplacingCurrentDraftGraph() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val importedBaseline = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "design:order-draft-dto",
                    type = NodeType.CLASS,
                    title = "OrderDraftDto",
                    sourceTag = GraphSourceTag.DESIGN_BASELINE,
                ),
            ),
        )

        service.loadGraph(factGraph, "currentMethod")
        service.importMermaid("graph TD\nA-->B", importedBaseline)

        val snapshot = service.snapshot()
        assertEquals(factGraph, snapshot.workingGraph)
        assertEquals(factGraph, snapshot.referenceFactGraph)
        assertEquals(importedBaseline, snapshot.designBaselineGraph)
        assertEquals(factGraph, snapshot.visibleGraph)
        assertEquals("graph TD\nA-->B", snapshot.importedMermaid)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
    }

    @Test
    fun markGraphChangedKeepsImportedDesignBaselineGraph() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val importedBaseline = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "design:order-draft-dto",
                    type = NodeType.CLASS,
                    title = "OrderDraftDto",
                    sourceTag = GraphSourceTag.DESIGN_BASELINE,
                ),
            ),
        )
        val draftGraph = GraphDocument(
            nodes = factGraph.nodes + GraphNode(
                id = "note:manual-fallback",
                type = NodeType.CLASS,
                title = "ManualFallback",
                sourceTag = GraphSourceTag.DRAFT_MANUAL,
            ),
        )

        service.loadGraph(factGraph, "currentMethod")
        service.importMermaid("graph TD\nA-->B", importedBaseline)
        service.markGraphChanged(draftGraph)

        val snapshot = service.snapshot()
        assertEquals(importedBaseline, snapshot.designBaselineGraph)
    }

    @Test
    fun markGraphChangedOnlyMovesDraftLayer() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val draftGraph = GraphDocument(
            nodes = factGraph.nodes + GraphNode(
                id = "note:manual-fallback",
                type = NodeType.CLASS,
                title = "ManualFallback",
                sourceTag = GraphSourceTag.DRAFT_MANUAL,
            ),
        )

        service.loadGraph(factGraph, "currentMethod")
        service.markGraphChanged(draftGraph)

        val snapshot = service.snapshot()
        assertEquals(draftGraph, snapshot.workingGraph)
        assertEquals(factGraph, snapshot.referenceFactGraph)
        assertEquals(draftGraph, snapshot.visibleGraph)
        assertNotNull(snapshot.referenceFactGraph)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals(1, snapshot.referenceFactGraph!!.nodes.size)
        assertEquals(2, snapshot.workingGraph!!.nodes.size)
    }

    @Test
    fun markGraphChanged在流程图模式下只更新流程图文档() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:fact-anchor",
                    type = NodeType.METHOD,
                    title = "FactAnchor",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val flowchartGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:flow-entry",
                    type = NodeType.METHOD,
                    title = "FlowEntry",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
            ),
        )
        val resourceGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "sql:order-save",
                    type = NodeType.SQL,
                    title = "order_mapper.xml#save",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("resource.lane" to "DATA"),
                ),
            ),
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartGraph,
                fullGraph = factGraph,
                anchorNodeId = "method:flow-entry",
                selectedMethodSignature = "com.example.OrderService.place():void",
                displayName = "OrderService.place",
                feedbackLevel = GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已加载流程图",
                projectionStats = AnalysisProjectionStats(),
                factGraphView = FactGraphViewDocument(
                    visibleGraph = factGraph,
                    fullGraph = factGraph,
                    anchorNodeId = "method:fact-anchor",
                ),
                flowchartView = FlowchartViewDocument(
                    visibleGraph = flowchartGraph,
                    fullGraph = flowchartGraph,
                    anchorNodeId = "method:flow-entry",
                ),
                resourceRelationView = ResourceRelationViewDocument(
                    visibleGraph = resourceGraph,
                    fullGraph = resourceGraph,
                    anchorNodeId = "sql:order-save",
                ),
            ),
            source = "currentSubject",
        )

        val editedFlowchartGraph = GraphDocument(
            nodes = flowchartGraph.nodes + GraphNode(
                id = "design-note:1",
                type = NodeType.DOC_PAGE,
                title = "FlowNote",
                sourceTag = GraphSourceTag.DRAFT_MANUAL,
            ),
        )

        service.markGraphChanged(editedFlowchartGraph)

        val snapshot = service.snapshot()
        assertEquals(editedFlowchartGraph, snapshot.visibleGraph)
        assertEquals(editedFlowchartGraph, snapshot.flowchartView?.visibleGraph)
        assertEquals(factGraph, snapshot.factGraphView?.visibleGraph)
        assertEquals(resourceGraph, snapshot.resourceRelationView?.visibleGraph)
        assertEquals(true, snapshot.workingGraphDirty)
    }

    @Test
    fun switchAnalysisDisplayMode在脏编辑态下复用当前三视图文档() {
        val service = GraphEditorStateService()
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:fact-anchor",
                    type = NodeType.METHOD,
                    title = "FactAnchor",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val flowchartGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:flow-entry",
                    type = NodeType.METHOD,
                    title = "FlowEntry",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
            ),
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartGraph,
                fullGraph = factGraph,
                anchorNodeId = "method:flow-entry",
                selectedMethodSignature = "com.example.OrderService.place():void",
                displayName = "OrderService.place",
                feedbackLevel = GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已加载流程图",
                projectionStats = AnalysisProjectionStats(),
                factGraphView = FactGraphViewDocument(
                    visibleGraph = factGraph,
                    fullGraph = factGraph,
                    anchorNodeId = "method:fact-anchor",
                ),
                flowchartView = FlowchartViewDocument(
                    visibleGraph = flowchartGraph,
                    fullGraph = flowchartGraph,
                    anchorNodeId = "method:flow-entry",
                ),
                resourceRelationView = ResourceRelationViewDocument(),
            ),
            source = "currentSubject",
        )

        val editedFlowchartGraph = GraphDocument(
            nodes = flowchartGraph.nodes + GraphNode(
                id = "design:1",
                type = NodeType.METHOD,
                title = "ManualFlowStep",
                sourceTag = GraphSourceTag.DRAFT_MANUAL,
            ),
        )
        service.markGraphChanged(editedFlowchartGraph)

        service.switchAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)
        var snapshot = service.snapshot()
        assertEquals(AnalysisDisplayMode.FACT_GRAPH, snapshot.analysisDisplayMode)
        assertEquals(factGraph, snapshot.visibleGraph)

        service.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        snapshot = service.snapshot()
        assertEquals(AnalysisDisplayMode.FLOWCHART, snapshot.analysisDisplayMode)
        assertEquals(editedFlowchartGraph, snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.id == "design:1" } == true)
    }

    @Test
    fun markGraphBeautificationResultStoresResultAndGraphReloadClearsIt() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val result = GraphBeautificationResult(
            source = LlmResultSource.MOCK,
            granularity = StepGranularity.BUSINESS,
            steps = listOf(
                GraphBeautificationStep(
                    stepId = "step-place-draft",
                    title = "当前方法内部",
                    granularity = StepGranularity.BUSINESS,
                    kind = StepKind.BUSINESS_ACTION,
                    description = "先判断参数，再调用 placeDraft。",
                    evidence = listOf(
                        ResultEvidenceFinding(
                            id = "direct-place-draft",
                            claim = "当前方法直接调用了 placeDraft。",
                            evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                            references = listOf(
                                ResultEvidenceReference(
                                    nodeId = "method:order-service-place",
                                    filePath = "/tmp/OrderService.java",
                                    startLine = 12,
                                    endLine = 14,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            promptPreview = "beautification prompt preview",
            warnings = listOf("当前仍为占位实现。"),
        )

        service.loadGraph(graph, "currentMethod")
        service.markGraphBeautificationResult(result)

        var snapshot = service.snapshot()
        assertEquals(result, snapshot.graphBeautificationResult)
        assertEquals("graphBeautificationResult", snapshot.lastMessageType)
        assertEquals(ResultEvidenceLevel.DIRECT_SOURCE, snapshot.graphBeautificationResult?.steps?.single()?.evidence?.single()?.evidenceLevel)

        service.loadGraph(graph, "currentMethod")

        snapshot = service.snapshot()
        assertEquals(null, snapshot.graphBeautificationResult)
    }

    @Test
    fun markDraftPatchApplyResultStoresReportAndGraphChangeClearsIt() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val report = DraftPatchApplyResult(
            summary = "已应用 1 条草稿图变更。",
            appliedOperationCount = 1,
            appliedNodeIds = listOf("method:order-service-place"),
            focusNodeId = "method:order-service-place",
            appliedTargets = listOf("OrderService.place"),
        )

        service.loadGraph(graph, "currentMethod")
        service.markDraftPatchApplyResult(report)

        var snapshot = service.snapshot()
        assertEquals(report, snapshot.lastDraftPatchApplyResult)
        assertEquals("draftPatchApplied", snapshot.lastMessageType)

        service.markGraphChanged(graph)

        snapshot = service.snapshot()
        assertEquals(null, snapshot.lastDraftPatchApplyResult)
    }

    @Test
    fun beginAuditRequestClearsPreviousAuditResultButKeepsGraphs() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")
        service.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "旧问题",
                answer = "旧答案",
                promptPreview = "old prompt",
            ),
        )

        service.beginAuditRequest()

        val snapshot = service.snapshot()
        assertEquals(graph, snapshot.visibleGraph)
        assertEquals(graph, snapshot.workingGraph)
        assertEquals(null, snapshot.auditResult)
        assertEquals("requestAudit", snapshot.lastMessageType)
    }

    @Test
    fun 异步请求状态变化也会推进快照版本号() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val beautificationResult = GraphBeautificationResult(
            source = LlmResultSource.MOCK,
            granularity = StepGranularity.BUSINESS,
            steps = listOf(
                GraphBeautificationStep(
                    stepId = "step-validate-before-write",
                    title = "当前方法内部",
                    granularity = StepGranularity.BUSINESS,
                    kind = StepKind.BUSINESS_ACTION,
                    description = "先校验参数，再调用下游。",
                ),
            ),
            promptPreview = "beautification prompt",
            warnings = emptyList(),
        )

        service.loadGraph(graph, "currentMethod")
        val afterLoad = service.snapshot()

        service.beginGraphBeautificationRequest()
        val afterBeginBeautification = service.snapshot()
        assertEquals(afterLoad.snapshotRevision + 1, afterBeginBeautification.snapshotRevision)

        service.markGraphBeautificationResult(beautificationResult)
        val afterBeautificationResult = service.snapshot()
        assertEquals(afterBeginBeautification.snapshotRevision + 1, afterBeautificationResult.snapshotRevision)

        service.beginAuditRequest()
        val afterBeginAudit = service.snapshot()
        assertEquals(afterBeautificationResult.snapshotRevision + 1, afterBeginAudit.snapshotRevision)

        service.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请审计当前链路",
                answer = "当前链路缺少兜底说明。",
                promptPreview = "audit prompt",
            ),
        )
        val afterAuditResult = service.snapshot()
        assertEquals(afterBeginAudit.snapshotRevision + 1, afterAuditResult.snapshotRevision)
    }

    @Test
    fun 操作反馈变化也会推进快照版本号() {
        val service = GraphEditorStateService()
        val initial = service.snapshot()

        service.markOperationFeedback(
            level = GraphEditorStateService.OperationFeedbackLevel.INFO,
            message = "正在生成链路讲解，请稍候。",
        )

        val snapshot = service.snapshot()
        assertEquals(initial.snapshotRevision + 1, snapshot.snapshotRevision)
        assertEquals("operationFeedback", snapshot.lastMessageType)
        assertEquals("正在生成链路讲解，请稍候。", snapshot.operationFeedback?.message)
    }

    @Test
    fun markGenerationPlanClearsPreviousDraftsAndMarksRequestStateSucceeded() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")
        service.markGeneratedCodeDrafts(
            drafts = listOf(
                com.charmnight.linkgraph.codegen.GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\nclass OrderDraftDto {}",
                ),
            ),
            warnings = listOf("旧草稿"),
            source = LlmResultSource.MOCK,
            promptPreview = "old prompt",
        )
        service.beginGenerationPlanRequest()

        val plan = GenerationPlan(
            source = GenerationPlanSource.MOCK,
            summary = "生成新的 DTO 计划。",
            warnings = emptyList(),
            promptPreview = "new prompt",
        )
        service.markGenerationPlan(plan)

        val snapshot = service.snapshot()
        assertEquals(plan, snapshot.generationPlan)
        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanRequestState.phase)
        assertEquals(null, snapshot.generationPlanRequestState.errorMessage)
        assertEquals(emptyList(), snapshot.generatedCodeDrafts)
        assertEquals(emptyList(), snapshot.generatedCodeDraftWarnings)
        assertEquals(null, snapshot.generatedCodeDraftSource)
        assertEquals(null, snapshot.generatedCodeDraftPromptPreview)
        assertEquals("requestGenerationPlan", snapshot.lastMessageType)
    }

    @Test
    fun beginAndFailDiffReviewRequestTracksExplicitRequestState() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")
        service.beginDiffReviewRequest()

        var snapshot = service.snapshot()
        assertEquals(GraphEditorStateService.AsyncRequestPhase.RUNNING, snapshot.diffReviewRequestState.phase)
        assertEquals(null, snapshot.diffReviewRequestState.errorMessage)
        assertEquals(null, snapshot.diffReviewResult)

        service.markDiffReviewRequestFailed("差异分析失败：HTTP 503")

        snapshot = service.snapshot()
        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.diffReviewRequestState.phase)
        assertEquals("差异分析失败：HTTP 503", snapshot.diffReviewRequestState.errorMessage)
        assertEquals(null, snapshot.diffReviewResult)
    }

    @Test
    fun markGraphChangedClearsDerivedAuditAndPreviewState() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")
        service.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "旧问题",
                answer = "旧答案",
                promptPreview = "old prompt",
            ),
        )
        service.markDiffReviewResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "旧差异问题",
                answer = "旧差异答案",
                promptPreview = "old diff prompt",
            ),
        )
        service.markDraftPatchPreview(
            GraphPatch(
                summary = "旧草稿预览",
                operations = emptyList(),
                addedNodeIds = listOf("doc:old-preview"),
            ),
        )

        service.markGraphChanged(graph)

        val snapshot = service.snapshot()
        assertEquals(null, snapshot.auditResult)
        assertEquals(null, snapshot.diffReviewResult)
        assertEquals(null, snapshot.draftPatchPreview)
        assertEquals("graphChanged", snapshot.lastMessageType)
    }
}
