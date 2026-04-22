package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.capability.QaCapability
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

class ReviewWorkflowAgentRuntimeTest : BasePlatformTestCase() {
    fun testRequestAuditAsyncRoutesThroughRuntimeAndKeepsAsyncLifecycleState() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                auditEvidenceCollector = AuditEvidenceCollector(maxSnippets = 0),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "runtime 已接管问答入口。",
                            promptPreview = "prompt",
                            warnings = emptyList(),
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync("请围绕当前链路进行问答")

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, snapshot.auditRequestState.phase)
        assertEquals("runtime 已接管问答入口。", snapshot.auditResult?.answer)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("runId=") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("capability=qa") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("step[0]") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("tool=get_draft_workbench") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("budget steps=") == true)
        assertEquals(2, snapshot.runtimeArtifactSummaries["qa"]?.size)
        assertEquals("图摘要", snapshot.runtimeArtifactSummaries["qa"]?.firstOrNull()?.title)
        assertEquals("问答结论", snapshot.runtimeArtifactSummaries["qa"]?.lastOrNull()?.title)
    }

    fun testRequestAuditAsyncReadsCodeEvidenceBeforeAuditExecutor() {
        val sourceFile = Files.createTempFile("review-workflow-qa", ".java")
        Files.writeString(
            sourceFile,
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
                        sourceTag = GraphSourceTag.FACT,
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
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "已读取${input.auditContext.sourceContext.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "请结合代码解释这里为什么会走 fallback",
            selectedNodeIds = listOf("method:upload-file"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已读取1段代码证据。", snapshot.auditResult?.answer)
        assertTrue(snapshot.runtimeArtifactSummaries["qa"]?.isNotEmpty() == true)
    }

    fun testRequestAuditAsyncReadsAdjacentCallEvidenceForExplicitSelection() {
        val controllerFile = Files.createTempFile("review-workflow-download", ".java")
        Files.writeString(
            controllerFile,
            """
            class CommonController {
                String fileDownload(String fileName) {
                    return RuoYiConfig.getDownloadPath() + fileName;
                }
            }
            """.trimIndent(),
        )
        val configFile = Files.createTempFile("review-workflow-config", ".java")
        Files.writeString(
            configFile,
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
                        sourceTag = GraphSourceTag.FACT,
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
                        sourceTag = GraphSourceTag.FACT,
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
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        var capturedNodeIds: List<String> = emptyList()
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        capturedNodeIds = input.auditContext.sourceContext.map { it.nodeId }
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "已读取${capturedNodeIds.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "请继续取证：确认下载路径配置是如何解析的",
            selectedNodeIds = listOf("method:file-download"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已读取2段代码证据。", snapshot.auditResult?.answer)
        assertEquals(
            listOf("method:file-download", "method:get-download-path"),
            capturedNodeIds,
        )
    }

    fun testRequestAuditAsyncBuildsCandidateChangeFromRuntimeCodeEvidenceInMockMode() {
        val sourceFile = Files.createTempFile("review-workflow-qa-direct-source", ".java")
        Files.writeString(
            sourceFile,
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
                        sourceTag = GraphSourceTag.FACT,
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
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = {
                    LinkGraphSettingsState(
                        llmEnabled = true,
                        provider = LlmProviderType.MOCK.name,
                    )
                },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = {
                LinkGraphSettingsState(
                    llmEnabled = true,
                    provider = LlmProviderType.MOCK.name,
                )
            },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
        )

        workflow.requestAuditAsync(
            question = "请把这里的 if(delete) 改成 delete == true，并在删除前校验 filePath 是否存在。",
            selectedNodeIds = listOf("method:file-download"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(LlmResultSource.MOCK, snapshot.auditResult?.source)
        assertEquals(1, snapshot.auditResult?.candidateChanges?.size)
        assertTrue(snapshot.auditResult?.investigationThreads?.isEmpty() == true)
        assertEquals("method:file-download", snapshot.auditResult?.candidateChanges?.single()?.targetNodeIds?.single())
        assertEquals(
            sourceFile.toString(),
            snapshot.auditResult?.candidateChanges?.single()?.editScopes?.singleOrNull()?.filePath,
        )
        assertTrue(snapshot.auditResult?.answer?.contains("待确认变更") == true)
    }

    fun testRequestAuditAsyncPreservesFactBaselineAndEditableWorkingGraph() {
        val factMethod = GraphNode(
            id = "method:file-download",
            type = NodeType.METHOD,
            title = "CommonController.fileDownload",
            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
            sourceTag = GraphSourceTag.FACT,
        )
        val editableDecision = GraphNode(
            id = "scope:file-download-if",
            type = NodeType.FLOW_SCOPE,
            title = "if (delete)",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
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
                        sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        var capturedAuditContext: GraphAuditContext? = null
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        capturedAuditContext = input.auditContext
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "已捕获 QA 图上下文。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "请确认删除分支是否属于当前方法流程",
            selectedNodeIds = listOf(factMethod.id),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("已捕获 QA 图上下文。", snapshot.auditResult?.answer)
        assertEquals(setOf(factMethod.id), capturedAuditContext?.factGraph?.nodes?.map(GraphNode::id)?.toSet())
        assertEquals(
            setOf(factMethod.id, editableDecision.id),
            capturedAuditContext?.editableGraph?.nodes?.map(GraphNode::id)?.toSet(),
        )
        assertEquals(listOf(factMethod.id), capturedAuditContext?.selectedNodeIds)
    }

    fun testRequestAuditAsyncReadsWholeGraphCodeEvidenceWithoutExplicitSelection() {
        val uploadFile = Files.createTempFile("review-workflow-whole-graph-upload", ".java")
        Files.writeString(
            uploadFile,
            """
            class UploadService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val auditFile = Files.createTempFile("review-workflow-whole-graph-audit", ".java")
        Files.writeString(
            auditFile,
            """
            class AuditService {
                boolean shouldAudit(String request) {
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
                        sourceTag = GraphSourceTag.FACT,
                        metadata = mapOf(
                            "source.filePath" to uploadFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                    GraphNode(
                        id = "method:should-audit",
                        type = NodeType.METHOD,
                        title = "AuditService.shouldAudit",
                        signature = "com.example.AuditService.shouldAudit(java.lang.String):boolean",
                        sourceTag = GraphSourceTag.FACT,
                        metadata = mapOf(
                            "source.filePath" to auditFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "整图读取${input.auditContext.sourceContext.size}段代码证据。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "请围绕整图解释 fallback 和审核判断是怎么串起来的",
            selectedNodeIds = emptyList(),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertTrue(snapshot.auditResult?.answer?.contains("整图读取") == true)
        assertTrue(snapshot.auditResult?.answer?.contains("0段") == false)
        assertTrue(snapshot.runtimeArtifactSummaries["qa"]?.any { it.title == "代码证据" } == true)
    }

    fun testRequestAuditAsyncPreservesFollowUpConversationHistoryThroughRuntime() {
        val sourceFile = Files.createTempFile("review-workflow-follow-up-history", ".java")
        Files.writeString(
            sourceFile,
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
                        sourceTag = GraphSourceTag.FACT,
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
        stateService.markAuditResult(
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = "历史问题",
                answer = "历史回答",
                promptPreview = "prompt",
                auditSession = AuditConversationSession(
                    sessionId = "session-follow-up",
                    scopeKey = "method:upload-file",
                    messages = listOf(
                        AuditConversationMessage(
                            messageId = "msg-user-1",
                            role = AuditMessageRole.USER,
                            content = "历史问题",
                        ),
                        AuditConversationMessage(
                            messageId = "msg-assistant-1",
                            role = AuditMessageRole.ASSISTANT,
                            content = "历史回答",
                        ),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        var capturedMessages: List<AuditConversationMessage> = emptyList()
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        capturedMessages = input.session?.messages.orEmpty()
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "follow-up runtime ok",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "继续解释这里为什么会 fallback",
            selectedNodeIds = listOf("method:upload-file"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("follow-up runtime ok", snapshot.auditResult?.answer)
        assertEquals(2, capturedMessages.size)
        assertEquals("历史问题", capturedMessages.firstOrNull()?.content)
        assertEquals("历史回答", capturedMessages.getOrNull(1)?.content)
    }

    fun testRequestAuditAsyncStopsWhenRuntimeCodeReadExceedsBudget() {
        val sourceFile = Files.createTempFile("review-workflow-budget", ".java")
        Files.writeString(
            sourceFile,
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
                        sourceTag = GraphSourceTag.FACT,
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
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        var executorInvoked = false
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(ReviewWorkflowAgentRuntimeTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    defaultBudget = RunBudget(maxFilesRead = 0),
                    auditExecutor = { input, _, _ ->
                        executorInvoked = true
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "不应该执行到这里。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync(
            question = "请解释这里的字符串处理逻辑",
            selectedNodeIds = listOf("method:submit"),
        )

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.FAILED
        }

        assertFalse(executorInvoked)
        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.auditRequestState.phase)
        assertTrue(snapshot.auditRequestState.errorMessage?.contains("runtime 未返回结果") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("failureReason=MAX_FILES_READ_EXCEEDED") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("step[2]") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("tool=read_source_snippet") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("nodeId=method:submit") == true)
    }

    private fun sampleGraph(): GraphDocument {
        return GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
    }

    private fun waitForSnapshot(
        stateService: GraphEditorStateService,
        predicate: (GraphEditorStateService.Snapshot) -> Boolean,
    ): GraphEditorStateService.Snapshot {
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
