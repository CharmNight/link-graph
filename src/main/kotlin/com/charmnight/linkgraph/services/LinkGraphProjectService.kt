package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.charmnight.linkgraph.ui.GraphBrowserPanel
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class LinkGraphProjectService(private val project: Project) {
    @Volatile
    private var browserPanel: GraphBrowserPanel? = null

    fun getOrCreateBrowserPanel(): GraphBrowserPanel {
        browserPanel?.let { return it }
        return synchronized(this) {
            browserPanel ?: GraphBrowserPanel(project).also { browserPanel = it }
        }
    }

    fun openToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) ?: return
        project.getService(GraphEditorStateService::class.java).markToolWindowOpened()
        toolWindow.show()
        toolWindow.activate(null)
    }

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        project.getService(GraphEditorStateService::class.java).loadGraph(graph, source)
    }

    fun pushSelectedMethod(signature: String) {
        project.getService(GraphEditorStateService::class.java).pushSelectedMethod(signature)
    }
}
