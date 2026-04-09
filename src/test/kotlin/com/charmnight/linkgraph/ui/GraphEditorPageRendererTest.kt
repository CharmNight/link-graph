package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FactGraphSummary
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartSummary
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationSummary
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationSection
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEditorPageRendererTest {
    @Test
    fun bootstrapJson输出展示模式字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"analysisDisplayMode\":\"FACT_GRAPH\""))
    }

    @Test
    fun bootstrapJson输出异步请求遥测字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            auditRequestState = GraphEditorStateService.AsyncRequestState.running(
                statusMessage = "正在等待远程 LLM 审计响应",
                detailMessage = "当前采用完整返回，不是流式输出。",
                startedAtEpochMillis = 1_710_000_000_000,
                streaming = false,
            ).copy(
                requestId = 17,
                scene = "审计",
                executionMode = GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY,
                providerLabel = "OpenAI Compatible",
                model = "gpt-test",
                endpointSummary = "example.com/v1/chat/completions",
                promptPreviewAvailable = true,
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"requestId\":17"))
        assertTrue(json.contains("\"scene\":\"审计\""))
        assertTrue(json.contains("\"executionMode\":\"REMOTE_READY\""))
        assertTrue(json.contains("\"providerLabel\":\"OpenAI Compatible\""))
        assertTrue(json.contains("\"model\":\"gpt-test\""))
        assertTrue(json.contains("\"endpointSummary\":\"example.com/v1/chat/completions\""))
        assertTrue(json.contains("\"promptPreviewAvailable\":true"))
    }

    @Test
    fun bootstrapJson输出流式预览字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            graphBeautificationRequestState = GraphEditorStateService.AsyncRequestState.running(
                statusMessage = "正在等待远程 LLM 链路讲解响应",
                detailMessage = "当前采用流式输出。",
                streaming = true,
            ).copy(
                previewText = "第一段预览",
                previewUpdatedAtEpochMillis = 1_710_000_000_123,
                finalizingStructuredResult = true,
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"previewText\":\"第一段预览\""))
        assertTrue(json.contains("\"previewUpdatedAtEpochMillis\":1710000000123"))
        assertTrue(json.contains("\"finalizingStructuredResult\":true"))
    }

    @Test
    fun bootstrapJson输出修订号布局与选中节点字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            layoutState = GraphLayoutState(
                positions = mapOf(
                    "method:submit-order" to GraphLayoutPosition(
                        x = 520.0,
                        y = 240.0,
                    ),
                ),
            ),
            semanticRevision = 11,
            layoutRevision = 7,
            snapshotRevision = 19,
            selectedNodeId = "method:submit-order",
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"analysisDisplayMode\":\"FLOWCHART\""))
        assertTrue(json.contains("\"semanticRevision\":11"))
        assertTrue(json.contains("\"layoutRevision\":7"))
        assertTrue(json.contains("\"snapshotRevision\":19"))
        assertTrue(json.contains("\"selectedNodeId\":\"method:submit-order\""))
        assertTrue(json.contains("\"layoutState\":{\"positions\":{\"method:submit-order\":{"))
        assertTrue(json.contains("\"x\":520.0") || json.contains("\"x\":520"))
        assertTrue(json.contains("\"y\":240.0") || json.contains("\"y\":240"))
    }

    @Test
    fun bootstrapJson输出三视图独立投影文档() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = GraphDocument(),
            workingGraph = GraphDocument(),
            factGraphView = FactGraphViewDocument(
                visibleGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:fact-anchor",
                            type = NodeType.METHOD,
                            title = "FactAnchor",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                fullGraph = GraphDocument(),
                anchorNodeId = "method:fact-anchor",
                summary = FactGraphSummary(
                    anchorTitle = "FactAnchor",
                    visibleNodeCount = 1,
                    fullNodeCount = 0,
                ),
            ),
            flowchartView = FlowchartViewDocument(
                visibleGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:flow-entry",
                            type = NodeType.METHOD,
                            title = "FlowEntry",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf("flowchart.kind" to "ENTRY"),
                        ),
                    ),
                ),
                fullGraph = GraphDocument(),
                anchorNodeId = "method:flow-entry",
                summary = FlowchartSummary(
                    nodeCount = 1,
                    branchCount = 0,
                    exceptionPathCount = 0,
                ),
            ),
            resourceRelationView = ResourceRelationViewDocument(
                visibleGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "resource:sql",
                            type = NodeType.SQL,
                            title = "order_mapper.xml#insertOrder",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf("resource.lane" to "DATA"),
                        ),
                    ),
                ),
                fullGraph = GraphDocument(),
                anchorNodeId = "resource:sql",
                summary = ResourceRelationSummary(
                    visibleNodeCount = 1,
                    laneCounts = mapOf("DATA" to 1),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"factGraphView\""))
        assertTrue(json.contains("\"flowchartView\""))
        assertTrue(json.contains("\"resourceRelationView\""))
        assertTrue(json.contains("FactAnchor"))
        assertTrue(json.contains("FlowEntry"))
        assertTrue(json.contains("order_mapper.xml#insertOrder"))
        assertTrue(json.contains("\"anchorTitle\":\"FactAnchor\""))
        assertTrue(json.contains("\"visibleNodeCount\":1"))
        assertTrue(json.contains("\"branchCount\":0"))
        assertTrue(json.contains("\"exceptionPathCount\":0"))
        assertTrue(json.contains("\"laneCounts\":{\"DATA\":1}"))
    }

    @Test
    fun rendersBootstrapStateIntoFrontendHtml() {
        val renderer = GraphEditorPageRenderer()
        val html = """
            <html>
              <head><title>Link Graph</title></head>
              <body><div id="root"></div></body>
            </html>
        """.trimIndent()
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        location = "src/main/java/com/example/OrderController.java:18",
                        signature = "com.example.OrderController.submit():void",
                        inputs = listOf("java.lang.String", "com.example.SubmitRequest"),
                        outputs = listOf("com.example.SubmitResult"),
                        doc = "Submit order entry.",
                        metadata = mapOf(
                            "ui.x" to "120",
                            "ui.y" to "96",
                            "linkGraph.manual" to "true",
                        ),
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
                edges = listOf(
                    GraphEdge(
                        id = "call:submit-order->draft-dto",
                        type = com.charmnight.linkgraph.model.EdgeType.CALL,
                        fromNodeId = "method:submit-order",
                        toNodeId = "class:order-draft-dto",
                        metadata = mapOf("callOrder" to "0"),
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "note:default-fallback",
                        type = NodeType.CLASS,
                        title = "DefaultFallback",
                        doc = "AI 建议补充默认兜底节点。",
                        sourceTag = GraphSourceTag.DRAFT_AI,
                    ),
                ),
            ),
            referenceFactGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            designBaselineGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "design:default-fallback",
                        type = NodeType.CLASS,
                        title = "DefaultFallback",
                        sourceTag = GraphSourceTag.DESIGN_BASELINE,
                    ),
                ),
            ),
            draftPatchPreview = GraphPatch(
                summary = "Add fallback path",
                operations = listOf(
                    GraphPatchOperation(
                        id = "patch-op-1",
                        action = GraphPatchAction.ADD_NODE,
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = "note:default-fallback",
                        title = "新增默认兜底节点",
                        summary = "把审计建议写入草稿层",
                        node = GraphNode(
                            id = "note:default-fallback",
                            type = NodeType.CLASS,
                            title = "DefaultFallback",
                            sourceTag = GraphSourceTag.DRAFT_AI,
                        ),
                    ),
                ),
                addedNodeIds = listOf("note:default-fallback"),
            ),
            selectedNodeId = "method:submit-order",
            diff = GraphDiff(
                entries = listOf(
                    GraphDiffEntry(
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = "class:order-draft-dto",
                        status = DiffStatus.ONLY_IN_MERMAID,
                        message = "Design node is missing from code.",
                    ),
                ),
            ),
            syncPreviewItems = listOf(
                SyncPreviewItem(
                    id = "create-order-draft",
                    title = "Create OrderDraftDto",
                    description = "Generate OrderDraftDto from Mermaid design.",
                    risk = SyncPreviewRisk.LOW,
                ),
            ),
            mermaidIssues = listOf(
                MermaidIssue(
                    category = MermaidIssue.Category.SEMANTIC,
                    code = "missing-method-signature",
                    message = "METHOD node 'draft:create-order' is missing signature metadata.",
                    line = 3,
                    nodeId = "draft:create-order",
                ),
            ),
            generationPlan = GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "Create DTO and align service wiring.",
                items = listOf(
                    GenerationPlanItem(
                        id = "gen-1",
                        title = "Create OrderDraftDto",
                        description = "Generate DTO class skeleton.",
                        risk = SyncPreviewRisk.LOW,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                ),
                warnings = listOf("Review mapper binding before applying code."),
                promptPreview = "Prompt preview",
            ),
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\nclass OrderDraftDto {}",
                ),
            ),
            generatedCodeDraftWriteReport = GeneratedCodeDraftWriteReport(
                writtenFiles = listOf("src/main/java/com/example/OrderDraftDto.java"),
                skippedFiles = emptyList(),
                warnings = emptyList(),
            ),
            lastDraftPatchApplyResult = DraftPatchApplyResult(
                summary = "已应用 1 条草稿图变更。",
                appliedOperationCount = 1,
                appliedNodeIds = listOf("note:default-fallback"),
                focusNodeId = "note:default-fallback",
                appliedTargets = listOf("DefaultFallback"),
            ),
            graphBeautificationResult = GraphBeautificationResult(
                source = LlmResultSource.MOCK,
                summaryTitle = "链路讲解占位结果",
                summary = "当前方法先做输入处理，再进入下游调用。",
                sections = listOf(
                    GraphBeautificationSection(
                        id = "anchor-method",
                        title = "当前方法内部",
                        content = "先进入 OrderController.submit，再调用后续节点。",
                    ),
                ),
                findings = listOf(
                    ResultEvidenceFinding(
                        id = "submit-direct-call",
                        claim = "当前方法直接进入 OrderController.submit。",
                        evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                        references = listOf(
                            ResultEvidenceReference(
                                nodeId = "method:submit-order",
                                filePath = "/tmp/OrderController.java",
                                startLine = 21,
                                endLine = 28,
                            ),
                        ),
                    ),
                ),
                promptPreview = "Beautification prompt preview",
                warnings = listOf("GraphBeautificationService 当前仍是占位实现。"),
            ),
            diffReviewRequestState = GraphEditorStateService.AsyncRequestState.failed("差异分析失败：HTTP 503"),
            codeDraftRequestState = GraphEditorStateService.AsyncRequestState.running(),
            operationFeedback = GraphEditorStateService.OperationFeedback(
                level = GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                message = "已加载当前方法链路：OrderController.submit",
            ),
            layoutState = GraphLayoutState(
                positions = mapOf(
                    "method:submit-order" to GraphLayoutPosition(x = 520.0, y = 260.0),
                ),
            ),
            semanticRevision = 4,
            layoutRevision = 7,
            snapshotRevision = 11,
        )

        val rendered = renderer.render(html, snapshot)

        assertTrue(rendered.contains("window.linkGraphBootstrap"))
        assertTrue(rendered.contains("OrderController.submit"))
        assertTrue(rendered.contains("src/main/java/com/example/OrderController.java:18"))
        assertTrue(rendered.contains("java.lang.String"))
        assertTrue(rendered.contains("com.example.SubmitResult"))
        assertTrue(rendered.contains("\"visibleGraph\""))
        assertTrue(rendered.contains("\"workingGraph\""))
        assertTrue(rendered.contains("\"referenceFactGraph\""))
        assertTrue(rendered.contains("\"designBaselineGraph\""))
        assertTrue(rendered.contains("\"draftPatchPreview\""))
        assertTrue(rendered.contains("\"sourceTag\":\"FACT\""))
        assertTrue(rendered.contains("\"sourceTag\":\"DRAFT_AI\""))
        assertTrue(rendered.contains("\"sourceTag\":\"DESIGN_BASELINE\""))
        assertTrue(rendered.contains("Add fallback path"))
        assertTrue(rendered.contains("ONLY_IN_MERMAID"))
        assertTrue(rendered.contains("method:submit-order"))
        assertTrue(rendered.contains("Create OrderDraftDto"))
        assertTrue(rendered.contains("missing-method-signature"))
        assertTrue(rendered.contains("METHOD node 'draft:create-order' is missing signature metadata."))
        assertTrue(rendered.contains("Create DTO and align service wiring."))
        assertTrue(rendered.contains("Create OrderDraftDto"))
        assertTrue(rendered.contains("src/main/java/com/example/OrderDraftDto.java"))
        assertFalse(rendered.contains("package com.example;"))
        assertTrue(rendered.contains("\"contentArtifactId\""))
        assertTrue(rendered.contains("\"lastDraftPatchApplyResult\""))
        assertTrue(rendered.contains("已应用 1 条草稿图变更。"))
        assertTrue(rendered.contains("DefaultFallback"))
        assertTrue(rendered.contains("\"graphBeautificationResult\""))
        assertTrue(rendered.contains("\"findings\""))
        assertTrue(rendered.contains("\"DIRECT_SOURCE\""))
        assertTrue(rendered.contains("submit-direct-call"))
        assertTrue(rendered.contains("链路讲解占位结果"))
        assertFalse(rendered.contains("Beautification prompt preview"))
        assertTrue(rendered.contains("\"promptPreviewArtifactId\""))
        assertTrue(rendered.contains("\"diffReviewRequestState\""))
        assertTrue(rendered.contains("\"codeDraftRequestState\""))
        assertTrue(rendered.contains("差异分析失败：HTTP 503"))
        assertTrue(rendered.contains("\"RUNNING\""))
        assertTrue(rendered.contains("operationFeedback"))
        assertTrue(rendered.contains("SUCCESS"))
        assertTrue(rendered.contains("已加载当前方法链路：OrderController.submit"))
        assertTrue(rendered.contains("\"callOrder\":\"0\""))
        assertTrue(rendered.contains("\"layoutState\""))
        assertTrue(rendered.contains("\"semanticRevision\":4"))
        assertTrue(rendered.contains("\"layoutRevision\":7"))
        assertTrue(rendered.contains("\"snapshotRevision\":11"))
        assertTrue(rendered.contains("\"linkGraph.manual\":\"true\""))
        assertTrue(!rendered.contains("\"ui.x\""))
        assertTrue(!rendered.contains("\"ui.y\""))
    }

    @Test
    fun bootstrapScriptDoesNotSpamConsoleWithWholeSnapshotPayload() {
        val renderer = GraphEditorPageRenderer()
        val script = renderer.bootstrapScript(
            sessionId = "session-1",
            snapshot = GraphEditorStateService.Snapshot(
                visibleGraph = GraphDocument(),
                workingGraph = GraphDocument(),
                snapshotRevision = 5,
            ),
        )

        assertTrue(script.contains("window.linkGraphBootstrap ="))
        assertTrue(script.contains("link-graph-bootstrap"))
        assertTrue(script.contains("\"sessionId\":\"session-1\""))
        assertTrue(script.contains("\"revision\":5"))
        assertTrue(!script.contains("console.log"))
    }

    @Test
    fun serializesNodePositionFromLayoutStateEvenWithoutUiMetadata() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            layoutState = GraphLayoutState(
                positions = mapOf(
                    "method:submit-order" to GraphLayoutPosition(x = 640.0, y = 320.0),
                ),
            ),
        )

        val bootstrapJson = renderer.bootstrapJson(snapshot)

        assertTrue(bootstrapJson.contains(""""position":{"""))
        assertTrue(bootstrapJson.contains(""""x":640"""))
        assertTrue(bootstrapJson.contains(""""y":320"""))
    }

    @Test
    fun bootstrapJsonUsesExplicitGraphChannels() {
        val renderer = GraphEditorPageRenderer()
        val visibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:visible-submit",
                    type = NodeType.METHOD,
                    title = "VisibleController.submit",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:working-submit",
                    type = NodeType.METHOD,
                    title = "WorkingController.submit",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                ),
            ),
        )
        val snapshot = GraphEditorStateService.Snapshot(
            visibleGraph = visibleGraph,
            workingGraph = workingGraph,
            referenceFactGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "class:fact-node",
                        type = NodeType.CLASS,
                        title = "FactNode",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            designBaselineGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "class:baseline-node",
                        type = NodeType.CLASS,
                        title = "BaselineNode",
                        sourceTag = GraphSourceTag.DESIGN_BASELINE,
                    ),
                ),
            ),
        )

        val bootstrapJson = renderer.bootstrapJson(snapshot)

        assertTrue(bootstrapJson.contains(""""visibleGraph""""))
        assertTrue(bootstrapJson.contains("VisibleController.submit"))
        assertTrue(bootstrapJson.contains("WorkingController.submit"))
        assertTrue(!bootstrapJson.contains(""""graph""""))
        assertTrue(!bootstrapJson.contains(""""factGraph""""))
        assertTrue(!bootstrapJson.contains(""""draftGraph""""))
        assertTrue(!bootstrapJson.contains(""""designBaseline""""))
    }

    @Test
    fun bootstrapJsonIncludesTypedSourceNavigationState() {
        val renderer = GraphEditorPageRenderer()

        val bootstrapJson = renderer.bootstrapJson(GraphEditorStateService.Snapshot())

        assertTrue(bootstrapJson.contains(""""sourceNavigationState""""))
        assertTrue(bootstrapJson.contains(""""phase":"IDLE""""))
    }
}
