package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.InvocationExpansionWorkflow
import com.charmnight.linkgraph.application.workflow.SourceNavigationWorkflow

/** 处理源码导航、调用展开和设置入口命令。 */
internal class SourceNavigationApplicationCommandHandler(
    private val sourceNavigationFlow: SourceNavigationWorkflow,
    private val invocationExpansionFlow: InvocationExpansionWorkflow,
) {
    fun handle(command: ApplicationCommand.RequestSourceNavigation) {
        sourceNavigationFlow.requestSourceNavigation(command.nodeId)
    }

    fun handle(command: ApplicationCommand.RequestExpandInvocation) {
        invocationExpansionFlow.requestExpandInvocation(command.nodeId, command.frontendRequestedAtMs)
    }

    fun handle(command: ApplicationCommand.RequestRemoveInvocationExpansion) {
        invocationExpansionFlow.requestRemoveInvocationExpansion(command.expansionId)
    }

    fun handle(command: ApplicationCommand.OpenSettings) {
        sourceNavigationFlow.openSettings()
    }
}
