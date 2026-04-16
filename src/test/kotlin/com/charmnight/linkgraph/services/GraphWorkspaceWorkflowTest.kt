package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphLayoutPosition
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.deriveFlowchartSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphWorkspaceWorkflowTest {
    @Test
    fun exportMermaidUsesFullFlowchartGraphWhenVisibleGraphIsTruncated() {
        val stateService = GraphEditorStateService()
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = GraphWorkspaceWorkflow(
            session = session,
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )
        val entryNode = GraphNode(
            id = "method:order-service-place",
            type = NodeType.METHOD,
            title = "OrderService.place",
            signature = "com.example.OrderService.place():void",
            metadata = mapOf("flowchart.kind" to "ENTRY"),
        )
        val hiddenLoopNode = GraphNode(
            id = "scope:foreach",
            type = NodeType.FLOW_SCOPE,
            title = "for (file : files)",
            metadata = mapOf(
                "flowchart.kind" to "DECISION",
                "flow.scopeKind" to "FOREACH",
                "flow.scopeCategory" to "LOOP_PRE_TEST",
            ),
        )
        val visibleGraph = GraphDocument(nodes = listOf(entryNode))
        val fullGraph = GraphDocument(
            nodes = listOf(entryNode, hiddenLoopNode),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-loop",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = entryNode.id,
                    toNodeId = hiddenLoopNode.id,
                    metadata = mapOf("flow.edgeRole" to "ENTRY"),
                ),
            ),
        )

        stateService.loadAnalysisOutcome(
            outcome = AnalysisOutcome(
                displayMode = AnalysisDisplayMode.FLOWCHART,
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = entryNode.id,
                selectedMethodSignature = entryNode.signature,
                displayName = "uploadFiles",
                feedbackLevel = GraphEditorStateService.OperationFeedbackLevel.INFO,
                feedbackMessage = "loaded",
                flowchartView = FlowchartViewDocument(
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                    anchorNodeId = entryNode.id,
                    summary = deriveFlowchartSummary(visibleGraph, fullGraph),
                ),
            ),
            source = "test-flowchart",
        )

        val exported = workflow.exportMermaid()

        assertTrue(exported.contains("nodeId=scope:foreach"))
        assertTrue(exported.contains("flow.scopeKind=FOREACH"))
        assertTrue(exported.contains("edgeType=CONTROL_FLOW"))
    }

    @Test
    fun importExportAndDiffModeUpdateEditorState() {
        val stateService = GraphEditorStateService()
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = GraphWorkspaceWorkflow(
            session = session,
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                ),
            ),
        )
        val mermaid = """
            graph TD
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        workflow.loadGraph(codeGraph, "code-graph")
        val imported = workflow.importMermaid(mermaid)
        val exported = workflow.exportMermaid()
        val diffResult = workflow.showDiffMode()

        val snapshot = stateService.snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertEquals(imported, snapshot.designBaselineGraph)
        assertEquals(exported, snapshot.exportedMermaid)
        assertTrue(snapshot.mermaidIssues.isEmpty())
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertNotNull(diffResult)
        assertTrue(snapshot.visibleGraph?.nodes?.isNotEmpty() == true)
    }

    @Test
    fun frontendLayoutChangeDoesNotRequestBrowserSync() {
        val stateService = GraphEditorStateService()
        var syncCount = 0
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = { syncCount += 1 },
        )
        val workflow = GraphWorkspaceWorkflow(
            session = session,
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )

        workflow.handleFrontendLayoutChanged(
            positions = mapOf(
                "method:order-service-place" to GraphLayoutPosition(x = 128.0, y = 256.0),
            ),
        )

        assertEquals(0, syncCount)
        assertEquals(128.0, stateService.snapshot().layoutState.positions["method:order-service-place"]?.x)
        assertEquals(256.0, stateService.snapshot().layoutState.positions["method:order-service-place"]?.y)
    }
}
