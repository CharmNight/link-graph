package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory
import com.charmnight.linkgraph.ui.GraphBrowserPanel
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class LinkGraphProjectService(private val project: Project) {
    private val mermaidImporter = MermaidImporter()
    private val mermaidValidator = MermaidValidator()
    private val mermaidExporter = MermaidExporter()
    private val graphDiffer = GraphDiffer()

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
        stateService().loadGraph(graph, source)
    }

    fun importMermaid(mermaid: String): GraphDocument {
        val parseResult = mermaidImporter.import(mermaid)
        mermaidValidator.validate(parseResult.document, parseResult.issues)
        stateService().importMermaid(mermaid, parseResult.document)
        return parseResult.document
    }

    fun exportMermaid(): String {
        val snapshot = stateService().snapshot()
        val document = snapshot.designGraph ?: snapshot.graph ?: GraphDocument()
        return mermaidExporter.export(document).also { exported ->
            stateService().markMermaidExported(exported)
        }
    }

    fun showDiffMode(): GraphDifferResult? {
        val snapshot = stateService().snapshot()
        val codeGraph = snapshot.codeGraph ?: return null
        val designGraph = snapshot.designGraph ?: return null
        return graphDiffer.diff(codeGraph, designGraph).also { result ->
            stateService().showDiffMode(result.graph, result.diff)
        }
    }

    fun navigate(node: GraphNode): SourceNavigationService.NavigationTarget? {
        return project.getService(SourceNavigationService::class.java).navigate(node)
    }

    fun pushSelectedMethod(signature: String) {
        stateService().pushSelectedMethod(signature)
    }

    private fun stateService(): GraphEditorStateService {
        return project.getService(GraphEditorStateService::class.java)
    }
}
