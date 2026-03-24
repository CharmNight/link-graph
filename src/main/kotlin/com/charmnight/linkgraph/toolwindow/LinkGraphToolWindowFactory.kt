package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LinkGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contentCount > 0) {
            return
        }
        val panel = project.getService(LinkGraphProjectService::class.java).getOrCreateBrowserPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID: String = "Link Graph"
    }
}
