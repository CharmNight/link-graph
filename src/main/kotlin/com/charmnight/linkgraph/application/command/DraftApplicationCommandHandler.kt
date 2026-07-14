package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.ConfirmedDraftChangeCoordinator
import com.charmnight.linkgraph.application.workflow.DraftPatchWorkflow
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/** 处理候选变更确认和草稿补丁命令。 */
internal class DraftApplicationCommandHandler(
    private val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator,
    private val draftPatchFlow: DraftPatchWorkflow,
) {
    fun handle(command: ApplicationCommand.ConfirmQaCandidateChange): DraftWorkbenchEntry? =
        confirmedDraftCoordinator.confirm(command.changeId)

    fun handle(command: ApplicationCommand.UnconfirmQaCandidateChange): DraftWorkbenchEntry? =
        confirmedDraftCoordinator.unconfirm(command.changeId)

    fun handle(command: ApplicationCommand.ApplyDraftPatchPreview): GraphDocument? =
        draftPatchFlow.applyDraftPatchPreview(command.operationIds)

    fun handle(command: ApplicationCommand.ClearDraftPatchPreview) =
        draftPatchFlow.clearDraftPatchPreview()

    fun handle(command: ApplicationCommand.RestoreDraftPatchPreview): GraphPatch? =
        draftPatchFlow.restoreDraftPatchPreview(command.source)

    fun handle(command: ApplicationCommand.UndoLastDraftPatchApply): GraphDocument? =
        draftPatchFlow.undoLastDraftPatchApply()
}
