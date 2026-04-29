package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
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
import com.charmnight.linkgraph.llm.GraphBeautificationStep
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.GraphPatchResult
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
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftValidationStatus
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionMessage
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestKind
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEditorPageRendererTest {
    @Test
    fun bootstrapJson输出问答恢复状态草稿验证和建议追问字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
            qaRequestRecoveryState = QaRequestRecoveryState(
                lastFailedRequest = ReplayableQaRequest(
                    requestId = "qa-1",
                    kind = QaRequestKind.ASK,
                    question = "这里为什么会走兜底分支？",
                    selectedNodeIds = listOf("method:submit-order"),
                    baseSession = AuditConversationSession(
                        sessionId = "audit-1",
                        scopeKey = "method:submit-order",
                    ),
                ),
            ),
            draftValidationState = DraftValidationState(
                status = DraftValidationStatus.REVIEW_REQUIRED,
                message = "当前草稿仍有待验证风险。",
                detailMessage = "请先确认这些风险是继续取证、接受、排除，还是回退对应草稿变更。",
                unresolvedThreadIds = listOf("thread-fallback"),
            ),
            generationPlanDiscussionSession = GenerationPlanDiscussionSession(
                sessionId = "plan-discussion-1",
                messages = listOf(
                    GenerationPlanDiscussionMessage(
                        messageId = "message-1",
                        role = AuditMessageRole.USER,
                        content = "为什么建议先改这个 service？",
                        focusItemId = "item-1",
                    ),
                ),
                focusItemId = "item-1",
            ),
            codeEligibilityDecision = StageEligibilityDecision(
                target = com.charmnight.linkgraph.workbench.StageEligibilityTarget.CODE,
                allowed = false,
                message = "生成代码草稿前请先处理仍会阻塞代码阶段的风险线程。",
                detailMessage = "当前仍存在暂挂风险，代码阶段不能越过这些风险直接继续生成。",
                blockingThreadIds = listOf("thread-fallback"),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"qaRequestRecoveryState\""))
        assertTrue(json.contains("\"lastFailedRequest\""))
        assertTrue(json.contains("\"requestId\":\"qa-1\""))
        assertTrue(json.contains("\"draftValidationState\""))
        assertTrue(json.contains("\"generationPlanDiscussionSession\""))
        assertTrue(json.contains("\"focusItemId\":\"item-1\""))
        assertTrue(json.contains("\"codeEligibilityDecision\""))
        assertTrue(json.contains("\"blockingThreadIds\":[\"thread-fallback\"]"))
    }

    @Test
    fun bootstrapJson为缺省草稿验证说明输出null而不是空字符串() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
            draftValidationState = DraftValidationState(
                status = DraftValidationStatus.READY,
                message = "当前草稿已完成验证，可以继续生成实现建议或代码 diff。",
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"draftValidationState\""))
        assertTrue(json.contains("\"detailMessage\":null"))
        assertFalse(json.contains("\"detailMessage\":\"\""))
    }

    @Test
    fun bootstrapJson输出workspaceBaseGraph字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
            visibleGraph = GraphDocument(),
            workingGraph = GraphDocument(),
            referenceWorkingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:reference-working",
                        type = NodeType.METHOD,
                        title = "ReferenceWorkingGraph.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"workspaceBaseGraph\""))
        assertTrue(json.contains("\"method:reference-working\""))
    }

    @Test
    fun bootstrapJson保持当前图内容而不再执行旧版草稿工件清洗() {
        val renderer = GraphEditorPageRenderer()
        val workingGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit-order",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "draft-entry:change-submit-order",
                    type = NodeType.DOC_PAGE,
                    title = "draft projection",
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    metadata = mapOf("draft.entryId" to "change-submit-order"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "links:submit-order->draft-projection",
                    type = com.charmnight.linkgraph.model.EdgeType.LINKS_DOC,
                    fromNodeId = "method:submit-order",
                    toNodeId = "draft-entry:change-submit-order",
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    metadata = mapOf("draft.entryId" to "change-submit-order"),
                ),
            ),
        )
        val snapshot = testSnapshot(
            visibleGraph = workingGraph,
            workingGraph = workingGraph,
            factGraphView = FactGraphViewDocument(
                visibleGraph = workingGraph,
                fullGraph = workingGraph,
                anchorNodeId = "method:submit-order",
                summary = FactGraphSummary(
                    anchorTitle = "OrderController.submit",
                    visibleNodeCount = 2,
                    fullNodeCount = 2,
                ),
            ),
            flowchartView = FlowchartViewDocument(
                visibleGraph = workingGraph,
                fullGraph = workingGraph,
                anchorNodeId = "method:submit-order",
                summary = FlowchartSummary(
                    nodeCount = 2,
                    branchCount = 0,
                    exceptionPathCount = 0,
                ),
            ),
            resourceRelationView = ResourceRelationViewDocument(
                visibleGraph = workingGraph,
                fullGraph = workingGraph,
                anchorNodeId = "method:submit-order",
                summary = ResourceRelationSummary(
                    visibleNodeCount = 2,
                    laneCounts = mapOf("CODE" to 2),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("draft-entry:change-submit-order"))
        assertTrue(json.contains("links:submit-order-\\u003Edraft-projection"))
    }

    @Test
    fun bootstrapJson输出展示模式字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
        val snapshot = testSnapshot(
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
            auditRequestState = com.charmnight.linkgraph.ui.AsyncRequestState.running(
                statusMessage = "正在等待远程 LLM 问答响应",
                detailMessage = "当前采用完整返回，不是流式输出。",
                startedAtEpochMillis = 1_710_000_000_000,
                streaming = false,
            ).copy(
                requestId = 17,
                scene = "问答",
                executionMode = com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.REMOTE_READY,
                providerLabel = "OpenAI Compatible",
                model = "gpt-test",
                endpointSummary = "example.com/v1/chat/completions",
                promptPreviewAvailable = true,
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"requestId\":17"))
        assertTrue(json.contains("\"scene\":\"问答\""))
        assertTrue(json.contains("\"executionMode\":\"REMOTE_READY\""))
        assertTrue(json.contains("\"providerLabel\":\"OpenAI Compatible\""))
        assertTrue(json.contains("\"model\":\"gpt-test\""))
        assertTrue(json.contains("\"endpointSummary\":\"example.com/v1/chat/completions\""))
        assertTrue(json.contains("\"promptPreviewAvailable\":true"))
    }

    @Test
    fun bootstrapJson输出runtimeArtifactSummaries字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
            runtimeArtifactSummaries = mapOf(
                "qa" to listOf(
                    com.charmnight.linkgraph.ui.RuntimeArtifactSummary(
                        artifactId = "qa-graph-summary",
                        artifactType = "GRAPH_SUMMARY",
                        title = "图摘要",
                        description = "workingGraph",
                    ),
                    com.charmnight.linkgraph.ui.RuntimeArtifactSummary(
                        artifactId = "qa-conclusion",
                        artifactType = "QA_CONCLUSION",
                        title = "问答结论",
                        description = "已完成问答。",
                    ),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"runtimeArtifactSummaries\""))
        assertTrue(json.contains("\"qa\""))
        assertTrue(json.contains("\"artifactType\":\"GRAPH_SUMMARY\""))
        assertTrue(json.contains("\"title\":\"问答结论\""))
    }

    @Test
    fun bootstrapJson输出工作台折叠偏好字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
            workbenchSectionPreferences = mapOf(
                "audit.request-status" to true,
                "audit.candidate-changes" to false,
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"workbenchSectionPreferences\""))
        assertTrue(json.contains("\"audit.request-status\":true"))
        assertTrue(json.contains("\"audit.candidate-changes\":false"))
    }

    @Test
    fun bootstrapJson输出草稿图补丁字段() {
        val renderer = GraphEditorPageRenderer()
        val graphPatch = GraphPatch(
            summary = "补充路径调整说明节点",
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
                        sourceTag = GraphSourceTag.DRAFT_AI,
                    ),
                ),
            ),
            addedNodeIds = listOf("draft-note:change-upload-condition"),
        )
        val snapshot = testSnapshot(
            visibleGraph = GraphDocument(),
            workingGraph = GraphDocument(),
            draftWorkbenchState = DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-upload-condition",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传路径固定值",
                        sourceChangeId = "change-upload-condition",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                        reason = "旧路径已经废弃。",
                        impactSummary = "上传流程固定写入新路径。",
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
                        graphPatch = graphPatch,
                    ),
                ),
            ),
            auditResult = GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "请确认这条路径调整",
                answer = "建议补充路径调整说明节点。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-upload-condition",
                        status = CandidateDraftChangeStatus.CONFIRMED,
                        title = "修改上传路径固定值",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                        reason = "旧路径已经废弃。",
                        impactSummary = "上传流程固定写入新路径。",
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-condition",
                                claim = "当前源码里直接能看到上传条件判断。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload-condition")),
                            ),
                        ),
                        graphPatch = graphPatch,
                    ),
                ),
            ),
        )

        val json = renderer.bootstrapJson(snapshot)

        assertTrue(json.contains("\"graphPatch\""))
        assertTrue(json.contains("\"patch-op-upload-note\""))
        assertTrue(json.contains("\"draft-note:change-upload-condition\""))
    }

    @Test
    fun bootstrapJson输出流式预览字段() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
            graphBeautificationRequestState = com.charmnight.linkgraph.ui.AsyncRequestState.running(
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
        val snapshot = testSnapshot(
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
                    id = "method:flow-entry",
                    type = NodeType.METHOD,
                    title = "FlowEntry",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
            ),
        )
        val snapshot = testSnapshot(
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
                fullGraph = flowchartFullGraph,
                anchorNodeId = "method:flow-entry",
                summary = FlowchartSummary(
                    nodeCount = 1,
                    branchCount = 0,
                    exceptionPathCount = 0,
                    fullNodeCount = 3,
                    fullEdgeCount = 2,
                    hiddenNodeCount = 2,
                    hiddenEdgeCount = 1,
                    truncated = true,
                    incompleteNodeCount = 1,
                    incompleteEdgeCount = 0,
                    semanticallyIncomplete = true,
                    syntheticEdgeCount = 0,
                    syntheticEntryEdgeCount = 0,
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
        assertTrue(json.contains("\"workspaceGraph\""))
        assertTrue(json.contains("action:guard-condition"))
        assertTrue(json.contains("FactAnchor"))
        assertTrue(json.contains("FlowEntry"))
        assertTrue(json.contains("order_mapper.xml#insertOrder"))
        assertTrue(json.contains("\"anchorTitle\":\"FactAnchor\""))
        assertTrue(json.contains("\"visibleNodeCount\":1"))
        assertTrue(json.contains("\"branchCount\":0"))
        assertTrue(json.contains("\"exceptionPathCount\":0"))
        assertTrue(json.contains("\"fullNodeCount\":3"))
        assertFalse(json.contains("\"hiddenNodeCount\":2"))
        assertFalse(json.contains("\"hiddenEdgeCount\":1"))
        assertFalse(json.contains("\"truncated\":true"))
        assertTrue(json.contains("\"incompleteNodeCount\":1"))
        assertTrue(json.contains("\"semanticallyIncomplete\":true"))
        assertTrue(json.contains("\"laneCounts\":{\"DATA\":1}"))
    }

    @Test
    fun rendersBootstrapStateIntoFrontendHtml() {
        val renderer = GraphEditorPageRenderer()
        val artifactRegistry = GraphEditorArtifactRegistry()
        val html = """
            <html>
              <head><title>Link Graph</title></head>
              <body><div id="root"></div></body>
            </html>
        """.trimIndent()
        val snapshot = testSnapshot(
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
                        summary = "把问答建议写入草稿层",
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
            draftVersion = 4,
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
            generationPlanDraftVersion = 3,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\nclass OrderDraftDto {}",
                ),
                GeneratedCodeDraft(
                    id = "draft-2",
                    sourceNodeId = "method:submit-order",
                    title = "OrderController.java",
                    targetPath = "src/main/java/com/example/OrderController.java",
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "edit-1",
                            filePath = "src/main/java/com/example/OrderController.java",
                            scopeId = "scope-submit-order",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                            payload = "public SubmitResult submit(String request) {\n    return fallback(request);\n}",
                        ),
                    ),
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-submit-order",
                            targetNodeId = "method:submit-order",
                            filePath = "src/main/java/com/example/OrderController.java",
                            language = "JAVA",
                            symbolKind = "METHOD",
                            symbolSignature = "com.example.OrderController#submit(java.lang.String)",
                            startLine = 18,
                            endLine = 27,
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            supportingFindingIds = listOf("submit-direct-call"),
                        ),
                    ),
                    warnings = listOf("Only the submit method is writable."),
                ),
            ),
            generatedCodeDraftVersion = 2,
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
                granularity = StepGranularity.BUSINESS,
                steps = listOf(
                    GraphBeautificationStep(
                        stepId = "step-submit-order",
                        title = "当前方法内部",
                        granularity = StepGranularity.BUSINESS,
                        kind = StepKind.BUSINESS_ACTION,
                        description = "先进入 OrderController.submit，再调用后续节点。",
                        primaryNodeId = "method:submit-order",
                        codeSnippet = "orderService.submit(request);",
                        evidence = listOf(
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
                    ),
                ),
                promptPreview = "Beautification prompt preview",
                warnings = listOf("GraphBeautificationService 当前仍是占位实现。"),
            ),
            diffReviewRequestState = com.charmnight.linkgraph.ui.AsyncRequestState.failed("差异分析失败：HTTP 503"),
            codeDraftRequestState = com.charmnight.linkgraph.ui.AsyncRequestState.running(),
            operationFeedback = com.charmnight.linkgraph.ui.OperationFeedback(
                level = com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                message = "已加载当前编辑器上下文链路：OrderController.submit",
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

        val artifactRefs = artifactRegistry.replaceWith(snapshot)
        val rendered = renderer.render(
            entryHtml = html,
            sessionId = "session-1",
            snapshot = snapshot,
            artifactRefs = artifactRefs,
        )

        assertTrue(rendered.contains("window.linkGraphBootstrap"))
        assertTrue(rendered.contains("OrderController.submit"))
        assertTrue(rendered.contains("src/main/java/com/example/OrderController.java:18"))
        assertTrue(rendered.contains("java.lang.String"))
        assertTrue(rendered.contains("com.example.SubmitResult"))
        assertTrue(rendered.contains("\"workspaceGraph\""))
        assertTrue(rendered.contains("\"workspaceBaseGraph\""))
        assertTrue(rendered.contains("\"semanticFactGraph\""))
        assertTrue(rendered.contains("\"sceneStates\""))
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
        assertTrue(rendered.contains("\"editScopes\""))
        assertTrue(rendered.contains("scope-submit-order"))
        assertTrue(rendered.contains("\"allowedChangeKinds\":[\"REPLACE_METHOD_BLOCK\"]"))
        assertTrue(rendered.contains("\"editOperations\""))
        assertTrue(rendered.contains("\"kind\":\"REPLACE_METHOD_BLOCK\""))
        assertTrue(rendered.contains("Only the submit method is writable."))
        assertTrue(rendered.contains("\"lastDraftPatchApplyResult\""))
        assertTrue(rendered.contains("已应用 1 条草稿图变更。"))
        assertTrue(rendered.contains("DefaultFallback"))
        assertTrue(rendered.contains("\"graphBeautificationResult\""))
        assertTrue(rendered.contains("\"steps\""))
        assertTrue(rendered.contains("\"evidence\""))
        assertTrue(rendered.contains("\"DIRECT_SOURCE\""))
        assertTrue(rendered.contains("submit-direct-call"))
        assertTrue(rendered.contains("step-submit-order"))
        assertFalse(rendered.contains("Beautification prompt preview"))
        assertTrue(rendered.contains("\"promptPreviewArtifactId\""))
        assertTrue(rendered.contains("\"diffReviewRequestState\""))
        assertTrue(rendered.contains("\"codeDraftRequestState\""))
        assertTrue(rendered.contains("\"draftVersion\":4"))
        assertTrue(rendered.contains("\"generationPlanDraftVersion\":3"))
        assertTrue(rendered.contains("\"generatedCodeDraftVersion\":2"))
        assertTrue(rendered.contains("差异分析失败：HTTP 503"))
        assertTrue(rendered.contains("\"RUNNING\""))
        assertTrue(rendered.contains("operationFeedback"))
        assertTrue(rendered.contains("SUCCESS"))
        assertTrue(rendered.contains("已加载当前编辑器上下文链路：OrderController.submit"))
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
            snapshot = testSnapshot(
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
    fun debugBootstrapScriptEnablesEarlyFrontendTraceWithoutLoggingWholeSnapshotPayload() {
        val renderer = GraphEditorPageRenderer()
        val script = renderer.bootstrapScript(
            sessionId = "session-1",
            snapshot = testSnapshot(
                visibleGraph = GraphDocument(),
                workingGraph = GraphDocument(),
                snapshotRevision = 5,
            ),
            debugTracingEnabled = true,
        )

        assertTrue(script.contains("window.__linkGraphDebugEnabled = true"))
        assertTrue(script.contains("link-graph bootstrap start"))
        assertTrue(script.contains("link-graph bootstrap dispatched"))
        assertTrue(!script.contains("console.log(window.linkGraphBootstrap"))
    }

    @Test
    fun serializesNodePositionFromLayoutStateEvenWithoutUiMetadata() {
        val renderer = GraphEditorPageRenderer()
        val snapshot = testSnapshot(
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
    fun serializesFlowchartViewNodePositionFromLayoutStateAfterDraftRebuild() {
        val renderer = GraphEditorPageRenderer()
        val flowchartVisibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "scope:delete-file",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (Boolean.TRUE.equals(delete))",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
                GraphNode(
                    id = "draft:file-exists-check",
                    type = NodeType.FLOW_ACTION,
                    title = "Files.exists(Path.of(filePath))",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                    metadata = mapOf("flowchart.kind" to "PROCESS"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-delete",
                    type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:delete-file",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "edge:delete-exists",
                    type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:delete-file",
                    toNodeId = "draft:file-exists-check",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                ),
            ),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = flowchartVisibleGraph,
            workingGraph = flowchartVisibleGraph,
            flowchartView = FlowchartViewDocument(
                visibleGraph = flowchartVisibleGraph,
                fullGraph = flowchartVisibleGraph,
                anchorNodeId = "scope:delete-file",
                summary = FlowchartSummary(
                    nodeCount = 3,
                    branchCount = 1,
                    fullNodeCount = 3,
                    fullEdgeCount = 2,
                ),
            ),
            layoutState = GraphLayoutState(
                positions = mapOf(
                    "method:file-download" to GraphLayoutPosition(x = 32.0, y = 24.0),
                    "scope:delete-file" to GraphLayoutPosition(x = 352.0, y = 24.0),
                    "draft:file-exists-check" to GraphLayoutPosition(x = 512.0, y = 24.0),
                ),
            ),
        )

        val bootstrapJson = renderer.bootstrapJson(snapshot)

        assertTrue(bootstrapJson.contains(""""flowchartView""""))
        assertTrue(bootstrapJson.contains(""""scope:delete-file""""))
        assertTrue(bootstrapJson.contains(""""draft:file-exists-check""""))
        assertTrue(
            bootstrapJson.contains(""""position":{"x":352.0,"y":24.0}""") ||
                bootstrapJson.contains(""""position":{"x":352,"y":24}"""),
        )
        assertTrue(
            bootstrapJson.contains(""""position":{"x":512.0,"y":24.0}""") ||
                bootstrapJson.contains(""""position":{"x":512,"y":24}"""),
        )
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
        val snapshot = testSnapshot(
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

        val bootstrapJson = renderer.bootstrapJson(testSnapshot())

        assertTrue(bootstrapJson.contains(""""sourceNavigationState""""))
        assertTrue(bootstrapJson.contains(""""phase":"IDLE""""))
    }

    @Test
    fun bootstrapJsonIncludesWorkbenchConversationAndDraftState() {
        val renderer = GraphEditorPageRenderer()
        val candidate = CandidateDraftChange(
            changeId = "change-upload-condition",
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = "修改上传条件判断",
            targetStepIds = listOf("step-upload-condition"),
            targetNodeIds = listOf("flow-action:condition"),
            beforeState = "if (a > 10)",
            afterState = "if (a < 100)",
            reason = "业务条件写反了。",
            impactSummary = "影响主流程分支。",
            claimType = "CODE_FACT",
            evidence = listOf(
                com.charmnight.linkgraph.llm.ResultEvidenceFinding(
                    id = "finding-upload-condition",
                    claim = "当前源码里直接能看到上传条件判断。",
                    evidenceLevel = com.charmnight.linkgraph.llm.ResultEvidenceLevel.DIRECT_SOURCE,
                    references = listOf(
                        com.charmnight.linkgraph.llm.ResultEvidenceReference(
                            nodeId = "flow-action:condition",
                        ),
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(
            auditResult = com.charmnight.linkgraph.llm.GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "这里是不是有问题？",
                answer = "建议修改条件判断。",
                promptPreview = "prompt",
                candidateChanges = listOf(candidate),
                newCandidateChanges = listOf(candidate),
                auditSession = AuditConversationSession(
                    sessionId = "audit-method-submit",
                    scopeKey = "method:submit",
                    messages = listOf(
                        AuditConversationMessage(
                            messageId = "m-1",
                            role = AuditMessageRole.USER,
                            content = "这里是不是有问题？",
                        ),
                    ),
                    candidateChanges = listOf(candidate),
                    focusTargetId = candidate.changeId,
                ),
            ),
            draftWorkbenchState = DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传条件判断",
                        sourceChangeId = candidate.changeId,
                        targetStepIds = listOf("step-upload-condition"),
                        targetNodeIds = listOf("flow-action:condition"),
                        afterState = "if (a < 100)",
                        reason = "业务条件写反了。",
                    ),
                ),
                draftNotes = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-note-1",
                        kind = DraftEntryKind.NOTE,
                        title = "上传目录说明",
                        targetStepIds = listOf("step-read-upload-dir"),
                        afterState = "上传目录来自租户配置。",
                        reason = "讲解中手动加入。",
                    ),
                ),
            ),
        )

        val bootstrapJson = renderer.bootstrapJson(snapshot)

        assertTrue(bootstrapJson.contains(""""draftWorkbenchState""""))
        assertTrue(bootstrapJson.contains(""""candidateChanges""""))
        assertTrue(bootstrapJson.contains(""""newCandidateChanges""""))
        assertTrue(bootstrapJson.contains(""""auditSession""""))
        assertTrue(bootstrapJson.contains(""""scopeKey":"method:submit""""))
        assertTrue(bootstrapJson.contains(""""claimType":"CODE_FACT""""))
        assertTrue(bootstrapJson.contains(""""evidenceLevel":"DIRECT_SOURCE""""))
        assertTrue(bootstrapJson.contains(""""kind":"CHANGE""""))
        assertTrue(bootstrapJson.contains(""""kind":"NOTE""""))
        assertFalse(bootstrapJson.contains(""""investigationLeads""""))
    }
}
