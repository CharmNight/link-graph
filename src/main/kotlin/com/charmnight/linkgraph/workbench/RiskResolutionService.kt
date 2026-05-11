package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.application.model.RiskResolutionSnapshot
import com.charmnight.linkgraph.llm.GraphPatchResult

class RiskResolutionService {
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

    private fun resolveThreads(snapshot: RiskResolutionSnapshot): List<InvestigationThread> {
        val result = snapshot.qaResult ?: return emptyList()
        return result.qaSession?.investigationThreads
            ?.takeIf(List<InvestigationThread>::isNotEmpty)
            ?: result.investigationThreads
    }

    private fun unresolvedThreadIds(threads: List<InvestigationThread>): List<String> {
        return threads.filter { thread ->
            val status = thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED
            status == RiskResolutionStatus.UNRESOLVED || status == RiskResolutionStatus.DEFERRED || status == RiskResolutionStatus.EVIDENCE_EXHAUSTED
        }.map(InvestigationThread::threadId)
    }

    private fun isDraftValidationBlocking(thread: InvestigationThread): Boolean {
        return when (thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED) {
            RiskResolutionStatus.UNRESOLVED,
            RiskResolutionStatus.DEFERRED,
            RiskResolutionStatus.EVIDENCE_EXHAUSTED,
            -> true
            else -> false
        }
    }

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
