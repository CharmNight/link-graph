package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.agent.model.GraphPatchResult
import java.util.UUID

/**
 * QA 请求生命周期服务。
 *
 * 把"构造可重放请求"和"标记请求状态"两个操作集中起来，
 * 让上层（重试、撤销等流程）不需要直接操作 [QaRequestRecoveryState] 的字段。
 * 状态变化统一通过本服务，便于将来加埋点或扩展。
 */
class QaRequestLifecycleService {
    /**
     * 构造一个可重放的 QA 请求。
     *
     * @param qaResult 最近一次 QA 结果；提供会话基线
     * @param question 本轮问题
     * @param selectedNodeIds 选中节点列表
     * @param sourceThreadId 来源线程 ID；非空时表示是"继续取证"
     * @param mode QA 模式；默认 AUTO
     * @return 可重放请求对象
     */
    fun buildReplayableRequest(
        qaResult: GraphPatchResult?,
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode = QaMode.AUTO,
    ): ReplayableQaRequest {
        return ReplayableQaRequest(
            // UUID 保证每次请求都有唯一 ID，便于跨服务追踪
            requestId = UUID.randomUUID().toString(),
            // 有来源线程时归类为 INVESTIGATE_THREAD，否则为 ASK
            kind = if (sourceThreadId.isNullOrBlank()) QaRequestKind.ASK else QaRequestKind.INVESTIGATE_THREAD,
            question = question,
            mode = mode,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            // 把上一次 QA 的会话作为基线，支持多轮对话
            baseSession = qaResult?.qaSession,
        )
    }

    /** 标记请求已提交：清除失败记录，保留为最近一次提交。 */
    fun markSubmitted(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = null,
        )
    }

    /** 标记请求失败：保留为最近一次提交与最近一次失败，便于重试。 */
    fun markFailed(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = request,
        )
    }

    /** 标记请求成功：清除失败记录。 */
    fun markSucceeded(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = null,
        )
    }
}
