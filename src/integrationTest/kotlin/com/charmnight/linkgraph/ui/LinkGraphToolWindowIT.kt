package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.actions.OpenLinkGraphAction
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
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
}
