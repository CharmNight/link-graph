package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance

class LinkGraphDebugStartupActivityTest : BasePlatformTestCase() {
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

    fun testAutoOpensToolWindowOnlyWhenDebugFlagIsEnabled() {
        val disabled = LinkGraphDebugStartupActivity(autoOpenEnabled = { false })
        disabled.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val disabledSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertFalse(disabledSnapshot.toolWindowOpenRequested)

        val enabled = LinkGraphDebugStartupActivity(autoOpenEnabled = { true })
        enabled.runActivity(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val enabledSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(enabledSnapshot.toolWindowOpenRequested)
    }
}
