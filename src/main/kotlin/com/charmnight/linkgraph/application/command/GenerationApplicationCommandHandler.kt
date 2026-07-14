package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow

/** 处理代码草稿生成、预览、写入和导航命令。 */
internal class GenerationApplicationCommandHandler(
    private val codeDraftGenerationFlow: CodeDraftGenerationWorkflow,
    private val codeDraftApplyFlow: CodeDraftApplyWorkflow,
    private val openCodeDraftNativeDiffHook: () -> ((String) -> Unit)? = { null },
) {
    fun handle(command: ApplicationCommand.RequestCodeDrafts) {
        codeDraftGenerationFlow.requestCodeDraftsAsync()
    }

    fun handle(command: ApplicationCommand.ApplyCodeDrafts) {
        codeDraftApplyFlow.applyCodeDrafts()
    }

    fun handle(command: ApplicationCommand.ApplySingleCodeDraft) {
        codeDraftApplyFlow.applySingleCodeDraft(command.draftId)
    }

    fun handle(command: ApplicationCommand.OpenCodeDraftNativeDiff) {
        openCodeDraftNativeDiffHook()?.invoke(command.draftId)
            ?: codeDraftApplyFlow.openCodeDraftNativeDiff(command.draftId)
    }

    fun handle(command: ApplicationCommand.RequestDraftNavigation) {
        codeDraftApplyFlow.requestDraftNavigation(command.targetPath)
    }
}
