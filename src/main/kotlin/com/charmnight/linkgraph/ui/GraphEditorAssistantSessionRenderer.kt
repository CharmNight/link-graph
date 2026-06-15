package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantContextSnapshot
import com.charmnight.linkgraph.workbench.AssistantSessionState

internal object GraphEditorAssistantSessionRenderer {
    fun assistantSessionStateToMap(
        state: AssistantSessionState,
    ): Map<String, Any?> = linkedMapOf(
        "sessionId" to state.sessionId,
        "activeIntent" to state.activeIntent.name,
        "activeActionId" to state.activeActionId?.name,
        "contextLocked" to state.contextLocked,
        "context" to assistantContextSnapshotToMap(state.context),
        "composer" to linkedMapOf(
            "draft" to state.composer.draft,
            "target" to assistantComposerTargetToMap(state.composer.target),
            "draftSource" to state.composer.draftSource,
            "actionId" to state.composer.actionId?.name,
            "sceneId" to state.composer.sceneId,
        ),
        "nextResultSequence" to state.nextResultSequence,
        "turns" to state.turns.map { turn ->
            linkedMapOf(
                "turnId" to turn.turnId,
                "kind" to turn.kind.name,
                "intent" to turn.intent?.name,
                "actionId" to turn.actionId?.name,
                "sourceMessageType" to turn.sourceMessageType,
                "resultId" to turn.resultId,
                "createdAtEpochMillis" to turn.createdAtEpochMillis,
                "context" to assistantContextSnapshotToMap(turn.context),
            )
        },
    )

    private fun assistantComposerTargetToMap(
        target: AssistantComposerTarget,
    ): Map<String, Any?> = when (target) {
        AssistantComposerTarget.NewTask -> linkedMapOf(
            "kind" to "NewTask",
        )
        is AssistantComposerTarget.QaRecovery -> linkedMapOf(
            "kind" to "QaRecovery",
            "requestId" to target.requestId,
            "selectedNodeIds" to target.selectedNodeIds,
            "sourceThreadId" to target.sourceThreadId,
            "mode" to target.mode?.name,
        )
        is AssistantComposerTarget.ExplanationFollowUp -> linkedMapOf(
            "kind" to "ExplanationFollowUp",
            "stepId" to target.stepId,
            "stepTitle" to target.stepTitle,
            "focusNodeId" to target.focusNodeId,
        )
        is AssistantComposerTarget.GenerationDiscussion -> linkedMapOf(
            "kind" to "GenerationDiscussion",
            "planItemId" to target.planItemId,
        )
        is AssistantComposerTarget.RiskInvestigation -> linkedMapOf(
            "kind" to "RiskInvestigation",
            "threadId" to target.threadId,
            "targetNodeIds" to target.targetNodeIds,
        )
    }

    private fun assistantContextSnapshotToMap(
        context: AssistantContextSnapshot,
    ): Map<String, Any?> = linkedMapOf(
        "selectedNodeIds" to context.selectedNodeIds,
        "selectedDiffItemIds" to context.selectedDiffItemIds,
        "analysisDisplayMode" to context.analysisDisplayMode,
        "currentSceneId" to context.currentSceneId,
        "selectedMethodSignature" to context.selectedMethodSignature,
        "scopeLabel" to context.scopeLabel,
    )
}
