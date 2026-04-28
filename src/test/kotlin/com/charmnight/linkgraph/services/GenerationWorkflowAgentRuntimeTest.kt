package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.diff.merge.MergeRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GenerationWorkflowAgentRuntimeTest : BasePlatformTestCase() {
    fun testOpenCodeDraftNativeDiffUsesWritableMergeRequest() {
        val projectBasePath = project.basePath?.toString() ?: throw AssertionError("project base path unavailable")
        val targetPath = "build/tests/native-merge/CommonController.java"
        val targetFile = Paths.get(projectBasePath, targetPath)
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public void fileDownload(String fileName, Boolean delete) {
                        String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                        String filePath = "/tmp/" + realFileName;
                        FileUtils.writeBytes(filePath, response.getOutputStream());
                        if (delete) {
                            FileUtils.deleteFile(filePath);
                        }
                    }
                }
            """.trimIndent(),
        )

        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.asyncRequests.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "scope:file-download-if",
                    title = "rewrite delete guard",
                    targetPath = targetPath,
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-1",
                            filePath = targetPath,
                            scopeId = "scope-file-download-if",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                            payload = """{
                                if (Boolean.TRUE.equals(delete)) {
                                    FileUtils.deleteFile(filePath);
                                }
                            }""".trimIndent(),
                        ),
                    ),
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-file-download-if",
                            targetNodeId = "scope:file-download-if",
                            filePath = targetPath,
                            language = "JAVA",
                            symbolKind = "FLOW_SCOPE",
                            symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                            startLine = 7,
                            endLine = 9,
                            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                        ),
                    ),
                ),
            ),
            warnings = emptyList(),
            source = LlmResultSource.MOCK,
            promptPreview = null,
        )
        val mergeRequests = mutableListOf<MergeRequest>()
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            showCodeDraftMergeRequest = { _, request ->
                mergeRequests += request
            },
        )

        workflow.openCodeDraftNativeDiff("draft-1")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(1, mergeRequests.size)
        assertNotNull(mergeRequests.single())
    }

    fun testOpenCodeDraftNativeDiffRejectsContentOnlyExistingFileDraft() {
        val projectBasePath = project.basePath?.toString() ?: throw AssertionError("project base path unavailable")
        val targetPath = "build/tests/native-merge-content-only/CommonController.java"
        val targetFile = Paths.get(projectBasePath, targetPath)
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public void fileDownload(String fileName, Boolean delete) {
                        String realFileName = System.currentTimeMillis() + fileName;
                        String filePath = "/tmp/" + realFileName;
                        FileUtils.writeBytes(filePath, response.getOutputStream());
                        if (delete) {
                            FileUtils.deleteFile(filePath);
                        }
                    }
                }
            """.trimIndent(),
        )

        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.asyncRequests.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-content-only",
                    sourceNodeId = "method:file-download",
                    title = "unsafe snippet",
                    targetPath = targetPath,
                    content = """
                        if (Boolean.TRUE.equals(delete)) {
                            FileUtils.deleteFile(filePath);
                        }
                    """.trimIndent(),
                ),
            ),
            warnings = emptyList(),
            source = LlmResultSource.REMOTE,
            promptPreview = null,
        )
        val mergeRequests = mutableListOf<MergeRequest>()
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            showCodeDraftMergeRequest = { _, request ->
                mergeRequests += request
            },
        )

        workflow.openCodeDraftNativeDiff("draft-content-only")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val snapshot = stateService.snapshot()
        assertTrue(mergeRequests.isEmpty(), "content-only existing-file draft must not open a writable merge")
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
        assertTrue(snapshot.operationFeedback?.message?.contains("结构化 editOperations") == true)
    }

    fun testApplySingleCodeDraftWritesFileAndPublishesFeedback() {
        val projectBasePath = project.basePath?.toString() ?: throw AssertionError("project base path unavailable")
        val targetPath = "build/tests/apply-single-draft/RuntimeChain.java"
        val targetFile = Paths.get(projectBasePath, targetPath)
        Files.createDirectories(targetFile.parent)

        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.asyncRequests.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:file-download",
                    title = "RuntimeChain.java",
                    targetPath = targetPath,
                    content = "class RuntimeChain {}",
                ),
            ),
            warnings = emptyList(),
            source = LlmResultSource.MOCK,
            promptPreview = null,
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
        )

        workflow.applySingleCodeDraft("draft-1")

        val snapshot = stateService.snapshot()
        assertTrue(snapshot.generatedCodeDraftWriteReport?.writtenFiles?.contains(targetPath) == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
        assertEquals("代码草稿已写入当前文件。", snapshot.operationFeedback?.message)
        assertTrue(Files.exists(targetFile))
        assertEquals("class RuntimeChain {}", Files.readString(targetFile))
    }

    fun testRequestGenerationPlanAsyncRoutesThroughRuntime() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 3_000L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            planCapabilityFactory = {
                PlanCapability(
                    planExecutor = { _, _, _ ->
                        GenerationPlan(
                            source = GenerationPlanSource.MOCK,
                            summary = "runtime 计划",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "upload draft",
                                    targetPath = "src/main/java/com/example/UploadDraft.java",
                                    content = "class UploadDraft {}",
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestGenerationPlanAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.generationPlanRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals("runtime 计划", snapshot.generationPlan?.summary)
        assertTrue(snapshot.generationPlanRequestState.detailMessage?.contains("runId=") == true)
        assertEquals(3, snapshot.runtimeArtifactSummaries["plan"]?.size)
        assertEquals("修改上传逻辑", snapshot.runtimeArtifactSummaries["plan"]?.firstOrNull()?.title)
        assertEquals("图差异", snapshot.runtimeArtifactSummaries["plan"]?.getOrNull(1)?.title)
        assertEquals("实现计划", snapshot.runtimeArtifactSummaries["plan"]?.lastOrNull()?.title)
    }

    fun testRequestCodeDraftsAsyncDoesNotBackfillPlanOutsideRuntime() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        var capturedPlan: GenerationPlan? = GenerationPlan(
            source = GenerationPlanSource.MOCK,
            summary = "unexpected",
        )
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            planCapabilityFactory = {
                PlanCapability(
                    planExecutor = { _, _, _ ->
                        GenerationPlan(
                            source = GenerationPlanSource.MOCK,
                            summary = "runtime 计划",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { input, _, _ ->
                        capturedPlan = input.plan
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "upload draft",
                                    targetPath = "src/main/java/com/example/UploadDraft.java",
                                    content = "class UploadDraft {}",
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(1, snapshot.generatedCodeDrafts.size)
        assertNull(capturedPlan)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("runId=") == true)
    }

    fun testRequestCodeDraftsAsyncRejectsExistingFileEditWhenTargetCodeWasNotRead() {
        val missingFile = "src/main/java/com/example/MissingController.java"
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = missingFile,
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.MissingController.uploadFile(java.lang.String):void",
            startLine = 10,
            endLine = 20,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                        editScopes = listOf(scope),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "rewrite upload",
                                    targetPath = missingFile,
                                    editOperations = listOf(
                                        CodeEditOperation(
                                            operationId = "op-1",
                                            filePath = missingFile,
                                            scopeId = "scope-upload",
                                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                            payload = "return;",
                                        ),
                                    ),
                                    editScopes = listOf(scope),
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertTrue(
            snapshot.codeDraftRequestState.errorMessage?.contains("证据不足") == true ||
                snapshot.codeDraftRequestState.errorMessage?.contains("本地安全校验") == true,
        )
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("failureReason=EVIDENCE_INSUFFICIENT") == true)
    }

    fun testRequestCodeDraftsAsyncPreparesExistingFileDraftForFlowScopeUsingOwnerMethodSignature() {
        val targetPath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java"
        val targetFile = java.nio.file.Path.of(requireNotNull(project.basePath)).resolve(targetPath)
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
            package com.ruoyi.web.controller.common;

            import javax.servlet.http.HttpServletRequest;
            import javax.servlet.http.HttpServletResponse;

            public class CommonController {
                public void fileDownload(
                    String fileName,
                    Boolean delete,
                    HttpServletResponse response,
                    HttpServletRequest request
                ) {
                    if (delete) {
                        response.setContentType("application/octet-stream");
                    }
                }
            }
            """.trimIndent(),
        )
        val scope = EditScope(
            scopeId = "scope-file-download-if",
            targetNodeId = "scope:file-download-if",
            filePath = targetPath,
            language = "JAVA",
            symbolKind = "FLOW_SCOPE",
            symbolSignature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean,javax.servlet.http.HttpServletResponse,javax.servlet.http.HttpServletRequest):void",
            startLine = 11,
            endLine = 13,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:file-download",
                        type = NodeType.METHOD,
                        title = "CommonController.fileDownload",
                        signature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean,javax.servlet.http.HttpServletResponse,javax.servlet.http.HttpServletRequest):void",
                        sourceTag = GraphSourceTag.FACT,
                        metadata = mapOf(
                            "source.filePath" to targetPath,
                            "source.startLine" to "6",
                            "source.endLine" to "15",
                        ),
                    ),
                    GraphNode(
                        id = "scope:file-download-if",
                        type = NodeType.FLOW_SCOPE,
                        title = "if (delete)",
                        sourceTag = GraphSourceTag.FACT,
                        metadata = mapOf(
                            "flow.ownerMethod" to "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean,javax.servlet.http.HttpServletResponse,javax.servlet.http.HttpServletRequest):void",
                            "source.filePath" to targetPath,
                            "source.startLine" to "11",
                            "source.endLine" to "13",
                        ),
                    ),
                ),
            ),
            "currentMethod",
        )
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "收紧删除条件",
                        editScopes = listOf(scope),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "scope:file-download-if",
                                    title = "rewrite delete guard",
                                    targetPath = targetPath,
                                    editOperations = listOf(
                                        CodeEditOperation(
                                            operationId = "op-1",
                                            filePath = targetPath,
                                            scopeId = "scope-file-download-if",
                                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                            payload = """{
                                                if (Boolean.TRUE.equals(delete)) {
                                                    response.setContentType("application/octet-stream");
                                                }
                                            }""".trimIndent(),
                                        ),
                                    ),
                                    editScopes = listOf(scope),
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        val draft = snapshot.generatedCodeDrafts.single()
        assertEquals("scope:file-download-if", draft.sourceNodeId)
        assertTrue(draft.preparedEdits.isNotEmpty())
        assertTrue(draft.preparedEdits.single().afterText.contains("Boolean.TRUE.equals(delete)"))
        assertFalse(draft.warnings.any { warning -> warning.contains("越界符号改写") })
    }

    fun testRequestCodeDraftsAsyncRejectsExistingFileEditWhenEditScopeIsInvalid() {
        val sourceFile = Files.createTempFile("generation-workflow-scope", ".java")
        Files.writeString(
            sourceFile,
            """
            class UploadController {
                void uploadFile(String file) {}
            }
            """.trimIndent(),
        )
        val targetPath = sourceFile.toString()
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = targetPath,
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.UploadController.uploadFile(java.lang.String):void",
            startLine = 1,
            endLine = 3,
            allowedChangeKinds = listOf("INSERT_METHOD"),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                        editScopes = listOf(scope),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "rewrite upload",
                                    targetPath = targetPath,
                                    editOperations = listOf(
                                        CodeEditOperation(
                                            operationId = "op-1",
                                            filePath = targetPath,
                                            scopeId = "scope-upload",
                                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                            payload = "return;",
                                        ),
                                    ),
                                    editScopes = listOf(scope),
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("本地安全校验") == true)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("failureReason=EVIDENCE_INSUFFICIENT") == true)
    }

    fun testRequestCodeDraftsAsyncRejectsInvalidExistingFileScopeBeforeCodegenExecutorRuns() {
        val sourceFile = Files.createTempFile("generation-workflow-prevalidate", ".java")
        Files.writeString(sourceFile, "class UploadController { void uploadFile(String file) {} }")
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 1,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                        editScopes = listOf(scope),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        var executorInvoked = false
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        executorInvoked = true
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "rewrite upload",
                                    targetPath = sourceFile.toString(),
                                    editOperations = listOf(
                                        CodeEditOperation(
                                            operationId = "op-1",
                                            filePath = sourceFile.toString(),
                                            scopeId = "scope-upload",
                                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                            payload = "return;",
                                        ),
                                    ),
                                    editScopes = listOf(scope),
                                ),
                            ),
                        )
                    },
                    toolRegistry = com.charmnight.linkgraph.llm.tools.AgentToolRegistry(
                        listOf(
                            com.charmnight.linkgraph.llm.tools.GetConfirmedIntentTool(com.charmnight.linkgraph.llm.tools.DraftToolFacade()),
                            com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool(com.charmnight.linkgraph.llm.tools.CodeReadToolFacade()),
                            object : com.charmnight.linkgraph.llm.tools.AgentTool {
                                override val name: String = "validate_edit_scope"
                                override val description: String = "fake invalid scope validator"

                                override fun invoke(
                                    input: Map<String, Any?>,
                                    context: com.charmnight.linkgraph.llm.tools.ToolExecutionContext,
                                ): com.charmnight.linkgraph.llm.tools.ToolResult {
                                    return com.charmnight.linkgraph.llm.tools.ToolResult(toolName = name, payload = mapOf("valid" to false))
                                }
                            },
                            object : com.charmnight.linkgraph.llm.tools.AgentTool {
                                override val name: String = "check_writable_draft"
                                override val description: String = "fake writable checker"

                                override fun invoke(
                                    input: Map<String, Any?>,
                                    context: com.charmnight.linkgraph.llm.tools.ToolExecutionContext,
                                ): com.charmnight.linkgraph.llm.tools.ToolResult {
                                    return com.charmnight.linkgraph.llm.tools.ToolResult(toolName = name, payload = mapOf("writable" to true))
                                }
                            },
                        ),
                    ),
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertFalse(executorInvoked)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("本地安全校验") == true)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("failureReason=EVIDENCE_INSUFFICIENT") == true)
    }

    fun testRequestCodeDraftsAsyncReportsAuthoritativeValidationFailureInsteadOfRuntimeMissingResult() {
        val sourceFile = Files.createTempFile("generation-workflow-validation-message", ".java")
        Files.writeString(sourceFile, "class UploadController { void uploadFile(String file) {} }")
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "UploadController.uploadFile(java.lang.String):void",
            startLine = 1,
            endLine = 1,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                        editScopes = listOf(scope),
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "rewrite upload",
                                    targetPath = sourceFile.toString(),
                                    editOperations = listOf(
                                        CodeEditOperation(
                                            operationId = "op-1",
                                            filePath = sourceFile.toString(),
                                            scopeId = "scope-upload",
                                            kind = CodeEditOperationKind.ADD_FIELD,
                                            payload = "private String extra;",
                                        ),
                                    ),
                                    editScopes = listOf(scope),
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertFalse(snapshot.codeDraftRequestState.errorMessage?.contains("runtime 未返回结果") == true)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("本地安全校验") == true)
        assertTrue(snapshot.codeDraftRequestState.detailMessage?.contains("failureReason=EVIDENCE_INSUFFICIENT") == true)
    }

    fun testConfirmedIntentPlanCodeDraftAndWritebackFormSingleAcceptedChain() {
        val relativeTargetPath = "src/generated/RuntimeChain${System.nanoTime()}.java"
        val absoluteTargetPath = java.nio.file.Path.of(requireNotNull(project.basePath)).resolve(relativeTargetPath)
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "新增上传草稿文件",
                    ),
                ),
            ),
        )
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            planCapabilityFactory = {
                PlanCapability(
                    planExecutor = { _, _, _ ->
                        GenerationPlan(
                            source = GenerationPlanSource.MOCK,
                            summary = "runtime 计划",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { _, _, _ ->
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "runtime chain draft",
                                    targetPath = relativeTargetPath,
                                    content = "class RuntimeChain {}",
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestGenerationPlanAsync()
        val planSnapshot = waitForSnapshot(stateService) {
            it.generationPlanRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }
        assertEquals("runtime 计划", planSnapshot.generationPlan?.summary)

        workflow.requestCodeDraftsAsync()
        val codegenSnapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }
        assertEquals(1, codegenSnapshot.generatedCodeDrafts.size)

        workflow.applyCodeDrafts()

        val finalSnapshot = stateService.snapshot()
        assertTrue(finalSnapshot.generatedCodeDraftWriteReport?.writtenFiles?.contains(relativeTargetPath) == true)
        assertTrue(Files.exists(absoluteTargetPath))
        assertTrue(Files.readString(absoluteTargetPath).contains("class RuntimeChain {}"))
    }

    fun testRequestCodeDraftsAsyncRejectsSnapshotPlanWithoutPlanArtifactLineage() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                    ),
                ),
            ),
        )
        stateService.asyncRequests.markGenerationPlan(
            GenerationPlan(
                source = GenerationPlanSource.MOCK,
                summary = "orphan plan",
            ),
        )
        project.getService(AgentArtifactStoreService::class.java).artifactStore.remove("plan-current")
        val session = ProjectEditorSession(stateService) {}
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("PlanArtifact") == true)
    }

    fun testRequestCodeDraftsAsyncUsesExistingPlanArtifactLineage() {
        val plan = GenerationPlan(
            source = GenerationPlanSource.MOCK,
            summary = "runtime 计划",
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        stateService.workbench.markDraftWorkbenchState(
            DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-1",
                        kind = DraftEntryKind.CHANGE,
                        title = "修改上传逻辑",
                    ),
                ),
            ),
        )
        stateService.asyncRequests.markGenerationPlan(plan)
        val artifactStore = project.getService(AgentArtifactStoreService::class.java).artifactStore
        artifactStore.save(PlanArtifact("plan-current", plan))
        val session = ProjectEditorSession(stateService) {}
        var capturedPlan: GenerationPlan? = null
        val workflow = GenerationWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = SyncPreviewPlanner(),
                graphGenerationService = GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphGenerationService = GraphGenerationService(),
            codeGenerationService = CodeGenerationService(),
            codeDraftWriterService = CodeDraftWriterService(project),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = { LinkGraphSettingsState() },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(GenerationWorkflowAgentRuntimeTest::class.java),
            codegenCapabilityFactory = {
                CodegenCapability(
                    project = project,
                    codegenExecutor = { input, _, _ ->
                        capturedPlan = input.plan
                        CodeGenerationResult(
                            drafts = listOf(
                                GeneratedCodeDraft(
                                    id = "draft-1",
                                    sourceNodeId = "method:upload-file",
                                    title = "upload draft",
                                    targetPath = "src/main/java/com/example/UploadDraft.java",
                                    content = "class UploadDraft {}",
                                ),
                            ),
                        )
                    },
                )
            },
        )

        workflow.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot(stateService) {
            it.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(plan, capturedPlan)
        assertEquals(1, snapshot.generatedCodeDrafts.size)
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
        fail("等待 GenerationWorkflow runtime 状态收敛超时")
        throw IllegalStateException("unreachable")
    }
}
