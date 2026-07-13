package com.charmnight.linkgraph.application
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.application.planning.QaEvidenceCollector
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.settings.LlmProviderPresets
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.agent.model.ResultEvidenceLevel
import com.charmnight.linkgraph.agent.model.ResultEvidenceReference
import com.charmnight.linkgraph.agent.capability.QaCapability
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcomeStatus
import com.charmnight.linkgraph.workbench.QaMode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

class ReviewWorkflowAgentRuntimeTest : BasePlatformTestCase() {
    fun testRequestQaReturnsControlledFailureWhenRuntimeOutputIsNull() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    defaultBudget = RunBudget(maxSteps = 0).recordStep(),
                    qaExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "不应该执行到这里。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync("请围绕当前链路进行问答")
        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertNull(snapshot.qaResult)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED, snapshot.qaRequestState.phase)
        assertTrue(snapshot.qaRequestState.errorMessage?.contains("runtime 未返回结果") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("failureReason=MAX_STEPS_EXCEEDED") == true)
    }

    fun testRequestQaUsesArchitectureVisibleGraphWhenWorkspaceGraphIsEmpty() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val architectureGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "arch:component:com.example.application",
                    type = NodeType.COMPONENT,
                    title = "com.example.application",
                ),
            ),
        )
        stateService.loadArchitectureGraphView(
            ArchitectureGraphResult(
                visibleGraph = architectureGraph,
                fullGraph = architectureGraph,
                anchorNodeId = "arch:component:com.example.application",
            ),
        )
        var executorGraphNodeCount = -1
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        executorGraphNodeCount = input.qaContext.editableGraph.nodes.size
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "architecture qa ok",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync("请介绍下这个项目结构和作用")

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("architecture qa ok", snapshot.qaResult?.answer)
        assertEquals(1, executorGraphNodeCount)
    }

    fun testRequestQaAsyncRoutesThroughRuntimeAndKeepsAsyncLifecycleState() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                qaEvidenceCollector = QaEvidenceCollector(maxSnippets = 0),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "runtime 已接管问答入口。",
                            promptPreview = "prompt",
                            warnings = emptyList(),
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync("请围绕当前链路进行问答")

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.qaRequestState.phase)
        assertEquals("runtime 已接管问答入口。", snapshot.qaResult?.answer)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("runId=") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("capability=qa") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("step[0]") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("tool=get_draft_workbench") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("budget steps=") == true)
        val artifactTitles = snapshot.runtimeArtifactSummaries["qa"]?.map { it.title }.orEmpty()
        assertTrue(artifactTitles.contains("图摘要"))
        assertTrue(artifactTitles.contains("问答结论"))
        assertEquals("问答结论", artifactTitles.lastOrNull())
    }

    fun testRequestQaAsyncAnswerModeReappliesBoundaryAfterConversationMerge() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "历史问题",
                answer = "历史回答",
                promptPreview = "prompt",
                qaSession = QaConversationSession(
                    sessionId = "session-answer-boundary",
                    scopeKey = "method:upload-file",
                    candidateChanges = listOf(
                        CandidateDraftChange(
                            changeId = "old-change",
                            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                            title = "历史候选",
                            targetNodeIds = listOf("method:upload-file"),
                            evidence = listOf(
                                ResultEvidenceFinding(
                                    id = "old-change-evidence",
                                    claim = "历史候选证据。",
                                    evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                    references = listOf(ResultEvidenceReference(nodeId = "method:upload-file")),
                                ),
                            ),
                        ),
                    ),
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "old-thread",
                            status = InvestigationThreadStatus.OPEN,
                            title = "历史风险",
                            targetNodeIds = listOf("method:upload-file"),
                            evidence = listOf(
                                ResultEvidenceFinding(
                                    id = "old-thread-evidence",
                                    claim = "历史风险证据。",
                                    evidenceLevel = ResultEvidenceLevel.DIRECT_GRAPH,
                                    references = listOf(ResultEvidenceReference(nodeId = "method:upload-file")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "只回答模式不应保留历史候选或风险线程。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请解释这个方法",
            selectedNodeIds = listOf("method:upload-file"),
            mode = QaMode.ANSWER,
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertTrue(snapshot.qaResult?.candidateChanges?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.newCandidateChanges?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.investigationThreads?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.qaSession?.candidateChanges?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.qaSession?.investigationThreads?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.qaSession?.turnOutcomes?.isEmpty() == true)
    }

    fun testRequestQaAsyncReadsCodeEvidenceBeforeQaExecutor() {
        val sourceFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowQaUploadService.java",
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:upload-file",
                        type = NodeType.METHOD,
                        title = "UploadService.submit",
                        signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "已读取${input.qaContext.sourceContext.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请结合代码解释这里为什么会走 fallback",
            selectedNodeIds = listOf("method:upload-file"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已读取1段代码证据。", snapshot.qaResult?.answer)
        assertEquals(QaMode.AUTO, snapshot.qaResult?.requestedMode)
        assertEquals(QaMode.ANSWER, snapshot.qaResult?.effectiveMode)
        assertEquals(QaMode.AUTO, snapshot.qaRequestState.requestedMode)
        assertEquals(QaMode.ANSWER, snapshot.qaRequestState.effectiveMode)
        assertTrue(snapshot.runtimeArtifactSummaries["qa"]?.isNotEmpty() == true)
    }

    fun testRequestQaAsyncReadsAdjacentCallEvidenceForExplicitSelection() {
        val controllerFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowDownloadController.java",
            """
            class CommonController {
                String fileDownload(String fileName) {
                    return RuoYiConfig.getDownloadPath() + fileName;
                }
            }
            """.trimIndent(),
        )
        val configFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowDownloadConfig.java",
            """
            class RuoYiConfig {
                static String getDownloadPath() {
                    return "/profile/download/";
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:file-download",
                        type = NodeType.METHOD,
                        title = "CommonController.fileDownload",
                        signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to controllerFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                    GraphNode(
                        id = "method:get-download-path",
                        type = NodeType.METHOD,
                        title = "RuoYiConfig.getDownloadPath",
                        signature = "com.example.RuoYiConfig.getDownloadPath():java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to configFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
                edges = listOf(
                    GraphEdge(
                        id = "edge:file-download->get-download-path",
                        type = EdgeType.CALL,
                        fromNodeId = "method:file-download",
                        toNodeId = "method:get-download-path",
                    ),
                ),
            ),
            "currentMethod",
        )
        var capturedNodeIds: List<String> = emptyList()
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        capturedNodeIds = input.qaContext.sourceContext.map { it.nodeId }
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "已读取${capturedNodeIds.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请继续取证：确认下载路径配置是如何解析的",
            selectedNodeIds = listOf("method:file-download"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已读取2段代码证据。", snapshot.qaResult?.answer)
        assertEquals(
            listOf("method:file-download", "method:get-download-path"),
            capturedNodeIds,
        )
    }

    fun testRequestQaAsyncRoutesThreadInvestigationThroughDeterministicPipeline() {
        myFixture.configureByText(
            "TaskQueueEventType.java",
            """
            package com.example.queue;

            enum TaskQueueEventType {
                ADD,
                REMOVE
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:add-pool",
                        type = NodeType.METHOD,
                        title = "PoolService.addPool",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                    ),
                ),
            ),
            "currentMethod",
        )
        stateService.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "历史问题",
                answer = "历史回答",
                promptPreview = "prompt",
                qaSession = QaConversationSession(
                    sessionId = "session-add-pool",
                    scopeKey = "method:add-pool",
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-add-event-type",
                            status = InvestigationThreadStatus.OPEN,
                            title = "TaskQueueEventType ADD 分支待确认",
                            targetNodeIds = listOf("method:add-pool"),
                            summary = "当前图里没有 TaskQueueEventType 的定义。",
                            evidenceGap = "缺少 TaskQueueEventType.ADD 的直接源码证据。",
                            recommendedQuestion = "请继续取证：定位 TaskQueueEventType.ADD。",
                            claimType = "RISK_HINT",
                            evidence = listOf(
                                ResultEvidenceFinding(
                                    id = "add-event-type-missing",
                                    claim = "当前只看到 addPool 调用点。",
                                    evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                                    references = listOf(ResultEvidenceReference(nodeId = "method:add-pool")),
                                ),
                            ),
                        ),
                    ),
                    focusTargetId = "thread-add-event-type",
                ),
            ),
        )
        var qaExecutorInvoked = false
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        qaExecutorInvoked = true
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "普通 QA 不应处理继续取证。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请继续取证：定位 TaskQueueEventType.ADD。",
            selectedNodeIds = listOf("method:add-pool"),
            sourceThreadId = "thread-add-event-type",
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertFalse(qaExecutorInvoked)
        assertTrue(snapshot.qaResult?.answer?.contains("本轮已确认直接证据") == true)
        assertTrue(snapshot.qaResult?.findings?.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE &&
                finding.references.any { reference ->
                    reference.filePath?.endsWith("TaskQueueEventType.java") == true
                }
        } == true)
        assertEquals(QaMode.AUTO, snapshot.qaResult?.requestedMode)
        assertEquals(QaMode.INVESTIGATE, snapshot.qaResult?.effectiveMode)
        assertEquals(QaMode.AUTO, snapshot.qaRequestState.requestedMode)
        assertEquals(QaMode.INVESTIGATE, snapshot.qaRequestState.effectiveMode)
    }

    fun testRequestQaAsyncDoesNotInvokeQaWhenInvestigationHasNoAcceptedEvidence() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:add-pool",
                        type = NodeType.METHOD,
                        title = "PoolService.addPool",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                    ),
                ),
            ),
            "currentMethod",
        )
        stateService.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "历史问题",
                answer = "历史回答",
                promptPreview = "prompt",
                qaSession = QaConversationSession(
                    sessionId = "session-missing-event-type",
                    scopeKey = "method:add-pool",
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-missing-event-type",
                            status = InvestigationThreadStatus.OPEN,
                            title = "MissingEventType ADD 分支待确认",
                            targetNodeIds = listOf("method:add-pool"),
                            evidenceGap = "缺少 MissingEventType.ADD 的直接源码证据。",
                            recommendedQuestion = "请继续取证：定位 MissingEventType.ADD。",
                            claimType = "RISK_HINT",
                        ),
                        InvestigationThread(
                            threadId = "thread-unrelated",
                            status = InvestigationThreadStatus.OPEN,
                            title = "不相关风险线程",
                            targetNodeIds = listOf("method:other"),
                            evidenceGap = "这条线程不属于本轮继续取证。",
                            recommendedQuestion = "稍后单独继续取证。",
                            claimType = "RISK_HINT",
                        ),
                    ),
                    focusTargetId = "thread-missing-event-type",
                ),
            ),
        )
        var qaExecutorInvoked = false
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        qaExecutorInvoked = true
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "普通 QA 不应处理无证据继续取证。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请继续取证：定位 MissingEventType.ADD。",
            selectedNodeIds = emptyList(),
            sourceThreadId = "thread-missing-event-type",
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertFalse(qaExecutorInvoked)
        assertTrue(snapshot.qaResult?.answer?.contains("没有拿到可进入 LLM 上下文的直接证据") == true)
        assertTrue(snapshot.qaResult?.findings?.isEmpty() == true)
        assertTrue(snapshot.qaResult?.warnings?.any { warning ->
            warning.contains("没有拿到可进入 LLM 上下文的直接证据")
        } == true)
        assertEquals(
            listOf("thread-missing-event-type"),
            snapshot.qaResult?.investigationThreads?.map(InvestigationThread::threadId),
        )
        val sessionThreads = snapshot.qaResult?.qaSession?.investigationThreads.orEmpty()
        assertEquals(
            setOf("thread-missing-event-type", "thread-unrelated"),
            sessionThreads.map(InvestigationThread::threadId).toSet(),
        )
        val focusedThread = sessionThreads.single { thread -> thread.threadId == "thread-missing-event-type" }
        assertTrue(focusedThread.targetNodeIds.contains("method:add-pool"))
        assertEquals(InvestigationThreadStatus.BLOCKED, focusedThread.status)
        assertEquals(InvestigationTurnOutcomeStatus.BLOCKED, snapshot.qaResult?.latestTurnOutcome?.status)
    }

    fun testRequestQaAsyncRecordsFailureWhenInvestigationPipelineThrows() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:add-pool",
                        type = NodeType.METHOD,
                        title = "PoolService.addPool",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                    ),
                ),
            ),
            "currentMethod",
        )
        var qaExecutorInvoked = false
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        qaExecutorInvoked = true
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "普通 QA 不应处理继续取证失败用例。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
            investigationPipelineFactory = {
                error("pipeline boom")
            },
        )

        workflow.requestQaAsync(
            question = "请继续取证：定位 MissingEventType.ADD。",
            selectedNodeIds = listOf("method:add-pool"),
            sourceThreadId = "thread-missing-event-type",
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertFalse(qaExecutorInvoked)
        assertTrue(snapshot.qaRequestState.errorMessage?.contains("继续取证失败：pipeline boom") == true)
        assertEquals(com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message?.contains("继续取证失败：pipeline boom") == true)
        assertEquals("thread-missing-event-type", snapshot.qaRequestRecoveryState.lastFailedRequest?.sourceThreadId)
        assertEquals(QaMode.AUTO, snapshot.qaRequestRecoveryState.lastFailedRequest?.mode)
    }

    fun testRequestQaAsyncBuildsCandidateChangeFromRuntimeCodeEvidenceInMockMode() {
        val sourceFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowDirectSourceController.java",
            """
            class CommonController {
                void fileDownload(String fileName, Boolean delete) {
                    if (delete) {
                        FileUtils.deleteFile(fileName);
                    }
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:file-download",
                        type = NodeType.METHOD,
                        title = "CommonController.fileDownload",
                        signature = "com.example.CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "7",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = {
                    LinkGraphSettingsState(
                        llmEnabled = true,
                        provider = LlmProviderPresets.MOCK.id,
                    )
                },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = {
                LinkGraphSettingsState(
                    llmEnabled = true,
                    provider = LlmProviderPresets.MOCK.id,
                )
            },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
        )

        workflow.requestQaAsync(
            question = "请把这里的 if(delete) 改成 delete == true，并在删除前校验 filePath 是否存在。",
            selectedNodeIds = listOf("method:file-download"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(LlmResultSource.LOCAL_RULE, snapshot.qaResult?.source)
        assertEquals(1, snapshot.qaResult?.candidateChanges?.size)
        assertTrue(snapshot.qaResult?.investigationThreads?.isEmpty() == true)
        assertEquals("method:file-download", snapshot.qaResult?.candidateChanges?.single()?.targetNodeIds?.single())
        assertEquals(
            sourceFile.toString(),
            snapshot.qaResult?.candidateChanges?.single()?.editScopes?.singleOrNull()?.filePath,
        )
        assertTrue(snapshot.qaResult?.answer?.contains("待确认变更") == true)
    }

    fun testRequestQaAsyncPreservesFactBaselineAndEditableWorkingGraph() {
        val factMethod = GraphNode(
            id = "method:file-download",
            type = NodeType.METHOD,
            title = "CommonController.fileDownload",
            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val editableDecision = GraphNode(
            id = "scope:file-download-if",
            type = NodeType.FLOW_SCOPE,
            title = "if (delete)",
            provenance = GraphProvenance.USER_DRAFT,
            metadata = mapOf(
                "flowchart.kind" to "DECISION",
                "flow.ownerMethod" to "com.example.CommonController.fileDownload(java.lang.String):void",
            ),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(GraphDocument(nodes = listOf(factMethod)), "currentMethod")
        stateService.markGraphChanged(
            GraphDocument(
                nodes = listOf(factMethod, editableDecision),
                edges = listOf(
                    GraphEdge(
                        id = "edge:file-download->if-delete",
                        type = EdgeType.CONTROL_FLOW,
                        fromNodeId = factMethod.id,
                        toNodeId = editableDecision.id,
                        provenance = GraphProvenance.USER_DRAFT,
                    ),
                ),
            ),
        )
        var capturedQaContext: GraphQaContext? = null
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        capturedQaContext = input.qaContext
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "已捕获 QA 图上下文。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请确认删除分支是否属于当前方法流程",
            selectedNodeIds = listOf(factMethod.id),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已捕获 QA 图上下文。", snapshot.qaResult?.answer)
        assertEquals(setOf(factMethod.id), capturedQaContext?.factGraph?.nodes?.map(GraphNode::id)?.toSet())
        assertEquals(
            setOf(factMethod.id, editableDecision.id),
            capturedQaContext?.editableGraph?.nodes?.map(GraphNode::id)?.toSet(),
        )
        assertEquals(listOf(factMethod.id), capturedQaContext?.selectedNodeIds)
    }

    fun testRequestQaAsyncReadsWholeGraphCodeEvidenceWithoutExplicitSelection() {
        val uploadFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowWholeGraphUploadService.java",
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val qaFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowWholeGraphQaService.java",
            """
            class QaService {
                boolean shouldAnswer(String request) {
                    return request != null && !request.isBlank();
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:upload-file",
                        type = NodeType.METHOD,
                        title = "UploadService.submit",
                        signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to uploadFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                    GraphNode(
                        id = "method:should-answer",
                        type = NodeType.METHOD,
                        title = "QaService.shouldAnswer",
                        signature = "com.example.QaService.shouldAnswer(java.lang.String):boolean",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to qaFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 2_000L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "整图读取${input.qaContext.sourceContext.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请围绕整图解释 fallback 和审核判断是怎么串起来的",
            selectedNodeIds = emptyList(),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertTrue(snapshot.qaResult?.answer?.contains("整图读取") == true)
        assertTrue(snapshot.qaResult?.answer?.contains("0段") == false)
        assertTrue(snapshot.runtimeArtifactSummaries["qa"]?.any { it.title == "代码证据" } == true)
    }

    fun testRequestQaAsyncPreservesFollowUpConversationHistoryThroughRuntime() {
        val sourceFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowFollowUpHistoryService.java",
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:upload-file",
                        type = NodeType.METHOD,
                        title = "UploadService.submit",
                        signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        stateService.asyncRequests.markQaResult(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "历史问题",
                answer = "历史回答",
                promptPreview = "prompt",
                qaSession = QaConversationSession(
                    sessionId = "session-follow-up",
                    scopeKey = "method:upload-file",
                    messages = listOf(
                        QaConversationMessage(
                            messageId = "msg-user-1",
                            role = QaMessageRole.USER,
                            content = "历史问题",
                        ),
                        QaConversationMessage(
                            messageId = "msg-assistant-1",
                            role = QaMessageRole.ASSISTANT,
                            content = "历史回答",
                        ),
                    ),
                ),
            ),
        )
        var capturedMessages: List<QaConversationMessage> = emptyList()
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        capturedMessages = input.session?.messages.orEmpty()
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "follow-up runtime ok",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "继续解释这里为什么会 fallback",
            selectedNodeIds = listOf("method:upload-file"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("follow-up runtime ok", snapshot.qaResult?.answer)
        assertEquals(2, capturedMessages.size)
        assertEquals("历史问题", capturedMessages.firstOrNull()?.content)
        assertEquals("历史回答", capturedMessages.getOrNull(1)?.content)
    }

    fun testRequestQaAsyncContinuesWhenRuntimeCodeReadBudgetIsUnavailable() {
        val sourceFile = projectSourceFile(
            "src/main/java/com/example/ReviewWorkflowBudgetGuard.java",
            """
            class UploadService {
                String submit(String request) {
                    return request.trim();
                }
            }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit",
                        type = NodeType.METHOD,
                        title = "UploadService.submit",
                        signature = "com.example.UploadService.submit(java.lang.String):java.lang.String",
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        var executorInvoked = false
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.agent.model.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.agent.model.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorHook = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutMillisSupplier = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    defaultBudget = RunBudget(maxFilesRead = 0),
                    qaExecutor = { input, _, _ ->
                        executorInvoked = true
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "源码证据预算不足，按图继续问答。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync(
            question = "请解释这里的字符串处理逻辑",
            selectedNodeIds = listOf("method:submit"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertTrue(executorInvoked)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.qaRequestState.phase)
        assertEquals("源码证据预算不足，按图继续问答。", snapshot.qaResult?.answer)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("budget steps=") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("files=1/0") == true)
        assertFalse(snapshot.qaRequestState.detailMessage?.contains("tool=read_source_snippet") == true)
    }

    private fun projectSourceFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = Path.of(requireNotNull(project.basePath)).resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun sampleGraph(): GraphDocument {
        return GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )
    }

    private fun waitForSnapshot(
        stateService: GraphEditorStateService,
        predicate: (com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) -> Boolean,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = stateService.snapshot()
            if (predicate(snapshot)) {
                return snapshot
            }
            Thread.sleep(50)
        }
        fail("等待 runtime 问答状态收敛超时")
        throw IllegalStateException("unreachable")
    }
}
