package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.investigation.application.InvestigationTargetHint
import com.charmnight.linkgraph.agent.runtime.AgentRunResult
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

/**
 * ReviewWorkflow 的纯展示 / 事件发射 / 取证提示派生 helper（P2-1 深度拆分）。
 *
 * 这些函数把问答场景的"用户可读文案生成"、"事件包装"、"runtime artifact 摘要化"、
 * "investigation target hint 派生"等无状态逻辑收敛在一起，与 ReviewWorkflow 的
 * orchestrator 主流程解耦后便于复用与单独测试。
 */

/** 生成问答开始时给用户的提示文案：远程 / 本地、流式 / 完整、整图 / 选中范围。 */
internal fun buildQaStartMessage(
    remoteRequested: Boolean,
    streamingSupported: Boolean,
    modeContext: QaModeContext,
): String {
    val selectedNodeIds = modeContext.selectedNodeIds
    val modeSuffix = "实际模式：${modeContext.effectiveMode.name}。"
    if (remoteRequested) {
        return if (streamingSupported) {
            "已发起远程 LLM 问答请求，当前采用流式输出。$modeSuffix"
        } else {
            "已发起远程 LLM 问答请求，当前采用完整返回。$modeSuffix"
        }
    }
    return if (selectedNodeIds.isEmpty()) {
        "正在对整个链路执行问答，请稍候。$modeSuffix"
    } else {
        "正在对当前选中范围执行问答，请稍候。$modeSuffix"
    }
}

/** 根据风险决议状态生成对应的用户反馈文案；codeAllowed 区分是否已有确认草稿。 */
internal fun resolutionFeedbackMessage(
    status: RiskResolutionStatus,
    codeAllowed: Boolean,
): String = when (status) {
    RiskResolutionStatus.DEFERRED -> "已暂挂该风险线程。现在可以继续生成实现计划，但代码阶段仍会保持拦截。"
    RiskResolutionStatus.ACCEPTED_RISK ->
        if (codeAllowed) {
            "已接受该风险。当前已有确认草稿变更，计划与代码阶段都可以继续。"
        } else {
            "已接受该风险。当前可以继续生成实现计划；代码阶段仍需至少一条已确认草稿变更。"
        }
    RiskResolutionStatus.EVIDENCE_EXHAUSTED -> "已标记该风险线程证据穷尽。现在可以继续生成实现计划，但代码阶段仍会保持拦截。"
    RiskResolutionStatus.DISMISSED -> "已排除该风险线程，后续阶段将按剩余风险与草稿状态重新判断。"
    RiskResolutionStatus.PROMOTED -> "该风险线程已提升为可执行变更，后续阶段将按草稿确认状态继续判断。"
    RiskResolutionStatus.UNRESOLVED -> "已恢复为未决风险线程，后续阶段将重新进入阻塞判断。"
}

/** 把 AgentRunResult 的 artifactSummaries 转换为应用层摘要。 */
internal fun toRuntimeArtifactSummaries(
    result: AgentRunResult<*>,
): List<ApplicationRuntimeArtifactSummary> =
    result.artifactSummaries.map(ApplicationRuntimeArtifactSummary::from)

/**
 * 从当前可见图中提取风险线程关联节点的结构化符号提示。
 *
 * 用于继续取证流水线：把目标节点 ID 解析为带 title / signature 的提示对象，
 * 帮助 LLM/runtime 锚定到具体节点。
 */
internal fun investigationTargetHints(
    snapshot: WorkflowEditorSnapshot,
    modeContext: QaModeContext,
): List<InvestigationTargetHint> {
    val request = modeContext.request
    val sourceThreadId = requireNotNull(modeContext.sourceThreadId)
    val sourceThread = request.baseSession?.investigationThreads
        ?.firstOrNull { thread -> thread.threadId == sourceThreadId }
    val targetNodeIds = (request.selectedNodeIds + sourceThread?.targetNodeIds.orEmpty()).distinct()
    if (targetNodeIds.isEmpty()) {
        return emptyList()
    }
    val nodesById = currentVisibleGraph(snapshot).nodes.associateBy { node -> node.id }
    return targetNodeIds.mapNotNull { nodeId ->
        val node = nodesById[nodeId] ?: return@mapNotNull null
        InvestigationTargetHint(
            nodeId = node.id,
            title = node.title,
            signature = node.signature,
        )
    }
}

/** 包装并发射 QaCompleted 事件。 */
internal fun emitQaCompleted(
    eventSink: GraphEditorApplicationEventSink,
    presentation: QaCompletedResult,
) {
    eventSink.emit(GraphEditorApplicationEvent.QaCompleted(presentation))
}

/** 包装并发射 QaFailed 事件。 */
internal fun emitQaFailed(
    eventSink: GraphEditorApplicationEventSink,
    presentation: QaFailedResult,
) {
    eventSink.emit(GraphEditorApplicationEvent.QaFailed(presentation))
}

/** 包装并发射 ReviewRequestStarted 事件。 */
internal fun emitReviewRequestStarted(
    eventSink: GraphEditorApplicationEventSink,
    presentation: ReviewRequestStartedResult,
) {
    eventSink.emit(GraphEditorApplicationEvent.ReviewRequestStarted(presentation))
}

/** 包装并发射 ReviewStreamingPreview 事件。 */
internal fun emitReviewStreamingPreview(
    eventSink: GraphEditorApplicationEventSink,
    scene: ReviewRequestScene,
    requestId: Long,
    previewText: String,
    finalizingStructuredResult: Boolean,
) {
    eventSink.emit(
        GraphEditorApplicationEvent.ReviewStreamingPreview(
            scene = scene,
            requestId = requestId,
            previewText = previewText,
            finalizingStructuredResult = finalizingStructuredResult,
        ),
    )
}
