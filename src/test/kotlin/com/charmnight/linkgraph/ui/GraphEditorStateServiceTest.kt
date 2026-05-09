package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

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
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphEditorStateServiceTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun stateServiceRemovesLegacyPartialViewMutationEntrypoints() {
        val serviceSource = Files.readString(
            root.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt"),
        )
        val modelSource = Files.readString(
            root.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt"),
        )

        assertTrue(serviceSource.contains("GraphEditorStateStore"))
        assertTrue(serviceSource.contains("fun markGraphChanged("))
        assertTrue(modelSource.contains("val currentSceneId: GraphSceneId"))
        assertTrue(modelSource.contains("val sceneStates: Map<GraphSceneId, GraphSceneState>"))
        assertTrue(modelSource.contains("val workspaceGraph: GraphDocument"))
        assertTrue(modelSource.contains("val workspaceBaseGraph: GraphDocument"))
        assertTrue(modelSource.contains("val semanticFactGraph: GraphDocument"))
        kotlin.test.assertFalse(serviceSource.contains("fun markViewGraphChanged("))
        kotlin.test.assertFalse(serviceSource.contains("fun markWorkingGraphChanged("))
    }

    @Test
    fun markOperationFeedbackCanPreserveExistingLastMessageType() {
        val service = GraphEditorStateService()

        service.asyncRequests.markGraphBeautificationResult(GraphBeautificationResult(source = LlmResultSource.LOCAL_RULE))
        service.workbench.markOperationFeedback(
            level = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
            message = "链路讲解完成，已更新步骤列表",
            preserveLastMessageType = true,
        )

        val snapshot = service.snapshot()
        assertEquals("graphBeautificationResult", snapshot.lastMessageType)
        assertEquals("链路讲解完成，已更新步骤列表", snapshot.operationFeedback?.message)
    }

    @Test
    fun markGraphChanged保留前端上报后的现有节点布局() {
        val service = GraphEditorStateService()
        val initialGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "ui.x" to "120",
                        "ui.y" to "96",
                    ),
                ),
            ),
        )

        service.loadGraph(initialGraph, "currentMethod")
        service.markLayoutChanged(
            mapOf(
                "method:order-service-place" to GraphLayoutPosition(
                    x = 520.0,
                    y = 240.0,
                ),
            ),
        )

        service.markGraphChanged(
            initialGraph.copy(
                nodes = initialGraph.nodes.map { node ->
                    node.copy(metadata = node.metadata - "ui.x" - "ui.y")
                },
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(520.0, snapshot.layoutState.positions["method:order-service-place"]?.x)
        assertEquals(240.0, snapshot.layoutState.positions["method:order-service-place"]?.y)
    }

    @Test
    fun markDraftWorkbenchStateStoresUnifiedDraftEntries() {
        val service = GraphEditorStateService()

        service.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-1",
                        kind = DraftEntryKind.CHANGE,
                        sourceChangeId = "change-upload-condition",
                    ),
                ),
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals("change-upload-condition", snapshot.draftWorkbenchState.draftChanges.first().sourceChangeId)
        assertEquals("draftWorkbenchState", snapshot.lastMessageType)
    }

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
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
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
        assertEquals(fullGraph, snapshot.visibleGraph)
        assertEquals(fullGraph, snapshot.workingGraph)
        assertEquals(fullGraph, snapshot.referenceFactGraph)
        assertEquals(GraphSceneId.WORKSPACE_FACT, snapshot.currentSceneId)
        assertEquals("method:order-service-place", snapshot.selectedNodeId)
        assertEquals("com.example.OrderService.place(java.lang.String):void", snapshot.selectedMethodSignature)
        assertEquals("已加载当前主体分析：OrderService.place", snapshot.operationFeedback?.message)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals("method:order-service-place", snapshot.factGraphView?.anchorNodeId)
        assertEquals("method:order-service-place", snapshot.flowchartView?.anchorNodeId)
        assertEquals("method:order-service-place", snapshot.resourceRelationView?.anchorNodeId)
    }

    @Test
    fun loadAnalysisOutcome在流程图模式下保留完整工作图并单独维护事实基线() {
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
        val flowchartVisibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (!allowed)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )
        val flowchartFullGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "action:guard-condition",
                    type = NodeType.FLOW_ACTION,
                    title = "!checkAllowDownload(fileName)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "PROCESS",
                        "flow.kind" to "CONDITION",
                    ),
                ),
                GraphNode(
                    id = "scope:guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (!allowed)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartVisibleGraph,
                fullGraph = flowchartFullGraph,
                anchorNodeId = "scope:guard",
                selectedMethodSignature = "com.example.OrderService.place():void",
                displayName = "OrderService.place",
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已加载流程图",
                projectionStats = AnalysisProjectionStats(),
                factGraphView = FactGraphViewDocument(
                    visibleGraph = factGraph,
                    fullGraph = factGraph,
                    anchorNodeId = "method:fact-anchor",
                ),
                flowchartView = FlowchartViewDocument(
                    visibleGraph = flowchartVisibleGraph,
                    fullGraph = flowchartFullGraph,
                    anchorNodeId = "scope:guard",
                ),
                resourceRelationView = ResourceRelationViewDocument(),
            ),
            source = "currentSubject",
        )

        val snapshot = service.snapshot()
        assertEquals(GraphSceneId.WORKSPACE_FLOWCHART, snapshot.currentSceneId)
        assertEquals(factGraph, snapshot.referenceFactGraph)
        assertEquals(flowchartFullGraph, snapshot.factGraphView.fullGraph)
        assertEquals(flowchartFullGraph, snapshot.workingGraph)
        assertEquals(flowchartFullGraph, snapshot.referenceWorkingGraph)
        assertEquals(flowchartFullGraph, snapshot.flowchartView.fullGraph)
    }

    @Test
    fun loadAnalysisOutcome在同方法重载时保留已确认草稿并重新应用到新流程图底图() {
        val service = GraphEditorStateService()
        val selectedMethodSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void"
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
                    metadata = mapOf("flowchart.kind" to "DECISION"),
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
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )
        val confirmedDraftState = DraftWorkbenchState(
            draftChanges = listOf(
                DraftWorkbenchEntry(
                    entryId = "draft-change-delete-guard",
                    kind = DraftEntryKind.CHANGE,
                    sourceChangeId = "change-delete-guard",
                    targetNodeIds = listOf("scope:file-download-if"),
                    beforeState = "if (delete)",
                    afterState = "if (delete == true)",
                    graphPatch = GraphPatch(
                        summary = "更新删除判断",
                        operations = listOf(
                            GraphPatchOperation(
                                id = "patch-op-update-delete-guard",
                                action = GraphPatchAction.UPDATE_NODE,
                                elementKind = GraphDiffElementKind.NODE,
                                elementId = "scope:file-download-if",
                                node = GraphNode(
                                    id = "scope:file-download-if",
                                    type = NodeType.FLOW_SCOPE,
                                    title = "if (delete == true)",
                                    sourceTag = GraphSourceTag.DRAFT_AI,
                                    metadata = mapOf("flowchart.kind" to "DECISION"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartVisibleGraph,
                fullGraph = flowchartFullGraph,
                anchorNodeId = "scope:file-download-if",
                selectedMethodSignature = selectedMethodSignature,
                displayName = "CommonController.fileDownload",
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
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
        service.workbench.markDraftWorkbenchState(confirmedDraftState, advanceDraftVersion = true)
        service.markWorkingGraphChanged(
            graph = flowchartFullGraph.copy(
                nodes = flowchartFullGraph.nodes.map { node ->
                    if (node.id == "scope:file-download-if") {
                        node.copy(title = "if (delete == true)", sourceTag = GraphSourceTag.DRAFT_AI)
                    } else {
                        node
                    }
                },
            ),
            selectedMethodSignature = selectedMethodSignature,
            workingGraphDirty = true,
        )

        service.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = flowchartVisibleGraph,
                fullGraph = flowchartFullGraph,
                anchorNodeId = "scope:file-download-if",
                selectedMethodSignature = selectedMethodSignature,
                displayName = "CommonController.fileDownload",
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                feedbackMessage = "已重新加载流程图",
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

        val snapshot = service.snapshot()
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals(1L, snapshot.draftVersion)
        assertEquals(true, snapshot.workingGraphDirty)
        assertEquals(
            "if (delete == true)",
            snapshot.workingGraph?.nodes?.firstOrNull { it.id == "scope:file-download-if" }?.title,
        )
        assertEquals(
            "if (delete == true)",
            snapshot.flowchartView?.visibleGraph?.nodes?.firstOrNull { it.id == "scope:file-download-if" }?.title,
        )
        assertEquals(
            factGraph,
            snapshot.referenceFactGraph,
        )
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
            source = GenerationPlanSource.LOCAL_RULE,
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
        service.asyncRequests.markGenerationPlan(plan)
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
    fun markGraphChanged在流程图模式下更新当前流程图文档并保留其他语义视图() {
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
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
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
        assertEquals(listOf("method:flow-entry", "design-note:1"), snapshot.visibleGraph?.nodes?.map { it.id })
        assertEquals("READABLE", snapshot.visibleGraph?.nodes?.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertEquals(listOf("method:flow-entry", "design-note:1"), snapshot.flowchartView?.visibleGraph?.nodes?.map { it.id })
        assertEquals(editedFlowchartGraph, snapshot.workingGraph)
        assertEquals(editedFlowchartGraph, snapshot.factGraphView?.visibleGraph)
        assertEquals(editedFlowchartGraph, snapshot.resourceRelationView?.visibleGraph)
        assertEquals(true, snapshot.workingGraphDirty)
    }

    @Test
    fun switchAnalysisDisplayMode在全局图变更后保留各视图独立文档() {
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
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
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
        assertEquals(editedFlowchartGraph, snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.id == "design:1" } == true)

        service.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        snapshot = service.snapshot()
        assertEquals(AnalysisDisplayMode.FLOWCHART, snapshot.analysisDisplayMode)
        assertEquals(listOf("method:flow-entry", "design:1"), snapshot.visibleGraph?.nodes?.map { it.id })
        assertEquals("READABLE", snapshot.visibleGraph?.nodes?.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.id == "design:1" } == true)
    }

    @Test
    fun markViewGraphChanged测试适配层会把流程图编辑提升为canonicalWorkspaceGraph重建全部视图() {
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
                feedbackLevel = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
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
                id = "design:1",
                type = NodeType.METHOD,
                title = "ManualFlowStep",
                sourceTag = GraphSourceTag.DRAFT_MANUAL,
                metadata = mapOf("flowchart.kind" to "PROCESS"),
            ),
        )

        service.markViewGraphChanged(
            graph = editedFlowchartGraph,
            displayMode = AnalysisDisplayMode.FLOWCHART,
        )

        val snapshot = service.snapshot()
        assertEquals(listOf("method:flow-entry", "design:1"), snapshot.visibleGraph?.nodes?.map { it.id })
        assertEquals("READABLE", snapshot.visibleGraph?.nodes?.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertEquals(listOf("method:flow-entry", "design:1"), snapshot.flowchartView?.visibleGraph?.nodes?.map { it.id })
        assertEquals(editedFlowchartGraph, snapshot.workingGraph)
        assertEquals(editedFlowchartGraph, snapshot.factGraphView?.visibleGraph)
        assertEquals(editedFlowchartGraph, snapshot.resourceRelationView?.visibleGraph)
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
            source = LlmResultSource.LOCAL_RULE,
            granularity = StepGranularity.BUSINESS,
            steps = listOf(
                GraphBeautificationStep(
                    stepId = "step-place-draft",
                    title = "当前方法内部",
                    granularity = StepGranularity.BUSINESS,
                    kind = StepKind.BUSINESS_ACTION,
                    description = "先判断参数，再调用 placeDraft。",
                    primaryNodeId = "method:order-service-place",
                    codeSnippet = "placeDraft(order);",
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
        service.asyncRequests.markGraphBeautificationResult(result)

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
        service.workbench.markDraftPatchApplyResult(report)

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
        service.asyncRequests.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "旧问题",
                answer = "旧答案",
                promptPreview = "old prompt",
            ),
        )

        service.asyncRequests.beginAuditRequest()

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
            source = LlmResultSource.LOCAL_RULE,
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

        service.asyncRequests.beginGraphBeautificationRequest()
        val afterBeginBeautification = service.snapshot()
        assertEquals(afterLoad.snapshotRevision + 1, afterBeginBeautification.snapshotRevision)

        service.asyncRequests.markGraphBeautificationResult(beautificationResult)
        val afterBeautificationResult = service.snapshot()
        assertEquals(afterBeginBeautification.snapshotRevision + 1, afterBeautificationResult.snapshotRevision)

        service.asyncRequests.beginAuditRequest()
        val afterBeginAudit = service.snapshot()
        assertEquals(afterBeautificationResult.snapshotRevision + 1, afterBeginAudit.snapshotRevision)

        service.asyncRequests.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "请围绕当前链路进行问答",
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

        service.workbench.markOperationFeedback(
            level = com.charmnight.linkgraph.ui.OperationFeedbackLevel.INFO,
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
        service.asyncRequests.markGeneratedCodeDrafts(
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
            source = LlmResultSource.LOCAL_RULE,
            promptPreview = "old prompt",
        )
        service.asyncRequests.beginGenerationPlanRequest()

        val plan = GenerationPlan(
            source = GenerationPlanSource.LOCAL_RULE,
            summary = "生成新的 DTO 计划。",
            warnings = emptyList(),
            promptPreview = "new prompt",
        )
        service.asyncRequests.markGenerationPlan(plan)

        val snapshot = service.snapshot()
        assertEquals(plan, snapshot.generationPlan)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanRequestState.phase)
        assertEquals(null, snapshot.generationPlanRequestState.errorMessage)
        assertEquals(emptyList(), snapshot.generatedCodeDrafts)
        assertEquals(emptyList(), snapshot.generatedCodeDraftWarnings)
        assertEquals(null, snapshot.generatedCodeDraftSource)
        assertEquals(null, snapshot.generatedCodeDraftPromptPreview)
        assertEquals("requestGenerationPlan", snapshot.lastMessageType)
    }

    @Test
    fun markDraftWorkbenchStateCanAdvanceDraftVersion() {
        val service = GraphEditorStateService()

        service.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "补充失败补偿说明",
                        targetNodeIds = listOf("method:submit-order"),
                    ),
                ),
            ),
            advanceDraftVersion = true,
        )
        service.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-2",
                        kind = DraftEntryKind.CHANGE,
                        title = "补充重试分支",
                        targetNodeIds = listOf("method:submit-order"),
                    ),
                ),
            ),
            advanceDraftVersion = true,
        )

        val snapshot = service.snapshot()
        assertEquals(2L, snapshot.draftVersion)
    }

    @Test
    fun markWorkingGraphChangedPreservesPlanAndCodeDraftsAfterDraftVersionAdvance() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit-order",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "currentMethod")
        service.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "补充失败补偿说明",
                        targetNodeIds = listOf("method:submit-order"),
                        afterState = "订单失败时补充补偿链路说明",
                    ),
                ),
            ),
            advanceDraftVersion = true,
        )
        service.asyncRequests.markGenerationPlan(
            GenerationPlan(
                source = GenerationPlanSource.LOCAL_RULE,
                summary = "先补失败补偿，再补重试分支。",
                warnings = emptyList(),
                promptPreview = "plan prompt",
            ),
        )
        service.asyncRequests.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:submit-order",
                    title = "OrderController.java",
                    targetPath = "src/main/java/com/example/OrderController.java",
                    content = "class OrderController {}",
                ),
            ),
            warnings = listOf("仅生成主方法草稿"),
            source = LlmResultSource.LOCAL_RULE,
            promptPreview = "code prompt",
        )
        service.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "补充失败补偿说明",
                        targetNodeIds = listOf("method:submit-order"),
                        afterState = "订单失败时补充补偿与重试链路说明",
                    ),
                ),
            ),
            advanceDraftVersion = true,
        )
        service.markWorkingGraphChanged(
            graph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit with compensation",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
        )

        val snapshot = service.snapshot()
        assertEquals(2L, snapshot.draftVersion)
        assertEquals(1L, snapshot.generationPlanDraftVersion)
        assertEquals(1L, snapshot.generatedCodeDraftVersion)
        assertEquals("先补失败补偿，再补重试分支。", snapshot.generationPlan?.summary)
        assertEquals(1, snapshot.generatedCodeDrafts.size)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.IDLE, snapshot.generationPlanRequestState.phase)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.IDLE, snapshot.codeDraftRequestState.phase)
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
        service.asyncRequests.beginDiffReviewRequest()

        var snapshot = service.snapshot()
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.RUNNING, snapshot.diffReviewRequestState.phase)
        assertEquals(null, snapshot.diffReviewRequestState.errorMessage)
        assertEquals(null, snapshot.diffReviewResult)

        service.asyncRequests.markDiffReviewRequestFailed("差异分析失败：HTTP 503")

        snapshot = service.snapshot()
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED, snapshot.diffReviewRequestState.phase)
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
        service.asyncRequests.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "旧问题",
                answer = "旧答案",
                promptPreview = "old prompt",
            ),
        )
        service.asyncRequests.markDiffReviewResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "旧差异问题",
                answer = "旧差异答案",
                promptPreview = "old diff prompt",
            ),
        )
        service.workbench.markDraftPatchPreview(
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
        assertEquals("workspaceGraphChanged", snapshot.lastMessageType)
    }
}
