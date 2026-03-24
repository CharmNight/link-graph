package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.actions.OpenLinkGraphAction
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance

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
        assertTrue(browserPanel.currentEntryUrl().contains("linkgraph"))
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
        assertEquals(1, snapshot.graph?.nodes?.size)
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
        assertEquals(2, snapshot.designGraph?.nodes?.size)
        assertEquals(exported, snapshot.exportedMermaid)
        assertTrue(exported.contains("ENTRY"))
        assertTrue(snapshot.diffMode)
        assertNotNull(snapshot.diff)
        assertNotNull(diffResult)
        assertTrue(
            snapshot.diff!!.entries.any { it.status == DiffStatus.ONLY_IN_MERMAID && it.elementId.contains("class:orderdraftdto") },
        )
        assertTrue(
            snapshot.graph?.nodes?.any { it.diff.status == DiffStatus.ONLY_IN_MERMAID } == true,
        )
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
}
