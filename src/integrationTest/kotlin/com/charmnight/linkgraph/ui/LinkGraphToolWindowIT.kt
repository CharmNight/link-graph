package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.actions.OpenLinkGraphAction
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
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
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
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

class LinkGraphToolWindowIT : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(
            GraphEditorStateService::class.java,
            GraphEditorStateService(),
        )
        project.registerServiceInstance(
            LinkGraphProjectService::class.java,
            LinkGraphProjectService(project),
        )

        val toolWindowManager = ToolWindowManager.getInstance(project)
        if (toolWindowManager.getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) == null) {
            val toolWindow = toolWindowManager.registerToolWindow(
                LinkGraphToolWindowFactory.TOOL_WINDOW_ID,
                false,
                ToolWindowAnchor.RIGHT,
                testRootDisposable,
            )
            LinkGraphToolWindowFactory().createToolWindowContent(project, toolWindow)
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
        val browserPanel = GraphBrowserPanel(project)
        val method = GraphBrowserPanel::class.java.getDeclaredMethod("buildRuntimeProbeScript", String::class.java)
        method.isAccessible = true

        val script = method.invoke(browserPanel, "flowchart-debug") as String

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
        assertNotNull(snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph!!.nodes.isNotEmpty())
        assertNotNull(snapshot.selectedNodeId)
        val selectedNode = snapshot.visibleGraph!!.nodes.firstOrNull { it.id == snapshot.selectedNodeId }
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
        assertNotNull(snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph!!.nodes.isNotEmpty())
        assertNotNull(snapshot.selectedNodeId)
        val selectedNode = snapshot.visibleGraph!!.nodes.firstOrNull { it.id == snapshot.selectedNodeId }
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
        val visibleGraph = snapshot.visibleGraph
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
        val visibleGraph = snapshot.visibleGraph
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(visibleGraph)
        assertTrue(
            "expected Kotlin getter node to be selected from accessor context",
            visibleGraph!!.nodes.any { node -> node.title == "AccessorService.getRaw" && node.type == NodeType.METHOD },
        )
        assertTrue(
            "expected accessor current-method graph to include downstream normalize call",
            visibleGraph.nodes.any { node -> node.title == "Formatter.normalize" },
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
        val visibleGraph = snapshot.visibleGraph
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertNotNull(visibleGraph)
        assertTrue(
            "expected constructor current-method graph to include constructor anchor",
            visibleGraph!!.nodes.any { node -> node.title == "PrimaryCtorFlow.PrimaryCtorFlow" && node.type == NodeType.METHOD },
        )
        assertTrue(
            "expected constructor current-method graph to include initializer downstream call",
            visibleGraph.nodes.any { node -> node.title == "Formatter.normalize" },
        )
        assertTrue(
            "supported Kotlin constructor should not be shown as unsupported boundary",
            visibleGraph.nodes.none { node -> node.metadata["linkGraph.boundary.kind"] != null },
        )
    }

    fun testBridgeThreadCanRequestCurrentMethodGraph() {
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
                bridge.dispatch(GraphEditorMessage.RequestCurrentMethodGraph)
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
        assertNotNull(snapshot.visibleGraph)
        assertTrue(snapshot.visibleGraph!!.nodes.isNotEmpty())
    }

    fun testBridgeDispatchCanOpenSettingsThroughProjectService() {
        var opened = false
        project.getService(LinkGraphProjectService::class.java).testOpenSettingsOverride = {
            opened = true
        }

        GraphEditorBridge(project).dispatch(GraphEditorMessage.OpenSettings)

        assertTrue(opened)
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("SUCCESS", snapshot.operationFeedback!!.level.name)
        assertEquals("已打开 IDE 设置 > Link Graph。", snapshot.operationFeedback!!.message)
    }

    fun testAsyncCurrentMethodGraphReportsWarningWhenCaretIsOutsideMethod() {
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

        project.getService(LinkGraphProjectService::class.java).loadCurrentMethodGraphAsync()
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("WARNING", snapshot.operationFeedback!!.level.name)
        assertEquals("当前光标不在方法内，请先把光标放到方法签名或方法体内。", snapshot.operationFeedback!!.message)
    }

    fun testCurrentMethodGraphExtractionRunsOffEdt() {
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
        val projectService = LinkGraphProjectService(project).apply {
            testSemanticAnalyzerOverride = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { codeHandle, _ ->
                            extractorRanOnDispatchThread = ApplicationManager.getApplication().isDispatchThread
                            minimalSemanticResult(codeHandle)
                        },
                    ),
                ),
            )
        }

        ApplicationManager.getApplication().invokeAndWait {
            projectService.loadCurrentMethodGraphAsync()
        }
        waitForGraphSource("currentMethod")

        assertNotNull(extractorRanOnDispatchThread)
        assertFalse(extractorRanOnDispatchThread!!)
    }

    fun testAsyncCurrentMethodGraphLoadingDoesNotBlockEdt() {
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
        val projectService = project.getService(LinkGraphProjectService::class.java).apply {
            testSemanticAnalyzerOverride = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { codeHandle, _ ->
                            started.countDown()
                            assertTrue("timed out waiting to release analyzer", release.await(5, TimeUnit.SECONDS))
                            minimalSemanticResult(codeHandle)
                        },
                    ),
                ),
            )
        }

        ApplicationManager.getApplication().invokeAndWait {
            projectService.loadCurrentMethodGraphAsync()
        }

        assertTrue("expected background extraction to start", started.await(1, TimeUnit.SECONDS))
        val pendingSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals("operationFeedback", pendingSnapshot.lastMessageType)
        assertEquals("正在分析当前方法链路：OrderService.place", pendingSnapshot.operationFeedback?.message)

        release.countDown()
        waitForGraphSource("currentMethod")
    }

    fun testAsyncCurrentMethodGraphCancellationDoesNotSurfaceAsError() {
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
        val projectService = project.getService(LinkGraphProjectService::class.java).apply {
            testSemanticAnalyzerOverride = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { _, _ ->
                            started.countDown()
                            throw ProcessCanceledException()
                        },
                    ),
                ),
            )
        }

        ApplicationManager.getApplication().invokeAndWait {
            projectService.loadCurrentMethodGraphAsync()
        }

        assertTrue("expected background extraction to start", started.await(1, TimeUnit.SECONDS))
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("INFO", snapshot.operationFeedback!!.level.name)
        assertEquals("正在分析当前方法链路：OrderService.place", snapshot.operationFeedback!!.message)
        assertEquals(null, snapshot.visibleGraph)
    }

    fun testAsyncCurrentMethodGraphDisposedFailureDoesNotSurfaceAsError() {
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
        val projectService = project.getService(LinkGraphProjectService::class.java).apply {
            testSemanticAnalyzerOverride = SemanticAnalyzer(
                registry = SemanticProviderRegistry(
                    listOf(
                        semanticProvider { _, _ ->
                            started.countDown()
                            throw AlreadyDisposedException("stub index disposed")
                        },
                    ),
                ),
            )
        }

        ApplicationManager.getApplication().invokeAndWait {
            projectService.loadCurrentMethodGraphAsync()
        }

        assertTrue("expected background extraction to start", started.await(1, TimeUnit.SECONDS))
        drainIdeQueue()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.operationFeedback)
        assertEquals("INFO", snapshot.operationFeedback!!.level.name)
        assertEquals("正在分析当前方法链路：OrderService.place", snapshot.operationFeedback!!.message)
        assertEquals(null, snapshot.visibleGraph)
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

        val appended = project.getService(LinkGraphProjectService::class.java).addCurrentMethodNode()

        assertTrue(appended)
        val snapshot = stateService.snapshot()
        assertEquals("graphChanged", snapshot.lastMessageType)
        assertNotNull(snapshot.visibleGraph)
        assertEquals(2, snapshot.visibleGraph!!.nodes.size)
        val methodNode = snapshot.visibleGraph!!.nodes.firstOrNull { it.type == NodeType.METHOD }
        assertNotNull(methodNode)
        assertEquals("OrderService.place", methodNode!!.title)
        assertTrue(methodNode.location!!.contains("OrderService.java"))
        assertEquals(listOf("java.lang.String"), methodNode.inputs)
        assertEquals(listOf("java.lang.String"), methodNode.outputs)
        assertEquals("提交订单", methodNode.doc)
    }

    fun testApplyDraftPatchPreviewOnlyMutatesDraftLayer() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
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
            summary = "apply audit note",
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

        projectService.loadGraph(factGraph, "code-graph")
        projectService.previewDraftPatch(patch)
        projectService.applyDraftPatchPreview()

        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.referenceFactGraph?.nodes?.size)
        assertEquals(2, snapshot.workingGraph?.nodes?.size)
        assertEquals(2, snapshot.visibleGraph?.nodes?.size)
        assertTrue(snapshot.workingGraph!!.nodes.any { it.id == "doc:default-fallback-note" && it.sourceTag == GraphSourceTag.DRAFT_AI })
        assertEquals(null, snapshot.draftPatchPreview)
    }

    fun testRequestAuditGeneratesAnswerAndDraftPatchPreview() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
        projectService.loadGraph(
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

        val result = projectService.requestAudit(
            question = "请审计当前范围：这段链路是否遗漏了默认兜底逻辑？",
            selectedNodeIds = listOf("uncertain:channel-router"),
        )

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(result)
        assertTrue(result.answer.contains("默认兜底"))
        assertNotNull(snapshot.auditResult)
        assertNotNull(snapshot.draftPatchPreview)
        assertTrue(snapshot.draftPatchPreview!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
    }

    fun testRequestDiffReviewGeneratesRevisionPatchPreview() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|DefaultChannelFallback"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        projectService.loadGraph(codeGraph, "code-graph")
        projectService.importMermaid(mermaid)
        projectService.showDiffMode()
        val result = projectService.requestDiffReview("这些差异意味着什么？请给出修订草稿。")

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(result)
        assertTrue(result!!.answer.contains("仅设计图有"))
        assertNotNull(snapshot.diffReviewResult)
        assertNotNull(snapshot.draftPatchPreview)
        assertTrue(snapshot.draftPatchPreview!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
    }

    fun testClearDraftPatchPreviewCanBeRestoredFromAuditResult() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
        projectService.loadGraph(
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

        projectService.requestAudit(
            question = "请审计当前范围：这段链路是否遗漏了默认兜底逻辑？",
            selectedNodeIds = listOf("uncertain:channel-router"),
        )
        projectService.clearDraftPatchPreview()

        var snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.auditResult)
        assertEquals(null, snapshot.draftPatchPreview)

        projectService.restoreDraftPatchPreview(LinkGraphProjectService.DraftPatchPreviewSource.AUDIT)

        snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.draftPatchPreview)
        assertTrue(snapshot.draftPatchPreview!!.operations.any { it.action == GraphPatchAction.ADD_NODE })
    }

    fun testUndoLastDraftPatchApplyRestoresDraftGraphAndPreview() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
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
            summary = "apply audit note",
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

        projectService.loadGraph(factGraph, "code-graph")
        projectService.previewDraftPatch(patch)
        projectService.applyDraftPatchPreview()
        projectService.undoLastDraftPatchApply()

        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.referenceFactGraph?.nodes?.size)
        assertEquals(1, snapshot.workingGraph?.nodes?.size)
        assertEquals(1, snapshot.visibleGraph?.nodes?.size)
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
        assertEquals(1, snapshot.visibleGraph?.nodes?.size)
    }

    fun testImportExportAndDiffModeFlowUpdatesEditorState() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        projectService.loadGraph(codeGraph, "code-graph")
        projectService.importMermaid(mermaid)
        val exported = projectService.exportMermaid()
        val diffResult = projectService.showDiffMode()

        val snapshot = stateService.snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertEquals(2, snapshot.designBaselineGraph?.nodes?.size)
        assertEquals(exported, snapshot.exportedMermaid)
        assertTrue(exported.contains("ENTRY"))
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
            snapshot.visibleGraph?.nodes?.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true,
        )
    }

    fun testExportMermaidWritesUserFeedbackIntoEditorState() {
        val projectService = project.getService(LinkGraphProjectService::class.java)
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

        projectService.loadGraph(graph, "code-graph")
        val exported = projectService.exportMermaid()

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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true)
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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.RequestSyncPreview)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(snapshot.syncPreviewRequested)
        assertTrue(snapshot.syncPreviewItems.isNotEmpty())
        assertTrue(snapshot.syncPreviewItems.any { it.title.contains("OrderDraftDto") })
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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(mermaid, snapshot.importedMermaid)
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true)
        assertTrue("Unexpected issues: ${snapshot.mermaidIssues}", snapshot.mermaidIssues.isEmpty())
    }

    fun testBridgeDispatchRequestsAuditAndAppliesDraftPatch() {
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(
            GraphEditorMessage.RequestAudit(
                question = "请审计当前范围：这段链路是否遗漏了默认兜底逻辑？",
                selectedNodeIds = listOf("uncertain:channel-router"),
            ),
        )
        waitForAuditResultAndDraftPreview()
        bridge.dispatch(GraphEditorMessage.ApplyDraftPatchPreview())
        waitForDraftGraphNodeCount(expectedNodeCount = 2)

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(null, snapshot.auditResult)
        assertEquals(2, snapshot.workingGraph?.nodes?.size)
        assertTrue(snapshot.workingGraph!!.nodes.any { it.sourceTag == GraphSourceTag.DRAFT_AI })
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

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(
            GraphEditorMessage.RequestAudit(
                question = "请审计当前范围：这段链路是否遗漏了默认兜底逻辑？",
                selectedNodeIds = listOf("uncertain:channel-router"),
            ),
        )
        waitForAuditResultAndDraftPreview()
        bridge.dispatch(GraphEditorMessage.ClearDraftPatchPreview)
        bridge.dispatch(GraphEditorMessage.RestoreDraftPatchPreview(GraphEditorMessage.DraftPatchPreviewSource.AUDIT))
        bridge.dispatch(GraphEditorMessage.ApplyDraftPatchPreview())
        waitForDraftGraphNodeCount(expectedNodeCount = 2)
        bridge.dispatch(GraphEditorMessage.UndoLastDraftPatchApply)
        waitForDraftUndoState()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(null, snapshot.auditResult)
        assertNotNull(snapshot.draftPatchPreview)
        assertEquals(1, snapshot.workingGraph?.nodes?.size)
        assertEquals("undoDraftPatchApply", snapshot.lastMessageType)
    }

    fun testBridgeDispatchBuildsGenerationPlan() {
        val bridge = GraphEditorBridge(project)
        project.getService(LinkGraphProjectService::class.java).testEffectiveGenerationSettingsOverride = LinkGraphSettingsState(
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
            ENTRY["METHOD|OrderService.place"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.RequestGenerationPlan)
        waitForGenerationPlan()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.generationPlan)
        assertTrue(snapshot.generationPlan!!.items.isNotEmpty())
        assertTrue(snapshot.generationPlan!!.summary.isNotBlank())
    }

    fun testBridgeDispatchGeneratesAndWritesCodeDrafts() {
        val bridge = GraphEditorBridge(project)
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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.RequestGenerationPlan)
        bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
        waitForCodeDrafts()
        bridge.dispatch(GraphEditorMessage.ApplyCodeDrafts)
        waitForCodeDraftWriteReport()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(snapshot.generatedCodeDrafts.isNotEmpty())
        val draft = snapshot.generatedCodeDrafts.single()
        assertEquals("src/main/java/com/example/OrderDraftDto.java", draft.targetPath)
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(draft.targetPath))

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
            ENTRY["METHOD|OrderService.place|signature=com.example.OrderService.place(java.lang.String):void"]
            DTO["CLASS|OrderDraftDto"]
            ENTRY -- CALL --> DTO
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "bridge-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.RequestGenerationPlan)
        bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
        waitForCodeDrafts()

        val generatedDraft = project.getService(GraphEditorStateService::class.java).snapshot().generatedCodeDrafts.single()
        bridge.dispatch(GraphEditorMessage.ApplySingleCodeDraft(generatedDraft.id))
        bridge.dispatch(GraphEditorMessage.RequestDraftNavigation(generatedDraft.targetPath))
        waitForCodeDraftWriteReport()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertNotNull(snapshot.generatedCodeDraftWriteReport)
        assertTrue(snapshot.generatedCodeDraftWriteReport!!.writtenFiles.contains(generatedDraft.targetPath))

        val writtenPath = Path.of(project.basePath!!).resolve(generatedDraft.targetPath)
        assertTrue(Files.exists(writtenPath))
        assertTrue(
            FileEditorManager.getInstance(project).selectedFiles.any { selected ->
                selected.path == writtenPath.toString()
            },
        )
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

    private fun waitForAuditResultAndDraftPreview() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.auditResult != null && snapshot.draftPatchPreview != null) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected audit result and draft patch preview to be available")
    }

    private fun waitForDraftGraphNodeCount(expectedNodeCount: Int) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (snapshot.workingGraph?.nodes?.size == expectedNodeCount) {
                return
            }
            Thread.sleep(100)
        }
        fail("Expected draft graph node count to reach $expectedNodeCount")
    }

    private fun waitForDraftUndoState() {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.lastMessageType == "undoDraftPatchApply" &&
                snapshot.workingGraph?.nodes?.size == 1 &&
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

    private fun semanticProvider(
        analyze: (CodeSubjectHandle, TraversalBudgetPolicy) -> SemanticAnalysisResult,
    ): SemanticProvider {
        return object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                return analyze(handle as CodeSubjectHandle, budgetPolicy)
            }
        }
    }

    private fun minimalSemanticResult(codeHandle: CodeSubjectHandle): SemanticAnalysisResult {
        val methodUnitId = "method:${codeHandle.methodSignature}"
        return SemanticAnalysisResult(
            subject = codeHandle,
            anchors = listOf(
                SemanticAnchor(
                    id = "anchor-entry",
                    targetUnitId = methodUnitId,
                ),
            ),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = methodUnitId,
                    title = codeHandle.displayName,
                    signature = codeHandle.methodSignature,
                ),
            ),
            relations = emptyList(),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = listOf(
                SourceMapping(
                    sourcePath = codeHandle.sourcePath,
                    sourceRange = codeHandle.sourceRange,
                    targetUnitId = methodUnitId,
                ),
            ),
        )
    }

    private fun drainIdeQueue(cycles: Int = 10) {
        repeat(cycles) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }
}
