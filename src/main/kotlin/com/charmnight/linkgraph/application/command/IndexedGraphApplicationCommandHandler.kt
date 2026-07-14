package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureGraphWorkflow
import com.charmnight.linkgraph.application.workflow.architecture.ClassDiagramWorkflow

/** 按视图类型把索引图请求分派给对应工作流。 */
internal class IndexedGraphApplicationCommandHandler(
    private val architectureGraphFlow: ArchitectureGraphWorkflow,
    private val classDiagramFlow: ClassDiagramWorkflow,
    private val reviewGraphFlow: ReviewGraphWorkflow,
) {
    fun handle(command: ApplicationCommand.RequestIndexedGraph) {
        when (command.request.view) {
            IndexedGraphView.ARCHITECTURE -> architectureGraphFlow.requestIndexedGraph(command.request)
            IndexedGraphView.CLASS_DIAGRAM -> classDiagramFlow.requestIndexedGraph(command.request)
            IndexedGraphView.REVIEW -> reviewGraphFlow.requestIndexedGraph(command.request)
        }
    }
}
