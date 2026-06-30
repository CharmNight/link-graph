package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.application.model.RiskResolutionSnapshot
import com.charmnight.linkgraph.agent.model.GraphPatchResult

/**
 * 风险处置服务：负责把风险线程的处置状态写回 patch 结果，并依据草稿与风险线程的状态
 * 判定草稿层是否已通过验证、代码阶段是否可以继续生成。
 */
class RiskResolutionService {
    /**
     * 把指定风险线程的处置结论写回 [result]，并在顶层 threads 与 QA 会话 threads 中同步更新。
     * 当传入的 result 为 null 时直接返回 null，表示当前没有可更新的 patch 结果。
     */
    fun applyResolution(
        result: GraphPatchResult?,
        threadId: String,
        status: RiskResolutionStatus,
        note: String = "",
    ): GraphPatchResult? {
        result ?: return null
        val resolution = RiskResolution(
            threadId = threadId,
            status = status,
            note = note,
        )
        val updateThread: (InvestigationThread) -> InvestigationThread = { thread ->
            if (thread.threadId == threadId) {
                thread.copy(resolution = resolution)
            } else {
                thread
            }
        }
        return result.copy(
            investigationThreads = result.investigationThreads.map(updateThread),
            qaSession = result.qaSession?.copy(
                investigationThreads = result.qaSession.investigationThreads.map(updateThread),
            ),
        )
    }

    /**
     * 评估当前草稿层是否已通过验证：
     * - 草稿为空时返回 EMPTY，提示先确认草稿变更；
     * - 存在未处理/暂挂/证据已穷尽的风险线程时返回 REVIEW_REQUIRED；
     * - 否则返回 READY，可以继续生成实现建议或代码 diff。
     */
    fun evaluateDraftValidation(
        snapshot: RiskResolutionSnapshot,
    ): DraftValidationState {
        val threads = resolveThreads(snapshot)
        val unresolvedThreads = threads.filter(::isDraftValidationBlocking)
        if (snapshot.draftWorkbenchState.draftChanges.isEmpty()) {
            return DraftValidationState(
                status = DraftValidationStatus.EMPTY,
                message = "当前还没有确认草稿变更，请先在草稿层确认修改目标。",
                detailMessage = if (unresolvedThreads.isNotEmpty()) {
                    "当前同时存在待验证风险。你可以先继续取证，或先确认哪些候选变更应进入草稿层。"
                } else {
                    "草稿层用于承载已经确认的业务意图；实现建议会基于当前草稿快照生成。"
                },
                unresolvedThreadIds = unresolvedThreads.map(InvestigationThread::threadId),
                unresolvedThreads = unresolvedThreads,
            )
        }
        if (unresolvedThreads.isNotEmpty()) {
            return DraftValidationState(
                status = DraftValidationStatus.REVIEW_REQUIRED,
                message = "当前草稿仍有待验证风险。",
                detailMessage = "请先确认这些风险是继续取证、接受、排除，还是回退对应草稿变更。",
                unresolvedThreadIds = unresolvedThreads.map(InvestigationThread::threadId),
                unresolvedThreads = unresolvedThreads,
            )
        }
        return DraftValidationState(
            status = DraftValidationStatus.READY,
            message = "当前草稿已完成验证，可以继续生成实现建议或代码 diff。",
        )
    }

    /**
     * 评估是否可以进入代码生成阶段：
     * - 草稿为空时不允许进入；
     * - 存在仍会阻塞代码阶段的风险线程（未处理/暂挂/证据已穷尽）时不允许进入；
     * - 否则允许继续生成代码草稿。
     */
    fun evaluateCodeEligibility(
        snapshot: RiskResolutionSnapshot,
    ): StageEligibilityDecision {
        val threads = resolveThreads(snapshot)
        if (snapshot.draftWorkbenchState.draftChanges.isEmpty()) {
            return StageEligibilityDecision(
                target = StageEligibilityTarget.CODE,
                allowed = false,
                message = "生成代码草稿前请先确认至少一条草稿变更。",
                detailMessage = "当前草稿层为空。先在问答结果中确认候选变更，使草稿层承载已确认的修改目标，再继续生成。",
                unresolvedThreadIds = unresolvedThreadIds(threads),
            )
        }
        val blockingThreads = threads.filter(::isCodeBlocking)
        if (blockingThreads.isNotEmpty()) {
            val hasDeferred = blockingThreads.any { it.resolution?.status == RiskResolutionStatus.DEFERRED }
            val detail = if (hasDeferred) {
                "当前仍存在暂挂风险，代码阶段不能越过这些风险直接继续生成。"
            } else {
                "当前仍有未处理或证据未穷尽的风险线程，代码阶段不能直接继续生成。"
            }
            return StageEligibilityDecision(
                target = StageEligibilityTarget.CODE,
                allowed = false,
                message = "生成代码草稿前请先处理仍会阻塞代码阶段的风险线程。",
                detailMessage = detail,
                blockingThreadIds = blockingThreads.map(InvestigationThread::threadId),
                unresolvedThreadIds = unresolvedThreadIds(threads),
            )
        }
        return StageEligibilityDecision(
            target = StageEligibilityTarget.CODE,
            allowed = true,
            message = "当前可以继续生成代码草稿。",
            unresolvedThreadIds = unresolvedThreadIds(threads),
        )
    }

    /** 从快照中解析需要评估的风险线程：优先取 QA 会话内的 threads，否则回退到结果顶层的 threads。 */
    private fun resolveThreads(snapshot: RiskResolutionSnapshot): List<InvestigationThread> {
        val result = snapshot.qaResult ?: return emptyList()
        return result.qaSession?.investigationThreads
            ?.takeIf(List<InvestigationThread>::isNotEmpty)
            ?: result.investigationThreads
    }

    /** 收集仍处于未处理、暂挂或证据已穷尽状态的风险线程 ID。 */
    private fun unresolvedThreadIds(threads: List<InvestigationThread>): List<String> {
        return threads.filter { thread ->
            val status = thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED
            status == RiskResolutionStatus.UNRESOLVED || status == RiskResolutionStatus.DEFERRED || status == RiskResolutionStatus.EVIDENCE_EXHAUSTED
        }.map(InvestigationThread::threadId)
    }

    /** 判断当前线程状态是否会阻塞草稿验证：未处理、暂挂、证据已穷尽均视为阻塞。 */
    private fun isDraftValidationBlocking(thread: InvestigationThread): Boolean {
        return when (thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED) {
            RiskResolutionStatus.UNRESOLVED,
            RiskResolutionStatus.DEFERRED,
            RiskResolutionStatus.EVIDENCE_EXHAUSTED,
            -> true
            else -> false
        }
    }

    /** 判断当前线程状态是否会阻塞代码生成：未处理、暂挂、证据已穷尽视为阻塞；已接受、已排除、已提升不阻塞。 */
    private fun isCodeBlocking(thread: InvestigationThread): Boolean {
        return when (thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED) {
            RiskResolutionStatus.UNRESOLVED,
            RiskResolutionStatus.DEFERRED,
            RiskResolutionStatus.EVIDENCE_EXHAUSTED,
            -> true
            RiskResolutionStatus.ACCEPTED_RISK,
            RiskResolutionStatus.DISMISSED,
            RiskResolutionStatus.PROMOTED,
            -> false
        }
    }
}
