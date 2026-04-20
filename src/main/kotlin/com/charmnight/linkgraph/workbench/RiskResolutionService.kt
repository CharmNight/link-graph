package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.ui.GraphEditorStateService

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
            auditSession = result.auditSession?.copy(
                investigationThreads = result.auditSession.investigationThreads.map(updateThread),
            ),
        )
    }

    fun evaluatePlanEligibility(
        snapshot: GraphEditorStateService.Snapshot,
    ): StageEligibilityDecision {
        val threads = resolveThreads(snapshot)
        val unresolvedThreads = threads.filter(::isPlanBlocking)
        if (unresolvedThreads.isNotEmpty()) {
            return StageEligibilityDecision(
                target = StageEligibilityTarget.PLAN,
                allowed = false,
                message = "生成实现计划前请先处理仍在阻塞的风险线程。",
                detailMessage = "当前仍有未决风险。你可以继续取证、暂挂风险、接受风险，或明确排除风险后再继续。",
                blockingThreadIds = unresolvedThreads.map(InvestigationThread::threadId),
                unresolvedThreadIds = unresolvedThreads.map(InvestigationThread::threadId),
            )
        }
        if (snapshot.draftWorkbenchState.draftChanges.isNotEmpty()) {
            return StageEligibilityDecision(
                target = StageEligibilityTarget.PLAN,
                allowed = true,
                message = "当前可以继续生成实现计划。",
                unresolvedThreadIds = unresolvedThreadIds(threads),
            )
        }
        if (threads.any(::isPlanOnlyContinuationResolution)) {
            return StageEligibilityDecision(
                target = StageEligibilityTarget.PLAN,
                allowed = true,
                message = "当前风险已完成人工决策，可以继续生成实现计划。",
                detailMessage = "当前还没有确认草稿变更，但可以先生成计划整理实现路径与风险摘要。",
                unresolvedThreadIds = unresolvedThreadIds(threads),
            )
        }
        return StageEligibilityDecision(
            target = StageEligibilityTarget.PLAN,
            allowed = false,
            message = "生成实现计划前请先确认至少一条草稿变更。",
            detailMessage = "当前草稿层为空。先在问答结果中确认候选变更，使草稿层承载已确认的修改目标，再继续生成。",
        )
    }

    fun evaluateCodeEligibility(
        snapshot: GraphEditorStateService.Snapshot,
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

    private fun resolveThreads(snapshot: GraphEditorStateService.Snapshot): List<InvestigationThread> {
        val result = snapshot.auditResult ?: return emptyList()
        return result.auditSession?.investigationThreads
            ?.takeIf(List<InvestigationThread>::isNotEmpty)
            ?: result.investigationThreads
    }

    private fun unresolvedThreadIds(threads: List<InvestigationThread>): List<String> {
        return threads.filter { thread ->
            val status = thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED
            status == RiskResolutionStatus.UNRESOLVED || status == RiskResolutionStatus.DEFERRED || status == RiskResolutionStatus.EVIDENCE_EXHAUSTED
        }.map(InvestigationThread::threadId)
    }

    private fun isPlanBlocking(thread: InvestigationThread): Boolean {
        return when (thread.resolution?.status ?: RiskResolutionStatus.UNRESOLVED) {
            RiskResolutionStatus.UNRESOLVED -> true
            else -> false
        }
    }

    private fun isPlanOnlyContinuationResolution(thread: InvestigationThread): Boolean {
        return when (thread.resolution?.status) {
            RiskResolutionStatus.DEFERRED,
            RiskResolutionStatus.ACCEPTED_RISK,
            RiskResolutionStatus.EVIDENCE_EXHAUSTED,
            RiskResolutionStatus.DISMISSED,
            RiskResolutionStatus.PROMOTED,
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
