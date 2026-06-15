package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.actions.OpenLinkGraphAction
import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent

class LinkGraphToolWindowIT : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        deleteGeneratedIntegrationFiles()
        project.registerServiceInstance(
            GraphEditorStateService::class.java,
            GraphEditorStateService(),
        )
        project.registerServiceInstance(
            LinkGraphProjectTestOverrides::class.java,
            LinkGraphProjectTestOverrides(),
        )
        project.registerServiceInstance(
            GraphEditorApplicationService::class.java,
            GraphEditorApplicationService(project),
        )
        project.registerServiceInstance(
            GraphEditorCommandRouter::class.java,
            GraphEditorCommandRouter(project),
        )

        val toolWindowManager = ToolWindowManager.getInstance(project)
        if (toolWindowManager.getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) == null) {
            val toolWindow = toolWindowManager.registerToolWindow(
                RegisterToolWindowTask.notClosable(
                    LinkGraphToolWindowFactory.TOOL_WINDOW_ID,
                    ToolWindowAnchor.RIGHT,
                ),
            )
            LinkGraphToolWindowFactory().createToolWindowContent(project, toolWindow)
        }
    }

    private fun deleteGeneratedIntegrationFiles() {
        val basePath = project.basePath ?: return
        listOf(
            "src/main/java/com/example/OrderDraftDto.java",
        ).forEach { relativePath ->
            Files.deleteIfExists(Path.of(basePath).resolve(relativePath))
        }
    }

    fun testOpenActionShowsToolWindowAndLoadsFrontendEntryPage() {
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID)
        assertNotNull(toolWindow)

        val content = toolWindow!!.contentManager.selectedContent
        assertNotNull(content)

        assertNotNull(content!!.component)

        val browserPanel = GraphBrowserPanel(project)
        assertTrue(browserPanel.currentEntryUrl().startsWith("https://"))
        assertTrue(browserPanel.currentEntryUrl().endsWith("/index.html"))
        assertTrue(!browserPanel.currentEntryUrl().contains("file:"))
        assertTrue(!browserPanel.currentEntryUrl().contains("jar:file:"))
        assertTrue(!browserPanel.currentEntryUrl().contains("editor-shell"))
    }

    fun testToolWindowContentUsesDisposableBrowserPanel() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID)
        assertNotNull(toolWindow)

        val content = toolWindow!!.contentManager.selectedContent
        assertNotNull(content)
        assertTrue(
            "Expected toolwindow browser panel to be disposable so JCEF resources can be released",
            content!!.component is Disposable,
        )
    }

    fun testBrowserBridgeScriptExposesTransportReadyAndAckHooks() {
        val browserPanel = GraphBrowserPanel(project)
        val method = GraphBrowserPanel::class.java.getDeclaredMethod("buildBridgeScript")
        method.isAccessible = true

        val script = method.invoke(browserPanel) as String

        assertTrue(script.contains("frontendReady"))
        assertTrue(script.contains("snapshotAck"))
        assertTrue(script.contains("requestAnalysisDisplayMode"))
    }

    fun testRuntimeProbeScriptBuffersTracePayloadsAndCollectsDecisionGeometry() {
        val script = GraphBrowserDebugProbe.buildRuntimeProbeScript("flowchart-debug")

        assertTrue(script.contains("__linkGraphTraceBuffer"))
        assertTrue(script.contains("dataset?.handleid"))
        assertTrue(script.contains(".react-flow__edge-path"))
        assertTrue(script.contains(".flowchart-react-node.kind-decision"))
        assertTrue(script.contains("__linkGraphInteractionProbe"))
        assertTrue(script.contains("pointerdown"))
        assertTrue(script.contains("jcef.interactionProbe.drag"))
    }

    fun testOpenActionLoadsCurrentMethodGraphFromEditorContext() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(currentVisibleGraph(snapshot))
        assertTrue(currentVisibleGraph(snapshot).nodes.isNotEmpty())
        assertNotNull(snapshot.selectedNodeId)
        val selectedNode = currentVisibleGraph(snapshot).nodes.firstOrNull { it.id == snapshot.selectedNodeId }
        assertNotNull(selectedNode)
        assertEquals("OrderService.place", selectedNode!!.title)
        assertTrue(selectedNode.location!!.contains("OrderService.java"))
    }

    fun testOpenActionLoadsCurrentMethodGraphFromKotlinEditorContext() {
        myFixture.configureByText(
            "OrderService.kt",
            """
                package com.example

                class OrderService {
                    fun place(value: String) {
                        println(<caret>value)
                    }
                }
            """.trimIndent(),
        )
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(currentVisibleGraph(snapshot))
        assertTrue(currentVisibleGraph(snapshot).nodes.isNotEmpty())
        assertNotNull(snapshot.selectedNodeId)
        val selectedNode = currentVisibleGraph(snapshot).nodes.firstOrNull { it.id == snapshot.selectedNodeId }
        assertNotNull(selectedNode)
        assertEquals("OrderService.place", selectedNode!!.title)
        assertTrue(selectedNode.location!!.contains("OrderService.kt"))
    }

    fun testOpenActionDoesNotAppendBoundaryForKotlinLeafMethod() {
        myFixture.configureByText(
            "LeafOrderService.kt",
            """
                package com.example

                class LeafOrderService {
                    fun place(value: String): String {
                        return <caret>value.trim()
                    }
                }
            """.trimIndent(),
        )
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val visibleGraph = currentVisibleGraph(snapshot)
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(visibleGraph)
        assertTrue(
            "expected current method anchor node to remain visible",
            visibleGraph!!.nodes.any { node -> node.title == "LeafOrderService.place" && node.type == NodeType.METHOD },
        )
        assertTrue(
            "Kotlin 叶子方法不应仅因语言类型被误标为静态提取边界",
            visibleGraph.nodes.none { node -> node.metadata["linkGraph.boundary.kind"] != null },
        )
    }

    fun testOpenActionLoadsCurrentMethodGraphFromKotlinAccessorContext() {
        myFixture.configureByText(
            "AccessorService.kt",
            """
                package com.example

                class Formatter {
                    fun normalize(value: String): String {
                        return value.trim()
                    }
                }

                class AccessorService(
                    private val formatter: Formatter = Formatter(),
                ) {
                    var raw: String = " seed "
                        get() = formatter.normalize(<caret>field)
                }
            """.trimIndent(),
        )
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val visibleGraph = currentVisibleGraph(snapshot)
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(visibleGraph)
        assertTrue(
            "expected Kotlin getter node to be selected from accessor context",
            visibleGraph!!.nodes.any { node -> node.title == "AccessorService.getRaw" && node.type == NodeType.METHOD },
        )
        assertTrue(
            "expected accessor current-method graph to include downstream normalize call",
            visibleGraph.nodes.any { node -> node.title.contains("Formatter.normalize") },
        )
        assertTrue(
            "custom Kotlin getter should not be shown as unsupported boundary",
            visibleGraph.nodes.none { node -> node.metadata["linkGraph.boundary.kind"] != null },
        )
    }

    fun testOpenActionLoadsCurrentMethodGraphFromKotlinConstructorContext() {
        myFixture.configureByText(
            "PrimaryCtorFlow.kt",
            """
                package com.example

                class Formatter {
                    fun normalize(value: String): String {
                        return value.trim()
                    }
                }

                class PrimaryCtorFlow(
                    value: String,
                    private val formatter: Formatter = Formatter(),
                ) {
                    private val normalized = formatter.normalize(<caret>value)
                }
            """.trimIndent(),
        )
        val action = OpenLinkGraphAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.actionPerformed(event)
        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val visibleGraph = currentVisibleGraph(snapshot)
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(visibleGraph)
        assertTrue(
            "expected constructor current-method graph to include constructor anchor",
            visibleGraph!!.nodes.any { node -> node.title == "PrimaryCtorFlow.PrimaryCtorFlow" && node.type == NodeType.METHOD },
        )
        assertTrue(
            "expected constructor current-method graph to include initializer downstream call",
            visibleGraph.nodes.any { node -> node.title.contains("Formatter.normalize") },
        )
        assertTrue(
            "supported Kotlin constructor should not be shown as unsupported boundary",
            visibleGraph.nodes.none { node -> node.metadata["linkGraph.boundary.kind"] != null },
        )
    }

    fun testBridgeThreadCanRequestCurrentEditorContextGraph() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        val bridge = GraphEditorBridge(project)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val task = executor.submit<Boolean> {
                bridge.dispatch(GraphEditorMessage.RequestCurrentEditorContextGraph)
                true
            }

            waitForTask(task)
            assertTrue(task.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        waitForGraphSource("currentMethod")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(currentVisibleGraph(snapshot))
        assertTrue(currentVisibleGraph(snapshot).nodes.isNotEmpty())
    }

    fun testBridgeDispatchCanOpenSettingsThroughProjectService() {
        var opened = false
        project.getService(LinkGraphProjectTestOverrides::class.java).openSettings = {
            opened = true
        }

        GraphEditorBridge(project).dispatch(GraphEditorMessage.OpenSettings)

        assertTrue(opened)
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("SUCCESS", snapshot.operationFeedback!!.level.name)
        assertEquals("已打开 IDE 设置 > Link Graph。", snapshot.operationFeedback!!.message)
    }

    fun testAsyncCurrentEditorContextGraphReportsWarningWhenCaretIsOutsideMethod() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    private final String value = <caret>"demo";

                    void place(String input) {
                        System.out.println(input);
                    }
                }
            """.trimIndent(),
        )

        project.getService(GraphEditorApplicationService::class.java).commandDispatcher.dispatch(ApplicationCommand.LoadCurrentEditorContextGraph(myFixture.editor))
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("WARNING", snapshot.operationFeedback!!.level.name)
        assertEquals(
            "当前光标不在可识别的方法或资源节点内，请把光标放到方法、Mapper SQL、配置项、Markdown 或 SQL 文件内容上。",
            snapshot.operationFeedback!!.message,
        )
    }

    fun testCurrentEditorContextGraphExtractionRunsOffEdt() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        var extractorRanOnDispatchThread: Boolean? = null
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.useResourceSubjectForAsyncIntegration()
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { handle, _ ->
                            extractorRanOnDispatchThread = ApplicationManager.getApplication().isDispatchThread
                            minimalSemanticResult(handle)
                        },
                    ),
                ),
            )

        ApplicationManager.getApplication().invokeAndWait {
            projectService.commandDispatcher.dispatch(ApplicationCommand.LoadCurrentEditorContextGraph(myFixture.editor))
        }
        waitForGraphSource("currentContext")

        assertNotNull(extractorRanOnDispatchThread)
        assertFalse(extractorRanOnDispatchThread!!)
    }

    fun testAsyncCurrentEditorContextGraphLoadingDoesNotBlockEdt() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.useResourceSubjectForAsyncIntegration()
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { handle, _ ->
                            started.countDown()
                            assertTrue("timed out waiting to release analyzer", release.await(5, TimeUnit.SECONDS))
                            minimalSemanticResult(handle)
                        },
                    ),
                ),
            )

        ApplicationManager.getApplication().invokeAndWait {
            projectService.commandDispatcher.dispatch(ApplicationCommand.LoadCurrentEditorContextGraph(myFixture.editor))
        }

        waitForLatch(started, "expected background extraction to start")
        val pendingSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("operationFeedback", pendingSnapshot.lastMessageType)
        assertEquals("正在分析当前节点关联图：OrderService.place", pendingSnapshot.operationFeedback?.message)

        release.countDown()
        waitForGraphSource("currentContext")
    }

    fun testAsyncCurrentEditorContextGraphCancellationDoesNotSurfaceAsError() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        val started = CountDownLatch(1)
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.useResourceSubjectForAsyncIntegration()
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { _, _ ->
                            started.countDown()
                            throw ProcessCanceledException()
                        },
                    ),
                ),
            )

        ApplicationManager.getApplication().invokeAndWait {
            projectService.commandDispatcher.dispatch(ApplicationCommand.LoadCurrentEditorContextGraph(myFixture.editor))
        }

        waitForLatch(started, "expected background extraction to start")
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("INFO", snapshot.operationFeedback!!.level.name)
        assertEquals("正在分析当前节点关联图：OrderService.place", snapshot.operationFeedback!!.message)
        assertTrue(currentVisibleGraph(snapshot).nodes.isEmpty())
    }

    fun testAsyncCurrentEditorContextGraphDisposedFailureDoesNotSurfaceAsError() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )
        val started = CountDownLatch(1)
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.useResourceSubjectForAsyncIntegration()
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { _, _ ->
                            started.countDown()
                            throw AlreadyDisposedException("stub index disposed")
                        },
                    ),
                ),
            )

        ApplicationManager.getApplication().invokeAndWait {
            projectService.commandDispatcher.dispatch(ApplicationCommand.LoadCurrentEditorContextGraph(myFixture.editor))
        }

        waitForLatch(started, "expected background extraction to start")
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("INFO", snapshot.operationFeedback!!.level.name)
        assertEquals("正在分析当前节点关联图：OrderService.place", snapshot.operationFeedback!!.message)
        assertTrue(currentVisibleGraph(snapshot).nodes.isEmpty())
    }

    fun testAddCurrentMethodNodeAppendsMethodNodeWithoutReplacingExistingGraph() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    /**
                     * 提交订单
                     */
                    String place(String value) {
                        return <caret>value;
                    }
                }
            """.trimIndent(),
        )
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "class:order-draft-dto",
                        type = NodeType.CLASS,
                        title = "OrderDraftDto",
                    ),
                ),
            ),
            "seed-graph",
        )

        val appended = project.getService(GraphEditorApplicationService::class.java).commandDispatcher.dispatch(ApplicationCommand.AddCurrentEditorContextNode)

        assertTrue(appended)
        val snapshot = stateService.snapshot()
        assertEquals("workspaceGraphChanged", snapshot.lastMessageType)
        assertNotNull(currentVisibleGraph(snapshot))
        assertEquals(2, currentVisibleGraph(snapshot).nodes.size)
        val methodNode = currentVisibleGraph(snapshot).nodes.firstOrNull { it.type == NodeType.METHOD }
        assertNotNull(methodNode)
        assertEquals("OrderService.place", methodNode!!.title)
        assertTrue(methodNode.location!!.contains("OrderService.java"))
        assertEquals(listOf("java.lang.String"), methodNode.inputs)
        assertEquals(listOf("java.lang.String"), methodNode.outputs)
        assertEquals("提交订单", methodNode.doc)
    }

    fun testApplyDraftPatchPreviewOnlyMutatesDraftLayer() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val stateService = project.getService(GraphEditorStateService::class.java)
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
        val patch = GraphPatch(
            summary = "apply qa note",
            operations = listOf(
                GraphPatchOperation(
                    id = "patch-add-note",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "doc:default-fallback-note",
                    node = GraphNode(
                        id = "doc:default-fallback-note",
                        type = NodeType.DOC_PAGE,
                        title = "默认兜底说明",
                        doc = "AI 建议补充默认兜底逻辑说明。",
                        sourceTag = GraphSourceTag.DRAFT_AI,
                    ),
                ),
            ),
            addedNodeIds = listOf("doc:default-fallback-note"),
        )

        stateService.loadGraph(factGraph, "code-graph")
        stateService.markDraftPatchPreviewForIntegration(patch)
        projectService.commandDispatcher.dispatch(ApplicationCommand.ApplyDraftPatchPreview())

        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.semanticFactGraph.nodes.size)
        assertEquals(2, snapshot.workspaceGraph.nodes.size)
        assertEquals(2, currentVisibleGraph(snapshot).nodes.size)
        assertTrue(snapshot.workspaceGraph.nodes.any { it.id == "doc:default-fallback-note" && it.sourceTag == GraphSourceTag.DRAFT_AI })
        assertEquals(null, snapshot.draftPatchPreview)
    }

    fun testRequestQaGeneratesAnswerAndInvestigationThreads() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        project.getService(GraphEditorStateService::class.java).loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:order-service-place",
                        type = NodeType.METHOD,
                        title = "OrderService.place",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                    GraphNode(
                        id = "uncertain:channel-router",
                        type = NodeType.UNCERTAIN_LINK,
                        title = "ChannelStrategyRouter.resolve",
                        sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                    ),
                ),
            ),
            "code-graph",
        )

        projectService.commandDispatcher.dispatch(
            requestQaCommand(
                prompt = "请围绕当前范围进行问答：这段链路是否遗漏了默认兜底逻辑？",
                selectedNodeIds = listOf("uncertain:channel-router"),
            ),
        )
        waitForQaResultAndFollowUps()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val result = snapshot.qaResult
        assertNotNull(result)
        assertTrue(result!!.answer.contains("默认兜底"))
        assertNotNull(snapshot.qaResult)
        assertEquals(null, snapshot.draftPatchPreview)
        assertTrue(snapshot.qaResult!!.investigationThreads.isNotEmpty())
        assertTrue(snapshot.qaResult!!.investigationThreads.any { it.targetNodeIds.contains("uncertain:channel-router") })
        assertEquals(null, snapshot.qaResult!!.patch)
    }

    fun testRequestDiffReviewGeneratesRevisionPatchPreview() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val codeGraph = GraphDocument(
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
        val mermaid = """
            graph TD
            %% LG_NODE ENTRY|nodeId=method:order-service-place|nodeType=METHOD|title=OrderService.place|signature=com.example.OrderService.place%28java.lang.String%29:void
            %% LG_NODE DTO|nodeId=class:defaultchannelfallback|nodeType=CLASS|title=DefaultChannelFallback
            ENTRY["OrderService.place"]
            DTO["DefaultChannelFallback"]
            %% LG_EDGE ENTRY|to=DTO|edgeType=CALL
            ENTRY -- 调用 --> DTO
        """.trimIndent()

        project.getService(GraphEditorStateService::class.java).loadGraph(codeGraph, "code-graph")
        projectService.commandDispatcher.dispatch(ApplicationCommand.ImportMermaid(mermaid))
        projectService.commandDispatcher.dispatch(ApplicationCommand.ShowDiffMode)
        projectService.commandDispatcher.dispatch(
            requestDiffReviewCommand("这些差异意味着什么？请给出修订草稿。"),
        )
        waitForDiffReviewResultAndDraftPreview()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val result = snapshot.diffReviewResult
        assertNotNull(result)
        assertTrue(result!!.answer.contains("仅设计图有"))
        assertNotNull(snapshot.diffReviewResult)
        assertNotNull(snapshot.draftPatchPreview)
        assertTrue(snapshot.draftPatchPreview!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
    }

    fun testRestoreDraftPatchPreviewFromQaResultIsUnavailable() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        project.getService(GraphEditorStateService::class.java).loadGraph(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "uncertain:channel-router",
                        type = NodeType.UNCERTAIN_LINK,
                        title = "ChannelStrategyRouter.resolve",
                        sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                    ),
                ),
            ),
            "code-graph",
        )

        projectService.commandDispatcher.dispatch(
            requestQaCommand(
                prompt = "请围绕当前范围进行问答：这段链路是否遗漏了默认兜底逻辑？",
                selectedNodeIds = listOf("uncertain:channel-router"),
            ),
        )
        waitForQaResultAndFollowUps()
        val restored = projectService.commandDispatcher.dispatch(ApplicationCommand.RestoreDraftPatchPreview(DraftPatchPreviewSource.QA))

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.qaResult)
        assertEquals(null, restored)
        assertEquals(null, snapshot.draftPatchPreview)
    }

    fun testUndoLastDraftPatchApplyRestoresDraftGraphAndPreview() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val stateService = project.getService(GraphEditorStateService::class.java)
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
        val patch = GraphPatch(
            summary = "apply qa note",
            operations = listOf(
                GraphPatchOperation(
                    id = "patch-add-note",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "doc:default-fallback-note",
                    node = GraphNode(
                        id = "doc:default-fallback-note",
                        type = NodeType.DOC_PAGE,
                        title = "默认兜底说明",
                        doc = "AI 建议补充默认兜底逻辑说明。",
                        sourceTag = GraphSourceTag.DRAFT_AI,
                    ),
                ),
            ),
            addedNodeIds = listOf("doc:default-fallback-note"),
        )

        stateService.loadGraph(factGraph, "code-graph")
        stateService.markDraftPatchPreviewForIntegration(patch)
        projectService.commandDispatcher.dispatch(ApplicationCommand.ApplyDraftPatchPreview())
        projectService.commandDispatcher.dispatch(ApplicationCommand.UndoLastDraftPatchApply)

        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.semanticFactGraph.nodes.size)
        assertEquals(1, snapshot.workspaceGraph.nodes.size)
        assertEquals(1, currentVisibleGraph(snapshot).nodes.size)
        assertNotNull(snapshot.draftPatchPreview)
        assertTrue(snapshot.draftPatchPreview!!.operations.any { it.id == "patch-add-note" })
    }

    fun testSelectedMethodContextCanBePushedIntoUiState() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                ),
            ),
        )

        stateService.loadGraph(document, source = "integration-test")
        stateService.pushSelectedMethod("com.example.OrderService.place(java.lang.String):void")

        val snapshot = stateService.snapshot()
        assertEquals("integration-test", snapshot.lastGraphSource)
        assertEquals(
            "com.example.OrderService.place(java.lang.String):void",
            snapshot.selectedMethodSignature,
        )
        assertEquals("method:order-service-place", snapshot.selectedNodeId)
        assertEquals(1, currentVisibleGraph(snapshot).nodes.size)
    }

    fun testImportExportAndDiffModeFlowUpdatesEditorState() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val stateService = project.getService(GraphEditorStateService::class.java)
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

        project.getService(GraphEditorStateService::class.java).loadGraph(codeGraph, "code-graph")
        projectService.commandDispatcher.dispatch(ApplicationCommand.ImportMermaid(mermaid))
        val exported = projectService.commandDispatcher.dispatch(ApplicationCommand.ExportMermaid)
        val diffResult = projectService.commandDispatcher.dispatch(ApplicationCommand.ShowDiffMode)

        val snapshot = stateService.snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertEquals(2, snapshot.designBaselineGraph?.nodes?.size)
        assertEquals(exported, snapshot.exportedMermaid)
        assertTrue(exported.contains("%% LG_NODE"))
        assertTrue(exported.contains("OrderService.place"))
        assertTrue(
            "unexpected Mermaid issues for valid design baseline: ${snapshot.mermaidIssues}",
            snapshot.mermaidIssues.isEmpty(),
        )
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertNotNull(diffResult)
        assertTrue(
            snapshot.diff!!.entries.any { it.status == DiffStatus.ONLY_IN_MERMAID && it.elementId.contains("class:orderdraftdto") },
        )
        assertTrue(
            currentVisibleGraph(snapshot).nodes.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true,
        )
    }

    fun testExportMermaidWritesUserFeedbackIntoEditorState() {
        val projectService = project.getService(GraphEditorApplicationService::class.java)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                ),
            ),
        )

        project.getService(GraphEditorStateService::class.java).loadGraph(graph, "code-graph")
        val exported = projectService.commandDispatcher.dispatch(ApplicationCommand.ExportMermaid)

        val snapshot = stateService.snapshot()
        assertEquals(exported, snapshot.exportedMermaid)
        assertNotNull(snapshot.operationFeedback)
        assertEquals("SUCCESS", snapshot.operationFeedback!!.level.name)
        assertTrue(snapshot.operationFeedback!!.message.contains("已导出 Mermaid"))
    }

    fun testSourceNavigationOpensResolvedLocation() {
        val projectFile = myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
                package com.example;

                class OrderController {
                    void submit() {}
                }
            """.trimIndent(),
        )
        val node = GraphNode(
            id = "method:order-controller-submit",
            type = NodeType.METHOD,
            title = "OrderController.submit",
            location = "src/main/java/com/example/OrderController.java:3:1",
            signature = "com.example.OrderController.submit():void",
        )

        val target = project.getService(SourceNavigationService::class.java).navigate(node)

        assertNotNull(target)
        assertEquals(projectFile.virtualFile.path, target!!.filePath)
        assertEquals(3, target.line)
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.contains(projectFile.virtualFile))
    }

    fun testSourceNavigationFallsBackToMethodSignatureWhenLocationMissing() {
        val projectFile = myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
                package com.example;

                class OrderController {
                    void submit() {}
                }
            """.trimIndent(),
        )
        val node = GraphNode(
            id = "method:order-controller-submit",
            type = NodeType.METHOD,
            title = "OrderController.submit",
            location = null,
            signature = "com.example.OrderController.submit():void",
        )

        val target = project.getService(SourceNavigationService::class.java).navigate(node)

        assertNotNull(target)
        assertEquals(projectFile.virtualFile.path, target!!.filePath)
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.contains(projectFile.virtualFile))
    }

    fun testBridgeDispatchExportsMermaidAndRequestsSourceNavigation() {
        val projectFile = myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
                package com.example;

                class OrderController {
                    void submit() {}
                }
            """.trimIndent(),
        )
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-controller-submit",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    location = "src/main/java/com/example/OrderController.java:3:1",
                    signature = "com.example.OrderController.submit():void",
                ),
            ),
        )
        val bridge = GraphEditorBridge(project)

        bridge.dispatch(GraphEditorMessage.LoadGraph(document, "bridge-test"))
        bridge.dispatch(GraphEditorMessage.NodeSelected("method:order-controller-submit"))
        bridge.dispatch(GraphEditorMessage.ExportMermaid)
        bridge.dispatch(GraphEditorMessage.RequestSourceNavigation("method:order-controller-submit"))
        waitForSourceNavigationFeedback(projectFile.virtualFile.path)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("method:order-controller-submit", snapshot.selectedNodeId)
        assertNotNull(snapshot.exportedMermaid)
        assertTrue(snapshot.exportedMermaid!!.contains("OrderController.submit"))
        assertEquals("method:order-controller-submit", snapshot.sourceNavigationState.nodeId)
        assertNotNull(snapshot.operationFeedback)
        assertEquals("SUCCESS", snapshot.operationFeedback!!.level.name)
        assertTrue(snapshot.operationFeedback!!.message.contains("已打开源码"))
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.contains(projectFile.virtualFile))
    }

    fun testBridgeDispatchRequestsSourceNavigationForSignatureOnlyNode() {
        val projectFile = myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
                package com.example;

                class OrderController {
                    void submit() {}
                }
            """.trimIndent(),
        )
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-controller-submit",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    location = null,
                    signature = "com.example.OrderController.submit():void",
                ),
            ),
        )
        val bridge = GraphEditorBridge(project)

        bridge.dispatch(GraphEditorMessage.LoadGraph(document, "bridge-signature-only"))
        bridge.dispatch(GraphEditorMessage.RequestSourceNavigation("method:order-controller-submit"))
        waitForSourceNavigationFeedback(projectFile.virtualFile.path)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("method:order-controller-submit", snapshot.sourceNavigationState.nodeId)
        assertNotNull(snapshot.operationFeedback)
        assertEquals("SUCCESS", snapshot.operationFeedback!!.level.name)
        assertTrue(snapshot.operationFeedback!!.message.contains("已打开源码"))
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.contains(projectFile.virtualFile))
    }

    fun testBridgeDispatchImportsMermaidAndShowsDiffMode() {
        val bridge = GraphEditorBridge(project)
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertTrue(currentVisibleGraph(snapshot).nodes.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true)
    }

    fun testBridgeDispatchBuildsSyncPreviewFromImportedMermaid() {
        val bridge = GraphEditorBridge(project)
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.RequestSyncPreview)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(snapshot.syncPreviewRequested)
        assertTrue(snapshot.syncPreviewItems.isNotEmpty())
        assertTrue(snapshot.syncPreviewItems.any { it.title.contains("OrderDraftDto") })
    }

    fun testBridgeDispatchRequestsReviewGraphFromDiff() {
        val sourcePath = "src/main/java/com/example/review/OrderService.java"
        myFixture.addFileToProject(
            sourcePath,
            """
                package com.example.review;

                class OrderService {
                    void place() {
                        new OrderRepository().save();
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/review/OrderRepository.java",
            """
                package com.example.review;

                class OrderRepository {
                    void save() {}
                }
            """.trimIndent(),
        )
        val bridge = GraphEditorBridge(project)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "class:/$sourcePath",
                    type = NodeType.CLASS,
                    title = "OrderService",
                    location = "$sourcePath:3:1",
                    signature = "com.example.review.OrderService",
                ),
            ),
        )
        val mermaid = """
            graph TD
            %% LG_NODE SERVICE|nodeId=class:/$sourcePath|nodeType=CLASS|title=OrderService|signature=com.example.review.OrderService|location=$sourcePath%3A4%3A1
            SERVICE["OrderService"]
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "review-graph-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)
        bridge.dispatch(GraphEditorMessage.RequestIndexedGraph(requestReviewGraphRequest()))
        waitForReviewGraph()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(GraphSceneId.WORKSPACE_REVIEW_GRAPH, snapshot.currentSceneId)
        assertReviewGraphViewDataContract(snapshot.reviewGraphView, "toolwindow.reviewGraph")
        assertNotNull(snapshot.reviewGraphView.visibleGraph.nodes.singleOrNull { it.signature == "com.example.review.OrderService" })
        assertTrue(
            "Review Graph should include indexed blast-radius relations from the shared ArchitectureGraphIndex",
            snapshot.reviewGraphView.visibleGraph.edges.any { edge ->
                edge.metadata["review.edgeRole"] in setOf("UPSTREAM", "DOWNSTREAM", "RELATION", "RELATED_TEST")
            },
        )
        assertTrue(snapshot.reviewGraphView.summary.changedSymbolCount > 0)
        assertEquals(1, snapshot.reviewGraphView.summary.affectedPackageCount)
        assertEquals("SUCCESS", snapshot.operationFeedback?.level?.name)
        assertTrue(snapshot.operationFeedback?.message?.contains("已加载 Review Graph") == true)
        assertNotNull(currentVisibleGraph(snapshot))
        assertTrue(currentVisibleGraph(snapshot).nodes.isNotEmpty())
    }

    fun testBridgeDispatchImportsFlowchartMermaidAndShowsDiffMode() {
        val bridge = GraphEditorBridge(project)
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
            flowchart TD
            %% LG_NODE ENTRY|nodeId=method:order-service-place|nodeType=METHOD|title=OrderService.place|signature=com.example.OrderService.place%28java.lang.String%29:void
            %% LG_NODE DTO|nodeId=class:orderdraftdto|nodeType=CLASS|title=OrderDraftDto
            ENTRY["OrderService.place"]
            DTO["OrderDraftDto"]
            %% LG_EDGE ENTRY|to=DTO|edgeType=CALL
            ENTRY -- 调用 --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertTrue(currentVisibleGraph(snapshot).nodes.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true)
        assertTrue("Unexpected issues: ${snapshot.mermaidIssues}", snapshot.mermaidIssues.isEmpty())
    }

    fun testBridgeDispatchRequestsQaAndConfirmsCandidateChangeIntoDraftWorkbench() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-scope:order-service-place-guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (a > 10)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        stateService.markQaResultForIntegration(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "请确认这条已证实的业务变更",
                answer = "当前源码里直接能看到这条变更需要确认。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-order-service-place",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "修正下单主流程条件",
                        targetNodeIds = listOf("flow-scope:order-service-place-guard"),
                        beforeState = "if (a > 10)",
                        afterState = "if (a < 100)",
                        reason = "当前源码里直接能看到条件判断写反。",
                        impactSummary = "会影响下单主流程分支。",
                        claimType = "CODE_FACT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-order-service-place",
                                claim = "源码里直接能看到条件判断写反。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-scope:order-service-place-guard")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val confirmedChangeId = "change-order-service-place"
        bridge.dispatch(GraphEditorMessage.ConfirmQaCandidateChange(confirmedChangeId))
        waitForConfirmedQaCandidateDraftEntry(confirmedChangeId)

        val snapshot = stateService.snapshot()
        assertEquals(confirmedChangeId, snapshot.qaResult?.candidateChanges?.firstOrNull()?.changeId)
        assertEquals(
            com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus.CONFIRMED,
            snapshot.qaResult?.candidateChanges?.firstOrNull()?.status,
        )
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals(confirmedChangeId, snapshot.draftWorkbenchState.draftChanges.first().sourceChangeId)
        assertEquals(
            listOf("flow-scope:order-service-place-guard"),
            snapshot.workspaceGraph.nodes.map { it.id },
        )
        assertEquals(
            "if (a < 100)",
            snapshot.workspaceGraph.nodes.singleOrNull()?.title,
        )
        assertTrue(snapshot.workspaceGraph.edges.isEmpty() == true)
        assertNotNull(snapshot.draftWorkbenchState.draftChanges.first().graphPatch)
        assertEquals(true, snapshot.workingGraphDirty)
    }

    fun testBridgeDispatchNormalizesTryScopedStructuralSuggestionToDecisionNode() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphNode(
                    id = "scope:file-download-try",
                    type = NodeType.FLOW_SCOPE,
                    title = "try",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "SCOPE",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf(
                        "flowchart.kind" to "DECISION",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
            ),
        )

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        stateService.markQaResultForIntegration(
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "请确认这条已证实的业务变更",
                answer = "建议收紧删除条件。",
                promptPreview = "prompt",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-delete-guard",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "收紧删除条件并在删除前校验文件存在",
                        targetNodeIds = listOf("scope:file-download-try"),
                        beforeState = "if (delete)",
                        afterState = "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                        reason = "delete 为包装类型，删除前缺少文件存在校验。",
                        impactSummary = "删除分支需要更严格的进入条件。",
                        claimType = "STRUCTURAL_SUGGESTION",
                        graphPatch = GraphPatch(
                            summary = "更新当前 try 作用域节点中的删除分支逻辑",
                            operations = listOf(
                                GraphPatchOperation(
                                    id = "patch-op-update-try",
                                    action = GraphPatchAction.UPDATE_NODE,
                                    elementKind = GraphDiffElementKind.NODE,
                                    elementId = "scope:file-download-try",
                                    title = "更新 try 作用域节点",
                                    summary = "收紧删除条件",
                                    node = GraphNode(
                                        id = "scope:file-download-try",
                                        type = NodeType.FLOW_SCOPE,
                                        title = "try",
                                        sourceTag = GraphSourceTag.DRAFT_AI,
                                        metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                    ),
                                    metadata = mapOf("draft.claimType" to "STRUCTURAL_SUGGESTION"),
                                ),
                            ),
                        ),
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-delete-guard",
                                claim = "源码里直接能看到删除判断条件。",
                                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                                references = listOf(ResultEvidenceReference(nodeId = "scope:file-download-if")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        bridge.dispatch(GraphEditorMessage.ConfirmQaCandidateChange("change-delete-guard"))
        waitForConfirmedQaCandidateDraftEntry("change-delete-guard")

        val snapshot = stateService.snapshot()
        val confirmedCandidate = snapshot.qaResult?.candidateChanges?.singleOrNull()
        assertEquals(listOf("scope:file-download-if"), confirmedCandidate?.targetNodeIds)
        assertEquals("scope:file-download-if", confirmedCandidate?.graphPatch?.operations?.singleOrNull()?.elementId)
        val nodesById = snapshot.workspaceGraph.nodes.associateBy { it.id }.orEmpty()
        assertEquals("try", nodesById["scope:file-download-try"]?.title)
        assertEquals(
            "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            nodesById["scope:file-download-if"]?.title,
        )
    }

    fun testBridgeDispatchClearsAndUndoesDraftPatchPreview() {
        val bridge = GraphEditorBridge(project)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "uncertain:channel-router",
                    type = NodeType.UNCERTAIN_LINK,
                    title = "ChannelStrategyRouter.resolve",
                    sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                ),
            ),
        )
        val mermaid = """
            graph TD
            %% LG_NODE ENTRY|nodeId=method:channel-strategy-router-resolve|nodeType=METHOD|title=ChannelStrategyRouter.resolve
            %% LG_NODE FALLBACK|nodeId=doc:defaultchannelfallback|nodeType=DOC_PAGE|title=DefaultChannelFallback
            ENTRY["ChannelStrategyRouter.resolve"]
            FALLBACK["DefaultChannelFallback"]
            %% LG_EDGE ENTRY|to=FALLBACK|edgeType=LINKS_DOC
            ENTRY -- 文档 --> FALLBACK
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)
        bridge.dispatch(
            requestDiffReviewMessage(
                prompt = "这些差异意味着什么？请给出修订草稿。",
                selectedDiffItemIds = listOf("uncertain:channel-router"),
            ),
        )
        waitForDiffReviewResultAndDraftPreview()
        bridge.dispatch(GraphEditorMessage.ClearDraftPatchPreview)
        bridge.dispatch(GraphEditorMessage.RestoreDraftPatchPreview(GraphEditorMessage.DraftPatchPreviewSource.DIFF_REVIEW))
        bridge.dispatch(GraphEditorMessage.ApplyDraftPatchPreview())
        waitForDraftGraphNodeCount(expectedNodeCount = 2)
        bridge.dispatch(GraphEditorMessage.UndoLastDraftPatchApply)
        waitForDraftUndoState()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(null, snapshot.diffReviewResult)
        assertNotNull(snapshot.draftPatchPreview)
        assertEquals(1, snapshot.workspaceGraph.nodes.size)
        assertEquals("undoDraftPatchApply", snapshot.lastMessageType)
    }

    fun testBridgeDispatchBuildsGenerationPlan() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        project.getService(LinkGraphProjectTestOverrides::class.java).effectiveGenerationSettings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = "MOCK",
            timeoutSeconds = 45,
        )
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                    inputs = listOf("java.lang.String"),
                    outputs = listOf("void"),
                ),
            ),
        )
        val mermaid = """
            graph TD
            %% LG_NODE ENTRY|nodeId=method:order-service-place|nodeType=METHOD|title=OrderService.place
            %% LG_NODE DTO|nodeId=class:orderdraftdto|nodeType=CLASS|title=OrderDraftDto
            ENTRY["OrderService.place"]
            DTO["OrderDraftDto"]
            %% LG_EDGE ENTRY|to=DTO|edgeType=CALL
            ENTRY -- 调用 --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        stateService.markQaResultForIntegration(buildConfirmedPlanCandidateResult(changeId = "change-order-service-place"))
        bridge.dispatch(GraphEditorMessage.ConfirmQaCandidateChange("change-order-service-place"))
        bridge.dispatch(requestGenerationPlanMessage())
        waitForGenerationPlan()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.generationPlan)
        assertTrue(snapshot.generationPlan!!.items.isNotEmpty())
        assertTrue(snapshot.generationPlan!!.summary.isNotBlank())
    }

    fun testBridgeDispatchGeneratesAndWritesCodeDrafts() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                    inputs = listOf("java.lang.String"),
                    outputs = listOf("void"),
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        stateService.markQaResultForIntegration(buildConfirmedPlanCandidateResult(changeId = "change-order-service-place"))
        bridge.dispatch(GraphEditorMessage.ConfirmQaCandidateChange("change-order-service-place"))
        bridge.dispatch(requestGenerationPlanMessage())
        waitForGenerationPlan()
        bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
        waitForCodeDrafts()
        bridge.dispatch(GraphEditorMessage.ApplyCodeDrafts)
        waitForCodeDraftWriteReport()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(snapshot.generatedCodeDrafts.isNotEmpty())
        val draft = snapshot.generatedCodeDrafts.single()
        assertEquals("src/main/java/com/example/OrderDraftDto.java", draft.targetPath)
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(
            snapshot.generatedCodeDraftWriteReport.toString(),
            snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(draft.targetPath),
        )

        val writtenPath = Path.of(project.basePath!!).resolve(draft.targetPath)
        assertTrue(Files.exists(writtenPath))
        assertTrue(Files.readString(writtenPath).contains("class OrderDraftDto"))
        assertTrue(
            FileEditorManager.getInstance(project).selectedFiles.any { selected ->
                selected.path == writtenPath.toString()
            },
        )
    }

    fun testBridgeDispatchWritesSingleDraftAndNavigatesByTargetPath() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                    inputs = listOf("java.lang.String"),
                    outputs = listOf("void"),
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        stateService.markQaResultForIntegration(buildConfirmedPlanCandidateResult(changeId = "change-order-service-place"))
        bridge.dispatch(GraphEditorMessage.ConfirmQaCandidateChange("change-order-service-place"))
        bridge.dispatch(requestGenerationPlanMessage())
        waitForGenerationPlan()
        bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
        waitForCodeDrafts()

        val generatedDraft = project.getService(GraphEditorStateService::class.java).snapshot().generatedCodeDrafts.single()
        bridge.dispatch(GraphEditorMessage.ApplySingleCodeDraft(generatedDraft.id))
        bridge.dispatch(GraphEditorMessage.RequestDraftNavigation(generatedDraft.targetPath))
        waitForCodeDraftWriteReport()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(
            snapshot.generatedCodeDraftWriteReport.toString(),
            snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(generatedDraft.targetPath),
        )

        val writtenPath = Path.of(project.basePath!!).resolve(generatedDraft.targetPath)
        assertTrue(Files.exists(writtenPath))
        assertTrue(
            FileEditorManager.getInstance(project).selectedFiles.any { selected ->
                selected.path == writtenPath.toString()
            },
        )
    }

    fun testBridgeDispatchAppliesStructuredJavaDraftToExistingFile() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val targetPath = "src/main/java/com/example/CommonController.java"
        val writtenPath = Path.of(project.basePath!!).resolve(targetPath)
        Files.createDirectories(writtenPath.parent)
        Files.writeString(
            writtenPath,
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )
        val draft = GeneratedCodeDraft(
            id = "draft-java-upload-file",
            sourceNodeId = "method:upload-file",
            title = "CommonController.java",
            targetPath = targetPath,
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-java-upload-file",
                    filePath = targetPath,
                    scopeId = "scope-java-upload-file",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        public String uploadFile(String fileName) {
                            if (fileName == null || fileName.isBlank()) {
                                throw new IllegalArgumentException("fileName");
                            }
                            return fileName.trim();
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-java-upload-file",
                    targetNodeId = "method:upload-file",
                    filePath = targetPath,
                    language = "JAVA",
                    symbolKind = "METHOD",
                    symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-java-upload-file"),
                ),
            ),
        )

        stateService.markGeneratedCodeDraftsForIntegration(listOf(draft), emptyList(), LlmResultSource.LOCAL_RULE, promptPreview = null)
        bridge.dispatch(GraphEditorMessage.ApplySingleCodeDraft(draft.id))
        waitForCodeDraftWriteReport()

        val snapshot = stateService.snapshot()
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(targetPath))
        val written = Files.readString(writtenPath)
        assertTrue(written.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(written.contains("return fileName.trim();"))
        assertTrue(written.contains("public String download(String resource) {\n        return resource;\n    }"))
    }

    fun testBridgeDispatchAppliesStructuredKotlinDraftToExistingFile() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val targetPath = "src/main/kotlin/com/example/CommonController.kt"
        val writtenPath = Path.of(project.basePath!!).resolve(targetPath)
        Files.createDirectories(writtenPath.parent)
        Files.writeString(
            writtenPath,
            """
                package com.example

                class CommonController {
                    fun download(resource: String): String {
                        return resource
                    }

                    fun uploadFile(fileName: String): String {
                        return fileName
                    }
                }
            """.trimIndent(),
        )
        val draft = GeneratedCodeDraft(
            id = "draft-kotlin-upload-file",
            sourceNodeId = "method:upload-file-kt",
            title = "CommonController.kt",
            targetPath = targetPath,
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-kotlin-upload-file",
                    filePath = targetPath,
                    scopeId = "scope-kotlin-upload-file",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        fun uploadFile(fileName: String): String {
                            require(fileName.isNotBlank()) { "fileName" }
                            return fileName.trim()
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-kotlin-upload-file",
                    targetNodeId = "method:upload-file-kt",
                    filePath = targetPath,
                    language = "KOTLIN",
                    symbolKind = "FUNCTION",
                    symbolSignature = "com.example.CommonController.uploadFile(kotlin.String):kotlin.String",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-kotlin-upload-file"),
                ),
            ),
        )

        stateService.markGeneratedCodeDraftsForIntegration(listOf(draft), emptyList(), LlmResultSource.LOCAL_RULE, promptPreview = null)
        bridge.dispatch(GraphEditorMessage.ApplySingleCodeDraft(draft.id))
        waitForCodeDraftWriteReport()

        val snapshot = stateService.snapshot()
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(targetPath))
        val written = Files.readString(writtenPath)
        assertTrue(written.contains("""require(fileName.isNotBlank()) { "fileName" }"""))
        assertTrue(written.contains("return fileName.trim()"))
        assertTrue(written.contains("fun download(resource: String): String {\n        return resource\n    }"))
    }

    fun testBridgeDispatchRejectsStructuredExistingFileOverreachAndKeepsFileUntouched() {
        val bridge = GraphEditorBridge(project)
        val stateService = project.getService(GraphEditorStateService::class.java)
        val targetPath = "src/main/java/com/example/CommonController.java"
        val writtenPath = Path.of(project.basePath!!).resolve(targetPath)
        Files.createDirectories(writtenPath.parent)
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()
        Files.writeString(writtenPath, before)
        val draft = GeneratedCodeDraft(
            id = "draft-java-overreach",
            sourceNodeId = "method:upload-file",
            title = "CommonController.java",
            targetPath = targetPath,
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-java-overreach",
                    filePath = targetPath,
                    scopeId = "scope-java-upload-file",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        public String download(String resource) {
                            return resource + "-changed";
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-java-upload-file",
                    targetNodeId = "method:upload-file",
                    filePath = targetPath,
                    language = "JAVA",
                    symbolKind = "METHOD",
                    symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-java-upload-file"),
                ),
            ),
        )

        stateService.markGeneratedCodeDraftsForIntegration(listOf(draft), emptyList(), LlmResultSource.LOCAL_RULE, promptPreview = null)
        bridge.dispatch(GraphEditorMessage.ApplySingleCodeDraft(draft.id))
        waitForCodeDraftWriteReport()

        val snapshot = stateService.snapshot()
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(snapshot.generatedCodeDraftWriteReport!!.skippedFiles.contains(targetPath))
        assertTrue(
            snapshot.generatedCodeDraftWriteReport!!.warnings.any { warning ->
                warning.contains("越界", ignoreCase = false) ||
                    warning.contains("未授权") ||
                    warning.contains("检测到")
            },
        )
        assertEquals(before, Files.readString(writtenPath).trim())
    }

    private fun waitForGraphSource(expectedSource: String) {
        repeat(20) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (project.getService(GraphEditorStateService::class.java).snapshot().lastGraphSource == expectedSource) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected graph source '$expectedSource' to be available")
    }

    private fun <T> waitForTask(task: java.util.concurrent.Future<T>) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (task.isDone) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected background task to complete")
    }

    private fun waitForLatch(
        latch: CountDownLatch,
        failureMessage: String,
    ) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (latch.await(100, TimeUnit.MILLISECONDS)) {
                return
            }
        }
        fail(failureMessage)
    }

    private fun waitForSourceNavigationFeedback(expectedFilePath: String) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            val feedback = snapshot.operationFeedback
            if (
                feedback?.level?.name == "SUCCESS" &&
                feedback.message.contains("已打开源码") &&
                FileEditorManager.getInstance(project).selectedFiles.any { selected ->
                    selected.path == expectedFilePath
                }
            ) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected source navigation to complete successfully")
    }

    private fun waitForGenerationPlan() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.generationPlan != null) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected generation plan to be available")
    }

    private fun requestQaCommand(
        prompt: String,
        selectedNodeIds: List<String> = emptyList(),
    ): ApplicationCommand.RequestAssistantTask =
        ApplicationCommand.RequestAssistantTask(
            actionId = AssistantActionId.ASK_CONTEXT,
            intent = AssistantIntent.ASK_CODE,
            prompt = prompt,
            selectedNodeIds = selectedNodeIds,
        )

    private fun requestDiffReviewCommand(
        prompt: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): ApplicationCommand.RequestAssistantTask =
        ApplicationCommand.RequestAssistantTask(
            actionId = AssistantActionId.CHECK_CHANGE,
            intent = AssistantIntent.CHECK_CHANGE,
            prompt = prompt,
            selectedDiffItemIds = selectedDiffItemIds,
        )

    private fun requestDiffReviewMessage(
        prompt: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): GraphEditorMessage.RequestAssistantTask =
        GraphEditorMessage.RequestAssistantTask(
            actionId = AssistantActionId.CHECK_CHANGE,
            intent = AssistantIntent.CHECK_CHANGE,
            prompt = prompt,
            selectedDiffItemIds = selectedDiffItemIds,
        )

    private fun requestGenerationPlanMessage(): GraphEditorMessage.RequestAssistantTask =
        GraphEditorMessage.RequestAssistantTask(
            actionId = AssistantActionId.GENERATE_IMPLEMENTATION,
            intent = AssistantIntent.GENERATE_CODE,
            prompt = "",
        )

    private fun waitForQaResultAndFollowUps() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.qaResult != null && (
                    snapshot.qaResult!!.candidateChanges.isNotEmpty() ||
                        snapshot.qaResult!!.investigationThreads.isNotEmpty()
                )
            ) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected QA result with candidate changes or investigation threads to be available")
    }

    private fun waitForDiffReviewResultAndDraftPreview() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.diffReviewResult != null && snapshot.draftPatchPreview != null) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected diff review result and draft patch preview to be available")
    }

    private fun waitForReviewGraph() {
        repeat(80) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.currentSceneId == GraphSceneId.WORKSPACE_REVIEW_GRAPH &&
                snapshot.reviewGraphView.visibleGraph.nodes.isNotEmpty()
            ) {
                return
            }
            Thread.sleep(100)
        }
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        fail("Expected Review Graph to be loaded, last feedback=${snapshot.operationFeedback}")
    }

    private fun waitForDraftGraphNodeCount(expectedNodeCount: Int) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.workspaceGraph.nodes.size == expectedNodeCount) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected draft graph node count to reach $expectedNodeCount")
    }

    private fun waitForConfirmedQaCandidateDraftEntry(changeId: String) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            val candidate = snapshot.qaResult?.candidateChanges?.firstOrNull { it.changeId == changeId }
            val draftEntry = snapshot.draftWorkbenchState.draftChanges.firstOrNull { it.sourceChangeId == changeId }
            if (candidate?.status == CandidateDraftChangeStatus.CONFIRMED && draftEntry != null) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected confirmed QA candidate '$changeId' to be written into draft workbench state")
    }

    private fun waitForDraftUndoState() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.lastMessageType == "undoDraftPatchApply" &&
                snapshot.workspaceGraph.nodes.size == 1 &&
                snapshot.draftPatchPreview != null
            ) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected draft patch undo state to be restored")
    }

    private fun waitForCodeDrafts() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.generatedCodeDrafts.isNotEmpty()) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected code drafts to be available")
    }

    private fun waitForCodeDraftWriteReport() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.generatedCodeDraftWriteReport != null) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected code draft write report to be available")
    }

    private fun buildConfirmedPlanCandidateResult(changeId: String): GraphPatchResult {
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = "请确认这条已证实的业务变更",
            answer = "当前源码里直接能看到这条变更需要确认。",
            promptPreview = "prompt",
            candidateChanges = listOf(
                CandidateDraftChange(
                    changeId = changeId,
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                    title = "修正下单主流程条件",
                    targetNodeIds = listOf("method:order-service-place"),
                    beforeState = "if (a > 10)",
                    afterState = "if (a < 100)",
                    reason = "当前源码里直接能看到条件判断写反。",
                    impactSummary = "会影响下单主流程分支。",
                    claimType = "CODE_FACT",
                    evidence = listOf(
                        ResultEvidenceFinding(
                            id = "finding-order-service-place",
                            claim = "源码里直接能看到条件判断写反。",
                            evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                            references = listOf(ResultEvidenceReference(nodeId = "method:order-service-place")),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun semanticProvider(
        analyze: (SubjectHandle, TraversalBudgetPolicy) -> SemanticAnalysisResult,
    ): SemanticProvider {
        return object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = true

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                return analyze(handle, budgetPolicy)
            }
        }
    }

    private fun minimalSemanticResult(handle: SubjectHandle): SemanticAnalysisResult {
        val methodSignature = (handle as? CodeSubjectHandle)?.methodSignature ?: "com.example.OrderService.place(java.lang.String):void"
        val methodUnitId = "method:$methodSignature"
        return SemanticAnalysisResult(
            subject = handle,
            anchors = listOf(
                SemanticAnchor(
                    id = "anchor-entry",
                    targetUnitId = methodUnitId,
                ),
            ),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = methodUnitId,
                    title = handle.displayName,
                    signature = methodSignature,
                ),
            ),
            relations = emptyList(),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = listOf(
                SourceMapping(
                    sourcePath = handle.sourcePath,
                    sourceRange = handle.sourceRange,
                    targetUnitId = methodUnitId,
                ),
            ),
        )
    }

    private fun LinkGraphProjectTestOverrides.useResourceSubjectForAsyncIntegration() {
        val handle = ResourceSubjectHandle(
            subjectId = "resource:order-service-place",
            sourcePath = "OrderService.java",
            sourceRange = SourceRange(startOffset = 0, endOffset = myFixture.editor.document.textLength),
            displayName = "OrderService.place",
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
        )
        subjectLocator = object : SubjectLocator {
            override fun locate(
                project: com.intellij.openapi.project.Project,
                editor: com.intellij.openapi.editor.Editor?,
                commitDocument: Boolean,
            ): SubjectHandle = handle

            override fun previewKind(
                project: com.intellij.openapi.project.Project,
                editor: com.intellij.openapi.editor.Editor?,
                commitDocument: Boolean,
            ): SubjectPreviewKind = SubjectPreviewKind.RESOURCE_SUBJECT
        }
    }

    private fun drainIdeQueue(cycles: Int = 10) {
        repeat(cycles) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }
}

