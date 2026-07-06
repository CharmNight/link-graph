package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.capability.QaCapabilityInput
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaModeContext

/**
 * 把问答 mode context + 快照 + 设置组装成 [QaCapabilityInput]，
 * 供 QaCapability 在 runtime 调用 LLM 时使用。
 *
 * 从 ReviewWorkflow 抽出（P2-1）：依赖 planningContextFactory 构造 GraphQaContext，
 * 属于"构造输入"职责，与 ReviewWorkflow 的"调度 + 事件"职责分离更清晰。
 */

/** 组装 QaCapability 调用所需的完整输入。 */
internal fun buildQaCapabilityInput(
    snapshot: WorkflowEditorSnapshot,
    modeContext: QaModeContext,
    settings: LinkGraphSettingsState,
    planningContextFactory: PlanningContextFactory,
    onPreview: ((String, Boolean) -> Unit)? = null,
): QaCapabilityInput {
    val request = modeContext.request
    val qaGraphs = planningContextFactory.buildQaGraphs(
        snapshot = snapshot,
        selectedNodeIds = modeContext.selectedNodeIds,
    )
    return QaCapabilityInput(
        question = modeContext.question,
        qaContext = GraphQaContext(
            factGraph = qaGraphs.factGraph,
            editableGraph = qaGraphs.editableGraph,
            selectedNodeIds = modeContext.selectedNodeIds,
            sourceContext = qaGraphs.sourceContext,
            evidenceTrace = qaGraphs.evidenceTrace,
            invocationExpansionContext = qaGraphs.invocationExpansionContext,
        ),
        settings = settings,
        session = request.baseSession ?: snapshot.qaResult?.qaSession,
        sourceThreadId = modeContext.sourceThreadId,
        requestedMode = modeContext.requestedMode,
        effectiveMode = modeContext.effectiveMode,
        onPreview = onPreview,
    )
}
