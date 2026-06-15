package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.application.model.AsyncRequestState
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
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionMessage
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantTurnKind
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
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
            preservePreviousStatusKind = true,
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
                statusMessage = "已加载当前主体分析：OrderService.place",
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
        assertEquals(fullGraph, currentVisibleGraph(snapshot))
        assertEquals(fullGraph, snapshot.workspaceGraph)
        assertEquals(fullGraph, snapshot.semanticFactGraph)
        assertEquals(GraphSceneId.WORKSPACE_FACT, snapshot.currentSceneId)
        assertEquals("method:order-service-place", snapshot.selectedNodeId)
        assertEquals("com.example.OrderService.place(java.lang.String):void", snapshot.selectedMethodSignature)
        assertEquals("已加载当前主体分析：OrderService.place", snapshot.operationFeedback?.message)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals("method:order-service-place", snapshot.factGraphView.anchorNodeId)
        assertEquals("method:order-service-place", snapshot.flowchartView.anchorNodeId)
        assertEquals("method:order-service-place", snapshot.resourceRelationView.anchorNodeId)
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
                statusMessage = "已加载流程图",
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
        assertEquals(factGraph, snapshot.semanticFactGraph)
        assertEquals(flowchartFullGraph, snapshot.factGraphView.fullGraph)
        assertEquals(flowchartFullGraph, snapshot.workspaceGraph)
        assertEquals(flowchartFullGraph, snapshot.workspaceBaseGraph)
        assertEquals(flowchartFullGraph, snapshot.flowchartView.fullGraph)
    }

    @Test
    fun loadGraph为可读流程图合并节点建立原始调用节点映射() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit",
                    type = NodeType.METHOD,
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit():void",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "action:write-bytes",
                    type = NodeType.FLOW_ACTION,
                    title = "FileUtils.writeBytes(filePath, response.toString())",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flow.kind" to "ACTION",
                        "flowchart.kind" to "PROCESS",
                    ),
                ),
                GraphNode(
                    id = "invoke:write-bytes",
                    type = NodeType.FLOW_ACTION,
                    title = "调用 FileUtils.writeBytes",
                    signature = "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flow.kind" to "INVOCATION",
                        "flowchart.kind" to "SUBROUTINE",
                    ),
                ),
            ),
            edges = listOf(
                com.charmnight.linkgraph.model.GraphEdge(
                    id = "control:submit-to-action",
                    type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:submit",
                    toNodeId = "action:write-bytes",
                    sourceTag = GraphSourceTag.FACT,
                ),
                com.charmnight.linkgraph.model.GraphEdge(
                    id = "control:action-to-invoke",
                    type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                    fromNodeId = "action:write-bytes",
                    toNodeId = "invoke:write-bytes",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )

        service.loadGraph(graph, "test")

        val snapshot = service.snapshot()
        val projectedNode = snapshot.flowchartView.visibleGraph.nodes.single { it.id == "action:write-bytes" }
        assertEquals("调用 FileUtils.writeBytes", projectedNode.title)
        assertEquals("invoke:write-bytes", projectedNode.metadata["flowchart.projectedFromNodeIds"])
        assertEquals(
            "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
            projectedNode.signature,
        )
        assertTrue(
            snapshot.flowchartView.projectionIndex.nodeMapping("action:write-bytes")
                ?.canonicalNodeIds
                .orEmpty()
                .contains("invoke:write-bytes"),
        )
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
                statusMessage = "已加载流程图",
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
        service.markGraphChanged(
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
                statusMessage = "已重新加载流程图",
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
            snapshot.workspaceGraph.nodes.firstOrNull { it.id == "scope:file-download-if" }?.title,
        )
        assertEquals(
            "if (delete == true)",
            snapshot.flowchartView.visibleGraph?.nodes?.firstOrNull { it.id == "scope:file-download-if" }?.title,
        )
        assertEquals(
            factGraph,
            snapshot.semanticFactGraph,
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
        assertEquals(graph, snapshot.factGraphView.visibleGraph)
        assertEquals(graph, snapshot.flowchartView.visibleGraph)
        assertEquals(graph, snapshot.resourceRelationView.visibleGraph)
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
        assertEquals(factGraph, snapshot.workspaceGraph)
        assertEquals(factGraph, snapshot.semanticFactGraph)
        assertEquals(importedBaseline, snapshot.designBaselineGraph)
        assertEquals(factGraph, currentVisibleGraph(snapshot))
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
        assertEquals(draftGraph, snapshot.workspaceGraph)
        assertEquals(factGraph, snapshot.semanticFactGraph)
        assertEquals(draftGraph, currentVisibleGraph(snapshot))
        assertNotNull(snapshot.semanticFactGraph)
        assertNotNull(snapshot.factGraphView)
        assertNotNull(snapshot.flowchartView)
        assertNotNull(snapshot.resourceRelationView)
        assertEquals(1, snapshot.semanticFactGraph.nodes.size)
        assertEquals(2, snapshot.workspaceGraph.nodes.size)
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
                statusMessage = "已加载流程图",
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
        assertEquals(listOf("method:flow-entry", "design-note:1"), currentVisibleGraph(snapshot).nodes.map { it.id })
        assertEquals("READABLE", currentVisibleGraph(snapshot).nodes.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertEquals(listOf("method:flow-entry", "design-note:1"), snapshot.flowchartView.visibleGraph?.nodes?.map { it.id })
        assertEquals(editedFlowchartGraph, snapshot.workspaceGraph)
        assertEquals(editedFlowchartGraph, snapshot.factGraphView.visibleGraph)
        assertEquals(editedFlowchartGraph, snapshot.resourceRelationView.visibleGraph)
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
                statusMessage = "已加载流程图",
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
        assertEquals(editedFlowchartGraph, currentVisibleGraph(snapshot))
        assertTrue(currentVisibleGraph(snapshot).nodes.any { it.id == "design:1" } == true)

        service.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        snapshot = service.snapshot()
        assertEquals(AnalysisDisplayMode.FLOWCHART, snapshot.analysisDisplayMode)
        assertEquals(listOf("method:flow-entry", "design:1"), currentVisibleGraph(snapshot).nodes.map { it.id })
        assertEquals("READABLE", currentVisibleGraph(snapshot).nodes.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertTrue(currentVisibleGraph(snapshot).nodes.any { it.id == "design:1" } == true)
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
                statusMessage = "已加载流程图",
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

        service.switchAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        service.markGraphChanged(graph = editedFlowchartGraph)

        val snapshot = service.snapshot()
        assertEquals(listOf("method:flow-entry", "design:1"), currentVisibleGraph(snapshot).nodes.map { it.id })
        assertEquals("READABLE", currentVisibleGraph(snapshot).nodes.firstOrNull { it.id == "method:flow-entry" }?.metadata?.get("flowchart.projection.mode"))
        assertEquals(listOf("method:flow-entry", "design:1"), snapshot.flowchartView.visibleGraph?.nodes?.map { it.id })
        assertEquals(editedFlowchartGraph, snapshot.workspaceGraph)
        assertEquals(editedFlowchartGraph, snapshot.factGraphView.visibleGraph)
        assertEquals(editedFlowchartGraph, snapshot.resourceRelationView.visibleGraph)
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
    fun beginQaRequestClearsPreviousQaResultButKeepsGraphs() {
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
        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "旧问题",
                answer = "旧答案",
                promptPreview = "old prompt",
            ),
        )

        service.asyncRequests.beginQaRequest()

        val snapshot = service.snapshot()
        assertEquals(graph, currentVisibleGraph(snapshot))
        assertEquals(graph, snapshot.workspaceGraph)
        assertEquals(null, snapshot.qaResult)
        assertEquals("requestAssistantTask", snapshot.lastMessageType)
    }

    @Test
    fun assistantSessionTracksThinTurnRefsWithoutCopyingQaResult() {
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
        service.selectNode("method:order-service-place")
        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "这个方法会影响哪里？",
                answer = "会影响订单提交流程。",
                promptPreview = "qa prompt",
            ),
        )

        val snapshot = service.snapshot()
        val assistantSession = snapshot.assistantSessionState
        assertEquals(AssistantIntent.ASK_CODE, assistantSession.activeIntent)
        assertEquals(listOf("method:order-service-place"), assistantSession.context.selectedNodeIds)
        assertEquals(1, assistantSession.turns.size)
        assertEquals(AssistantTurnKind.QA, assistantSession.turns.single().kind)
        assertEquals("qaResult", assistantSession.turns.single().sourceMessageType)
        assertEquals("qa:local:1", assistantSession.turns.single().resultId)
        assertEquals("会影响订单提交流程。", snapshot.qaResult?.answer)
    }

    @Test
    fun assistantSessionGivesFailedTurnsExplicitThinResultIds() {
        val service = GraphEditorStateService()

        service.asyncRequests.markQaRequestFailed(
            message = "上游超时",
            requestState = AsyncRequestState.failed(
                message = "上游超时",
                requestId = 41,
                finishedAtEpochMillis = 1000,
            ),
        )
        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "这个方法会影响哪里？",
                answer = "会影响订单提交流程。",
                promptPreview = "qa prompt",
            ),
            requestState = AsyncRequestState.succeeded(
                requestId = 42,
                finishedAtEpochMillis = 2000,
            ),
        )

        val turns = service.snapshot().assistantSessionState.turns
        assertEquals(2, turns.size)
        assertEquals("qa-failure:request:41", turns[0].resultId)
        assertEquals("qa:request:42", turns[1].resultId)
    }

    @Test
    fun assistantResultStoreStoresFailurePayloadForFailedTurns() {
        val service = GraphEditorStateService()

        service.asyncRequests.markQaRequestFailed(
            message = "上游超时",
            requestState = AsyncRequestState.failed(
                message = "上游超时",
                requestId = 41,
                detailMessage = "HTTP 504 from qa provider",
                finishedAtEpochMillis = 1000,
            ),
        )

        val snapshot = service.snapshot()
        val entry = assertNotNull(snapshot.assistantResultStore.results["qa-failure:request:41"])
        val failure = assertNotNull(entry.failure)
        assertEquals(AssistantTurnKind.QA, entry.kind)
        assertEquals("qa-failure:request:41", failure.resultId)
        assertEquals("上游超时", failure.message)
        assertEquals("HTTP 504 from qa provider", failure.detailMessage)
        assertEquals(41, failure.requestId)
        assertEquals("FAILED", failure.phase)
    }

    @Test
    fun assistantHistoryRetentionPrunesTurnsAndResultStoreTogether() {
        val service = GraphEditorStateService()

        repeat(55) { index ->
            service.asyncRequests.markQaResult(
                GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = "问题 $index",
                    answer = "回答 $index",
                    promptPreview = "prompt $index",
                ),
                requestState = AsyncRequestState.succeeded(
                    requestId = index.toLong(),
                    finishedAtEpochMillis = index.toLong(),
                ),
            )
        }

        val snapshot = service.snapshot()
        val resultIds = snapshot.assistantSessionState.turns.map { turn -> turn.resultId }
        assertEquals(50, snapshot.assistantSessionState.turns.size)
        assertEquals(resultIds.toSet(), snapshot.assistantResultStore.results.keys)
        assertEquals(5, snapshot.assistantSessionState.turns.first().createdAtEpochMillis)
        assertEquals(54, snapshot.assistantSessionState.turns.last().createdAtEpochMillis)
    }

    @Test
    fun generationPlanDiscussionHistoryKeepsEachTurnResultSnapshot() {
        val service = GraphEditorStateService()

        service.asyncRequests.markGenerationPlanDiscussion(
            generationDiscussionResult(
                answer = "第一轮回答",
                requestId = 10,
            ),
            requestState = AsyncRequestState.succeeded(
                requestId = 10,
                finishedAtEpochMillis = 1000,
            ),
        )
        service.asyncRequests.markGenerationPlanDiscussion(
            generationDiscussionResult(
                answer = "第二轮回答",
                requestId = 11,
            ),
            requestState = AsyncRequestState.succeeded(
                requestId = 11,
                finishedAtEpochMillis = 2000,
            ),
        )

        val snapshot = service.snapshot()
        val turns = snapshot.assistantSessionState.turns
        assertEquals(2, turns.size)
        assertEquals(2, turns.map { turn -> turn.resultId }.toSet().size)
        assertEquals(turns.map { turn -> turn.resultId }.toSet(), snapshot.assistantResultStore.results.keys)
        assertEquals(
            "第一轮回答",
            snapshot.assistantResultStore.results[turns[0].resultId]
                ?.generationDiscussionSession
                ?.messages
                ?.last()
                ?.content,
        )
        assertEquals(
            "第二轮回答",
            snapshot.assistantResultStore.results[turns[1].resultId]
                ?.generationDiscussionSession
                ?.messages
                ?.last()
                ?.content,
          )
      }

    @Test
    fun assistantResultStoreKeepsInstanceScopedSnapshotsForRepeatedSuccessfulTurns() {
        val service = GraphEditorStateService()

        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "这个方法会影响哪里？",
                answer = "会影响订单提交流程。",
                promptPreview = "qa prompt 1",
                warnings = listOf("qa-first"),
            ),
            requestState = AsyncRequestState.succeeded(requestId = 101, finishedAtEpochMillis = 1001),
        )
        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "这个方法会影响哪里？",
                answer = "会影响订单提交流程。",
                promptPreview = "qa prompt 2",
                warnings = listOf("qa-second"),
            ),
            requestState = AsyncRequestState.succeeded(requestId = 102, finishedAtEpochMillis = 1002),
        )
        service.asyncRequests.markDiffReviewResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "检查当前改动",
                answer = "当前改动需要补相关测试。",
                promptPreview = "diff prompt 1",
                warnings = listOf("diff-first"),
            ),
            requestState = AsyncRequestState.succeeded(requestId = 201, finishedAtEpochMillis = 2001),
        )
        service.asyncRequests.markDiffReviewResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "检查当前改动",
                answer = "当前改动需要补相关测试。",
                promptPreview = "diff prompt 2",
                warnings = listOf("diff-second"),
            ),
            requestState = AsyncRequestState.succeeded(requestId = 202, finishedAtEpochMillis = 2002),
        )
        service.asyncRequests.markGraphBeautificationResult(
            repeatedExplanation(description = "第一轮解释"),
            requestState = AsyncRequestState.succeeded(requestId = 301, finishedAtEpochMillis = 3001),
        )
        service.asyncRequests.markGraphBeautificationResult(
            repeatedExplanation(description = "第二轮解释"),
            requestState = AsyncRequestState.succeeded(requestId = 302, finishedAtEpochMillis = 3002),
        )
        service.asyncRequests.markGenerationPlan(
            repeatedPlan(description = "第一轮计划项说明"),
            requestState = AsyncRequestState.succeeded(requestId = 401, finishedAtEpochMillis = 4001),
        )
        service.asyncRequests.markGenerationPlan(
            repeatedPlan(description = "第二轮计划项说明"),
            requestState = AsyncRequestState.succeeded(requestId = 402, finishedAtEpochMillis = 4002),
        )

        val snapshot = service.snapshot()
        val turns = snapshot.assistantSessionState.turns
        val store = snapshot.assistantResultStore.results
        assertEquals(8, turns.size)
        assertEquals(turns.map { turn -> turn.resultId }.toSet(), store.keys)

        val qaTurns = turns.filter { turn -> turn.kind == AssistantTurnKind.QA }
        assertNotEquals(qaTurns[0].resultId, qaTurns[1].resultId)
        assertEquals(listOf("qa-first"), store[qaTurns[0].resultId]?.qa?.warnings)
        assertEquals(listOf("qa-second"), store[qaTurns[1].resultId]?.qa?.warnings)

        val checkTurns = turns.filter { turn -> turn.kind == AssistantTurnKind.CHECK_RESULT }
        assertNotEquals(checkTurns[0].resultId, checkTurns[1].resultId)
        assertEquals(listOf("diff-first"), store[checkTurns[0].resultId]?.check?.warnings)
        assertEquals(listOf("diff-second"), store[checkTurns[1].resultId]?.check?.warnings)

        val explanationTurns = turns.filter { turn -> turn.kind == AssistantTurnKind.EXPLANATION }
        assertNotEquals(explanationTurns[0].resultId, explanationTurns[1].resultId)
        assertEquals("第一轮解释", store[explanationTurns[0].resultId]?.explanation?.steps?.single()?.description)
        assertEquals("第二轮解释", store[explanationTurns[1].resultId]?.explanation?.steps?.single()?.description)

        val planTurns = turns.filter { turn -> turn.kind == AssistantTurnKind.GENERATION_PLAN }
        assertNotEquals(planTurns[0].resultId, planTurns[1].resultId)
        assertEquals("第一轮计划项说明", store[planTurns[0].resultId]?.generationPlan?.items?.single()?.description)
        assertEquals("第二轮计划项说明", store[planTurns[1].resultId]?.generationPlan?.items?.single()?.description)
    }

    @Test
    fun assistantSessionTracksSelectedDiffItemsForCheckChangeContext() {
        val service = GraphEditorStateService()
        val selectedDiffItemIds = listOf("diff:OrderController.kt")

        service.asyncRequests.beginDiffReviewRequest(
            selectedDiffItemIds = selectedDiffItemIds,
        )
        service.asyncRequests.markDiffReviewResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "检查当前改动",
                answer = "当前改动需要补相关测试。",
                requestedMode = QaMode.REVIEW,
                effectiveMode = QaMode.REVIEW,
                promptPreview = "diff review prompt",
            ),
            selectedDiffItemIds = selectedDiffItemIds,
        )

        val assistantSession = service.snapshot().assistantSessionState
        assertEquals(AssistantIntent.CHECK_CHANGE, assistantSession.activeIntent)
        assertEquals(selectedDiffItemIds, assistantSession.context.selectedDiffItemIds)
        assertEquals(selectedDiffItemIds, assistantSession.turns.single().context.selectedDiffItemIds)
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

        service.asyncRequests.beginQaRequest()
        val afterBeginQa = service.snapshot()
        assertEquals(afterBeautificationResult.snapshotRevision + 1, afterBeginQa.snapshotRevision)

        service.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "请围绕当前链路进行问答",
                answer = "当前链路缺少兜底说明。",
                promptPreview = "qa prompt",
            ),
        )
        val afterQaResult = service.snapshot()
        assertEquals(afterBeginQa.snapshotRevision + 1, afterQaResult.snapshotRevision)
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
        assertEquals("generationPlanResult", snapshot.lastMessageType)
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
        service.markGraphChanged(
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
    fun markGraphChangedClearsDerivedQaAndPreviewState() {
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
        service.asyncRequests.markQaResult(
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
        assertEquals(null, snapshot.qaResult)
        assertEquals(null, snapshot.diffReviewResult)
        assertEquals(null, snapshot.draftPatchPreview)
        assertEquals("workspaceGraphChanged", snapshot.lastMessageType)
    }

    private fun generationDiscussionResult(
        answer: String,
        requestId: Long,
    ): GenerationPlanDiscussionResult =
        GenerationPlanDiscussionResult(
            source = LlmResultSource.LOCAL_RULE,
            question = "继续讨论实现方案 $requestId",
            answer = answer,
            promptPreview = "generation discussion prompt $requestId",
            session = GenerationPlanDiscussionSession(
                sessionId = "generation-discussion-session",
                messages = listOf(
                    GenerationPlanDiscussionMessage(
                        messageId = "user-$requestId",
                        role = QaMessageRole.USER,
                        content = "继续讨论实现方案 $requestId",
                    ),
                    GenerationPlanDiscussionMessage(
                        messageId = "assistant-$requestId",
                        role = QaMessageRole.ASSISTANT,
                        content = answer,
                    ),
                ),
            ),
        )

    private fun repeatedExplanation(description: String): GraphBeautificationResult =
        GraphBeautificationResult(
            source = LlmResultSource.LOCAL_RULE,
            granularity = StepGranularity.BUSINESS,
            steps = listOf(
                GraphBeautificationStep(
                    stepId = "step-submit",
                    title = "提交订单",
                    granularity = StepGranularity.BUSINESS,
                    kind = StepKind.BUSINESS_ACTION,
                    description = description,
                ),
            ),
            promptPreview = "same explanation prompt",
        )

    private fun repeatedPlan(description: String): GenerationPlan =
        GenerationPlan(
            source = GenerationPlanSource.LOCAL_RULE,
            summary = "生成订单实现计划",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-item-submit",
                    title = "补充提交订单实现",
                    description = description,
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = "src/main/kotlin/com/example/OrderService.kt",
                ),
            ),
            promptPreview = "same plan prompt",
        )
}
