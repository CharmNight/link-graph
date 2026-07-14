package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.GraphWorkspaceWorkflow
import com.charmnight.linkgraph.application.workflow.WorkspaceChangeCoordinator
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem

/** 处理工作台图加载、编辑、差异与同步命令。 */
internal class WorkspaceApplicationCommandHandler(
    private val workspaceFlow: GraphWorkspaceWorkflow,
    private val workspaceChangeCoordinator: WorkspaceChangeCoordinator,
) {
    fun handle(command: ApplicationCommand.LoadGraph) {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        workspaceFlow.loadGraph(command.graph, command.source)
    }

    fun handle(command: ApplicationCommand.ImportMermaid): GraphDocument {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        return workspaceFlow.importMermaid(command.mermaid)
    }

    fun handle(command: ApplicationCommand.ExportMermaid): String = workspaceFlow.exportMermaid()

    fun handle(command: ApplicationCommand.ShowDiffMode): GraphDifferResult? {
        workspaceChangeCoordinator.invalidateRequests()
        return workspaceFlow.showDiffMode()
    }

    fun handle(command: ApplicationCommand.ApplyGraphEditRequest) {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        workspaceFlow.handleGraphEditRequest(command.parseResult)
    }

    fun handle(command: ApplicationCommand.LayoutChanged) {
        workspaceFlow.handleFrontendLayoutChanged(command.positions)
    }

    fun handle(command: ApplicationCommand.RequestSyncPreview): List<SyncPreviewItem> =
        workspaceFlow.requestSyncPreview()
}