private fun GraphDocument.nonEmptyOrNull(): GraphDocument? {
    return takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() || graph.patch != null }
}

private val GraphEditorStateSnapshot.selectedNodeId: String?
    get() = currentSceneState().selectedNodeId

private val GraphEditorStateSnapshot.diffMode: Boolean
    get() = currentSceneId == GraphSceneId.DIFF

private fun GraphEditorStateService.markQaResultForIntegration(result: GraphPatchResult) {
    invokeAsyncRequestSupportForIntegration(
        methodName = "markQaResult",
        result,
        AsyncRequestState.succeeded(),
        null,
        true,
    )
}

private fun GraphEditorStateService.markDraftPatchPreviewForIntegration(patch: GraphPatch) {
    val supportField = javaClass.getDeclaredField("workbench")
    supportField.isAccessible = true
    val support = supportField.get(this)
    val method = support.javaClass.getDeclaredMethod("markDraftPatchPreview", GraphPatch::class.java)
    method.isAccessible = true
    method.invoke(support, patch)
}

private fun GraphEditorStateService.markGeneratedCodeDraftsForIntegration(
    drafts: List<GeneratedCodeDraft>,
    warnings: List<String>,
    source: LlmResultSource,
    promptPreview: String?,
) {
    invokeAsyncRequestSupportForIntegration(
        methodName = "markGeneratedCodeDrafts",
        drafts,
        warnings,
        source,
        promptPreview,
        AsyncRequestState.succeeded(),
    )
}

private fun GraphEditorStateService.invokeAsyncRequestSupportForIntegration(
    methodName: String,
    vararg args: Any?,
) {
    val supportField = javaClass.getDeclaredField("asyncRequests")
    supportField.isAccessible = true
    val support = supportField.get(this)
    val method = support.javaClass.declaredMethods.first { candidate ->
        candidate.name == methodName && candidate.parameterCount == args.size
    }
    method.isAccessible = true
    method.invoke(support, *args)
}
