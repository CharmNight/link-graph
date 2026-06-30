package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantContextSnapshot
import com.charmnight.linkgraph.workbench.AssistantSessionState

/**
 * 助手会话状态的前端传输对象（DTO）。
 *
 * P2-6 替代之前的无类型映射载荷，给出类型稳定的契约：
 * - 字段名拼写错误会在编译期暴露
 * - 字段缺省或新增会被 IDE / 重构识别
 * - Gson 序列化时按声明顺序输出，与原 `linkedMapOf` 顺序一致
 *
 * 字段与前端 TS 类型 `AssistantSessionState` 一一对应。
 */
internal data class AssistantContextSnapshotDto(
    val selectedNodeIds: List<String>,
    val selectedDiffItemIds: List<String>,
    val analysisDisplayMode: String?,
    val currentSceneId: String?,
    val selectedMethodSignature: String?,
    val scopeLabel: String,
)

internal data class AssistantComposerTargetDto(
    val kind: String,
    val requestId: String? = null,
    val selectedNodeIds: List<String>? = null,
    val sourceThreadId: String? = null,
    val mode: String? = null,
    val stepId: String? = null,
    val stepTitle: String? = null,
    val focusNodeId: String? = null,
    val planItemId: String? = null,
    val threadId: String? = null,
    val targetNodeIds: List<String>? = null,
)

internal data class AssistantComposerStateDto(
    val draft: String,
    val target: AssistantComposerTargetDto,
    val draftSource: String?,
    val actionId: String?,
    val sceneId: String?,
    val qaMode: String,
)

internal data class AssistantTurnHeaderDto(
    val turnId: String,
    val kind: String,
    val intent: String?,
    val actionId: String?,
    val sourceMessageType: String?,
    val resultId: String?,
    val createdAtEpochMillis: Long,
    val context: AssistantContextSnapshotDto,
)

internal data class AssistantSessionStateDto(
    val sessionId: String,
    val activeIntent: String,
    val activeActionId: String?,
    val contextLocked: Boolean,
    val context: AssistantContextSnapshotDto,
    val composer: AssistantComposerStateDto,
    val nextResultSequence: Long,
    val turns: List<AssistantTurnHeaderDto>,
)

internal object GraphEditorAssistantSessionRenderer {
    fun assistantSessionStateToDto(
        state: AssistantSessionState,
    ): AssistantSessionStateDto = AssistantSessionStateDto(
        sessionId = state.sessionId,
        activeIntent = state.activeIntent.name,
        activeActionId = state.activeActionId?.name,
        contextLocked = state.contextLocked,
        context = state.context.toDto(),
        composer = AssistantComposerStateDto(
            draft = state.composer.draft,
            target = state.composer.target.toDto(),
            draftSource = state.composer.draftSource,
            actionId = state.composer.actionId?.name,
            sceneId = state.composer.sceneId,
            qaMode = state.composer.qaMode.name,
        ),
        nextResultSequence = state.nextResultSequence,
        turns = state.turns.map { turn ->
            AssistantTurnHeaderDto(
                turnId = turn.turnId,
                kind = turn.kind.name,
                intent = turn.intent?.name,
                actionId = turn.actionId?.name,
                sourceMessageType = turn.sourceMessageType,
                resultId = turn.resultId,
                createdAtEpochMillis = turn.createdAtEpochMillis,
                context = turn.context.toDto(),
            )
        },
    )

    private fun AssistantComposerTarget.toDto(): AssistantComposerTargetDto = when (this) {
        AssistantComposerTarget.NewTask -> AssistantComposerTargetDto(kind = "NewTask")
        is AssistantComposerTarget.QaRecovery -> AssistantComposerTargetDto(
            kind = "QaRecovery",
            requestId = requestId,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode?.name,
        )
        is AssistantComposerTarget.ExplanationFollowUp -> AssistantComposerTargetDto(
            kind = "ExplanationFollowUp",
            stepId = stepId,
            stepTitle = stepTitle,
            focusNodeId = focusNodeId,
        )
        is AssistantComposerTarget.GenerationDiscussion -> AssistantComposerTargetDto(
            kind = "GenerationDiscussion",
            planItemId = planItemId,
        )
        is AssistantComposerTarget.RiskInvestigation -> AssistantComposerTargetDto(
            kind = "RiskInvestigation",
            threadId = threadId,
            targetNodeIds = targetNodeIds,
        )
    }

    private fun AssistantContextSnapshot.toDto(): AssistantContextSnapshotDto = AssistantContextSnapshotDto(
        selectedNodeIds = selectedNodeIds,
        selectedDiffItemIds = selectedDiffItemIds,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        selectedMethodSignature = selectedMethodSignature,
        scopeLabel = scopeLabel,
    )
}
