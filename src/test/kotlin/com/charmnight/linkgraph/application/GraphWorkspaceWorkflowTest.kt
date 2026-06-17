package com.charmnight.linkgraph.application
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.application.workflow.GraphWorkspaceWorkflow
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.testing.*

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
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.currentVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.deriveFlowchartSummary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphWorkspaceWorkflowTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun workflowUsesCommandBasedEditScriptInsteadOfWholeGraphWriteback() {
        val source = Files.readString(
            root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GraphWorkspaceWorkflow.kt"),
        )

        assertTrue(source.contains("handleGraphEditRequest"))
        assertFalse(source.contains("handleFrontendGraphChanged"))
        assertFalse(source.contains("markViewGraphChanged"))
    }

    @Test
    fun exportMermaidUsesFullFlowchartGraphWhenVisibleGraphIsTruncated() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
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
                feedbackLevel = com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.INFO,
                statusMessage = "loaded",
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
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
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
            %% LG_NODE ENTRY|nodeId=method:order-service-place|nodeType=METHOD|title=OrderService.place|signature=com.example.OrderService.place%28java.lang.String%29:void
            %% LG_NODE DTO|nodeId=class:orderdraftdto|nodeType=CLASS|title=OrderDraftDto
            ENTRY["OrderService.place"]
            DTO["OrderDraftDto"]
            %% LG_EDGE ENTRY|to=DTO|edgeType=CALL
            ENTRY -- 调用 --> DTO
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
        assertTrue(currentVisibleGraph(snapshot).nodes.isNotEmpty())
    }

    @Test
    fun missingDiffInputsPublishesWarningFeedbackInsteadOfSilentNoop() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )

        val diffResult = workflow.showDiffMode()

        val snapshot = stateService.snapshot()
        assertNull(diffResult)
        assertEquals(com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message?.contains("缺少") == true, snapshot.operationFeedback?.message)
    }

    @Test
    fun emptySyncPreviewPublishesNeutralFeedback() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )

        val items = workflow.requestSyncPreview()

        val snapshot = stateService.snapshot()
        assertTrue(items.isEmpty())
        assertEquals(com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.INFO, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message?.contains("没有可同步") == true, snapshot.operationFeedback?.message)
    }

    @Test
    fun frontendLayoutChangeDoesNotRequestBrowserSync() {
        val stateService = GraphEditorStateService()
        var syncCount = 0
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
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

    @Test
    fun frontendGraphChangePreservesTrustedNavigationFieldsForExistingNodes() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )
        val trustedNode = GraphNode(
            id = "method:order-service-place",
            type = NodeType.METHOD,
            title = "OrderService.place",
            location = "src/main/java/com/example/OrderService.java:12:1",
            signature = "com.example.OrderService.place(java.lang.String):void",
            inputs = listOf("java.lang.String"),
            outputs = listOf("com.example.OrderResult"),
            doc = "Trusted node",
        )
        stateService.loadGraph(trustedNode.asGraph(), "trusted-graph")

        workflow.handleGraphEditRequest(
            GraphEditRequest(
                sceneId = GraphSceneId.WORKSPACE_FACT,
                baseWorkspaceRevision = stateService.snapshot().workspaceRevision,
                operations = listOf(
                    GraphEditOperation.UpsertNode(
                        trustedNode.copy(
                            title = "OrderService.placeDraft",
                            location = "/tmp/escape.java:1:1",
                            signature = "java.lang.Runtime.exec(java.lang.String):void",
                            inputs = listOf("java.lang.String", "com.example.OrderDraft"),
                            outputs = listOf("com.example.OrderDraft"),
                            doc = "Edited doc",
                        ),
                    ),
                ),
                source = GraphEditRequestSource.FRONTEND,
            ),
        )

        val persistedNode = stateService.snapshot().workspaceGraph.nodes.single()
        assertNotNull(persistedNode)
        assertEquals("OrderService.placeDraft", persistedNode.title)
        assertEquals(listOf("java.lang.String", "com.example.OrderDraft"), persistedNode.inputs)
        assertEquals(listOf("com.example.OrderDraft"), persistedNode.outputs)
        assertEquals("Edited doc", persistedNode.doc)
        assertEquals("src/main/java/com/example/OrderService.java:12:1", persistedNode.location)
        assertEquals("com.example.OrderService.place(java.lang.String):void", persistedNode.signature)
        assertEquals(NodeType.METHOD, persistedNode.type)
    }

    @Test
    fun frontendGraphChangeStripsNavigationFieldsFromNewManualNodes() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )

        workflow.handleGraphEditRequest(
            GraphEditRequest(
                sceneId = GraphSceneId.WORKSPACE_FACT,
                baseWorkspaceRevision = stateService.snapshot().workspaceRevision,
                operations = listOf(
                    GraphEditOperation.UpsertNode(
                        GraphNode(
                            id = "design:1",
                            type = NodeType.METHOD,
                            title = "Manual draft node",
                            location = "/tmp/escape.java:1:1",
                            signature = "java.lang.System.exit(int):void",
                            doc = "User-authored draft node",
                        ),
                    ),
                ),
                source = GraphEditRequestSource.FRONTEND,
            ),
        )

        val snapshot = stateService.snapshot()
        val persistedNode = snapshot.workspaceGraph.nodes.single()
        assertNotNull(persistedNode)
        assertEquals("Manual draft node", persistedNode.title)
        assertEquals("User-authored draft node", persistedNode.doc)
        assertNull(persistedNode.location)
        assertNull(persistedNode.signature)
        val transaction = assertNotNull(snapshot.lastGraphEditTransaction)
        assertEquals("design:1", transaction.graphAfterApply.nodes.single().id)
        assertEquals(GraphEditRequestSource.FRONTEND, transaction.source)
        assertNull(snapshot.lastGraphEditRejection)
    }

    @Test
    fun rejectedGraphEditPublishesFeedbackAndStructuredRejection() {
        val stateService = GraphEditorStateService()
        val workflow = GraphWorkspaceWorkflow(
            snapshotProvider = stateService.editorSnapshotProvider(),
            workspaceGraphCommitter = stateService.workspaceGraphCommitter(),
            eventSink = stateService.applicationEventSink(),
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            copyToClipboard = { false },
        )

        workflow.handleGraphEditRequest(
            GraphEditRequest(
                sceneId = GraphSceneId.WORKSPACE_FACT,
                baseWorkspaceRevision = stateService.snapshot().workspaceRevision - 1,
                operations = listOf(
                    GraphEditOperation.UpsertNode(
                        GraphNode(id = "node-new", type = NodeType.METHOD, title = "New"),
                    ),
                ),
                source = GraphEditRequestSource.FRONTEND,
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message?.contains("STALE_BASE_REVISION") == true, snapshot.operationFeedback?.message)
        assertEquals(GraphEditIssueCode.STALE_BASE_REVISION, snapshot.lastGraphEditRejection?.issues?.single()?.code)
        assertNull(snapshot.lastGraphEditTransaction)
        assertTrue(snapshot.workspaceGraph.nodes.isEmpty())
    }

    private fun GraphNode.asGraph() = GraphDocument(nodes = listOf(this))
}
