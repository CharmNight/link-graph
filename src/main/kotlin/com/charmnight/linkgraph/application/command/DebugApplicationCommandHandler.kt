package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.ProjectDebugWorkflow

/** 处理调试自动化图谱命令。 */
internal class DebugApplicationCommandHandler(
    private val debugFlow: ProjectDebugWorkflow,
) {
    fun handle(command: ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode) {
        debugFlow.prepareDebugRequestedAnalysisDisplayModeIfPresent(command.envName)
    }

    fun handle(command: ApplicationCommand.LoadDebugMethodGraphBySignature) {
        debugFlow.loadDebugMethodGraphBySignatureAsync(command.signature)
    }

    fun handle(command: ApplicationCommand.LoadDebugGraph) {
        debugFlow.loadDebugGraph(command.mode)
    }
}
