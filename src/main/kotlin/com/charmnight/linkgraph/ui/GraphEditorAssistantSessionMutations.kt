package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.AssistantContextSnapshot
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantTurnKind
import com.charmnight.linkgraph.workbench.AssistantTurnRef
import com.charmnight.linkgraph.workbench.QaMode

/**
 * 基于当前快照状态构建新的助手会话上下文。
 *
 * 若上下文已被锁定，则保留原上下文，否则依据当前选择、显示模式等重新生成；
 * 同时可更新当前激活的意图、动作标识以及问答模式。
 */
internal fun GraphEditorStateSnapshot.withAssistantContextFromCurrentState(
    activeIntent: AssistantIntent = assistantSessionState.activeIntent,
    activeActionId: AssistantActionId? = assistantSessionState.activeActionId,
    qaMode: QaMode? = null,
): GraphEditorStateSnapshot {
    val nextContext = if (assistantSessionState.contextLocked) {
        assistantSessionState.context
    } else {
        assistantContextSnapshot()
    }
    return copy(
        assistantSessionState = assistantSessionState.copy(
            activeIntent = activeIntent,
            activeActionId = activeActionId,
            context = nextContext,
            composer = qaMode
                ?.let { mode -> assistantSessionState.composer.copy(qaMode = mode) }
                ?: assistantSessionState.composer,
        ),
    )
}

/**
 * 更新助手上下文中所选的差异条目列表。
 *
 * 当上下文处于锁定状态时不做修改，直接返回原快照；
 * 否则更新上下文中保留的差异条目标识。
 */
internal fun GraphEditorStateSnapshot.withAssistantSelectedDiffItemIds(
    selectedDiffItemIds: List<String>,
): GraphEditorStateSnapshot {
    if (assistantSessionState.contextLocked) {
        return this
    }
    return copy(
        assistantSessionState = assistantSessionState.copy(
            context = assistantSessionState.context.copy(
                selectedDiffItemIds = selectedDiffItemIds,
            ),
        ),
    )
}

/**
 * 追加一轮助手对话到会话历史。
 *
 * 根据对话类型、意图、动作来源消息类型等信息构造一个新的回合引用，
 * 同时刷新会话上下文和作曲器（含问答模式），并将该回合追加到现有回合列表。
 */
internal fun GraphEditorStateSnapshot.withAssistantTurnRef(
    kind: AssistantTurnKind,
    activeIntent: AssistantIntent,
    activeActionId: AssistantActionId?,
    sourceMessageType: String,
    resultId: String,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
    qaMode: QaMode? = null,
): GraphEditorStateSnapshot {
    val nextContext = if (assistantSessionState.contextLocked) {
        assistantSessionState.context
    } else {
        assistantContextSnapshot()
    }
    val nextTurn = AssistantTurnRef(
        turnId = buildAssistantTurnId(kind, sourceMessageType, assistantSessionState.turns.size + 1, createdAtEpochMillis),
        kind = kind,
        intent = activeIntent,
        actionId = activeActionId,
        sourceMessageType = sourceMessageType,
        resultId = resultId,
        createdAtEpochMillis = createdAtEpochMillis,
        context = nextContext,
    )
    return copy(
        assistantSessionState = assistantSessionState.copy(
            activeIntent = activeIntent,
            activeActionId = activeActionId,
            context = nextContext,
            composer = qaMode
                ?.let { mode -> assistantSessionState.composer.copy(qaMode = mode) }
                ?: assistantSessionState.composer,
            turns = assistantSessionState.turns + nextTurn,
        ),
    )
}

/**
 * 从当前快照派生一份助手上下文快照。
 *
 * 收集当前选中的节点、差异条目、分析展示模式、场景以及方法签名等信息，
 * 并解析出供助手显示的范围标签，便于在后续对话中复用。
 */
private fun GraphEditorStateSnapshot.assistantContextSnapshot(): AssistantContextSnapshot {
    val currentSelectedNodeId = currentSceneState().selectedNodeId?.takeIf(String::isNotBlank)
    return AssistantContextSnapshot(
        selectedNodeIds = listOfNotNull(currentSelectedNodeId),
        selectedDiffItemIds = assistantSessionState.context.selectedDiffItemIds,
        analysisDisplayMode = analysisDisplayMode.name,
        currentSceneId = currentSceneId.name,
        selectedMethodSignature = selectedMethodSignature,
        scopeLabel = resolveAssistantScopeLabel(currentSelectedNodeId),
    )
}

/**
 * 解析助手上下文使用的范围标签。
 *
 * 优先使用选中的方法签名；其次取选中节点的标题；
 * 都没有时退回到当前的分析展示模式名称。
 */
private fun GraphEditorStateSnapshot.resolveAssistantScopeLabel(selectedNodeId: String?): String {
    if (!selectedMethodSignature.isNullOrBlank()) {
        return selectedMethodSignature
    }
    val selectedNodeTitle = selectedNodeId
        ?.let { nodeId -> currentVisibleGraphForAssistantContext().nodes.firstOrNull { node -> node.id == nodeId } }
        ?.title
    if (!selectedNodeTitle.isNullOrBlank()) {
        return selectedNodeTitle
    }
    return analysisDisplayMode.name
}

/**
 * 根据当前场景获取对应的可见图谱，用于生成助手上下文。
 *
 * 不同工作台场景（事实图、流程图、资源关系、架构图、类图、审阅图、差异图）
 * 各自有其可见图谱来源，差异图缺失时回退到工作区主图。
 */
private fun GraphEditorStateSnapshot.currentVisibleGraphForAssistantContext() =
    when (currentSceneId) {
        GraphSceneId.WORKSPACE_FACT -> factGraphView.visibleGraph
        GraphSceneId.WORKSPACE_FLOWCHART -> flowchartView.visibleGraph
        GraphSceneId.WORKSPACE_RESOURCE_RELATION -> resourceRelationView.visibleGraph
        GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> classDiagramView.visibleGraph
        GraphSceneId.WORKSPACE_REVIEW_GRAPH -> reviewGraphView.visibleGraph
        GraphSceneId.DIFF -> diffGraph ?: workspaceGraph
    }

/**
 * 构造一个全局唯一的助手回合标识。
 *
 * 由来源消息类型、回合类型的小写名、序号以及创建时间戳拼接而成，
 * 用于在历史中稳定引用某一回合。
 */
private fun buildAssistantTurnId(
    kind: AssistantTurnKind,
    sourceMessageType: String,
    sequence: Int,
    createdAtEpochMillis: Long,
): String = "${sourceMessageType}:${kind.name.lowercase()}:$sequence:$createdAtEpochMillis"
