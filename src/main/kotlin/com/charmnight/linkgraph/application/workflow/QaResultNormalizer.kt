package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationService
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaModelTurn
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaModeContext

/**
 * 负责把问答结果按当前 QA 模式归一化，并合并到问答会话。
 *
 * 这里保留两次模式边界：
 * 1. 入站边界：防止当前模式不允许的新输出进入会话合并。
 * 2. 出站边界：防止历史会话数据在合并后重新泄漏到当前结果。
 */
internal class QaResultNormalizer(
    /** 问答会话合并服务。 */
    private val qaConversationService: QaConversationService = QaConversationService(),
    /** 问答警告过滤策略。 */
    private val qaWarningPolicy: QaWarningPolicy = QaWarningPolicy(),
) {
    /**
     * 将一轮 QA 输出写入模式字段、按模式裁剪，并在需要时合并到会话。
     */
    fun normalize(
        result: GraphPatchResult,
        modeContext: QaModeContext,
    ): GraphPatchResult {
        if (result.qaSession != null) {
            // 已归一化的结果自带会话时，只做出站模式边界，避免重复合并用户轮次。
            return applyModeBoundary(result.withMode(modeContext), modeContext)
        }

        // 入站边界：先裁掉当前模式不允许的新输出，防止它们进入会话合并。
        val modeBoundResult = applyModeBoundary(
            result = result.withMode(modeContext),
            modeContext = modeContext,
            filterWarnings = false,
        )
        val baseSession = ensureQuestionCaptured(
            session = modeContext.baseSession ?: QaConversationSession(
                sessionId = "qa-${modeContext.request.requestId}",
                scopeKey = modeContext.selectedNodeIds.sorted().joinToString(",").ifBlank { "graph" },
            ),
            question = modeContext.question,
        )
        val turnResult = qaConversationService.applyModelTurn(
            session = baseSession,
            modelTurn = QaModelTurn(
                answer = modeBoundResult.answer,
                candidateChanges = modeBoundResult.candidateChanges,
                investigationThreads = modeBoundResult.investigationThreads,
                sourceThreadId = modeContext.sourceThreadId,
                observedNodeIds = observedNodeIds(modeBoundResult),
                observedFilePaths = observedFilePaths(modeBoundResult),
                blockedReason = blockedReason(modeBoundResult, modeContext),
            ),
        )
        val normalized = modeBoundResult.copy(
            candidateChanges = turnResult.session.candidateChanges,
            newCandidateChanges = modeBoundResult.newCandidateChanges.ifEmpty { turnResult.newCandidateChanges },
            investigationThreads = modeBoundResult.investigationThreads.ifEmpty { turnResult.session.investigationThreads },
            latestTurnOutcome = turnResult.latestTurnOutcome ?: modeBoundResult.latestTurnOutcome,
            recentTurnOutcomes = modeBoundResult.recentTurnOutcomes.ifEmpty { turnResult.recentTurnOutcomes },
            qaSession = turnResult.session,
        )
        // 出站边界：会话合并可能带回历史候选或线程，最终写回前必须再次按模式裁剪。
        return applyModeBoundary(normalized, modeContext)
    }

    /**
     * 根据当前实际模式裁剪结果边界。
     */
    private fun applyModeBoundary(
        result: GraphPatchResult,
        modeContext: QaModeContext,
        filterWarnings: Boolean = true,
    ): GraphPatchResult {
        return when (modeContext.effectiveMode) {
            QaMode.ANSWER -> result.withMode(modeContext).copy(
                patch = null,
                candidateChanges = emptyList(),
                newCandidateChanges = emptyList(),
                investigationThreads = emptyList(),
                latestTurnOutcome = null,
                recentTurnOutcomes = emptyList(),
                warnings = if (filterWarnings) {
                    qaWarningPolicy.filterForMode(result.warnings, modeContext.effectiveMode)
                } else {
                    result.warnings
                },
                qaSession = result.qaSession?.copy(
                    candidateChanges = emptyList(),
                    investigationThreads = emptyList(),
                    turnOutcomes = emptyList(),
                    focusTargetId = null,
                ),
            )
            QaMode.REVIEW -> result.withMode(modeContext).copy(
                patch = null,
                candidateChanges = emptyList(),
                newCandidateChanges = emptyList(),
                qaSession = result.qaSession?.copy(candidateChanges = emptyList()),
            )
            QaMode.INVESTIGATE -> result.withMode(modeContext).copy(
                patch = null,
                candidateChanges = emptyList(),
                newCandidateChanges = emptyList(),
                investigationThreads = result.investigationThreads
                    .filter { thread -> modeContext.matchesSourceThread(thread.threadId) },
                latestTurnOutcome = result.latestTurnOutcome
                    ?.takeIf { outcome -> modeContext.matchesSourceThread(outcome.threadId) },
                recentTurnOutcomes = result.recentTurnOutcomes
                    .filter { outcome -> modeContext.matchesSourceThread(outcome.threadId) },
                qaSession = result.qaSession?.copy(
                    candidateChanges = emptyList(),
                    focusTargetId = modeContext.sourceThreadId,
                ),
            )
            QaMode.CHANGE,
            QaMode.AUTO,
            -> result.withMode(modeContext)
        }
    }

    /**
     * 从模式上下文统一写入结果模式字段。
     */
    private fun GraphPatchResult.withMode(modeContext: QaModeContext): GraphPatchResult {
        return copy(
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
        )
    }

    /**
     * 确保会话里有本轮用户问题，避免重试时重复插入同一用户轮次。
     */
    private fun ensureQuestionCaptured(
        session: QaConversationSession,
        question: String,
    ): QaConversationSession {
        val lastMessage = session.messages.lastOrNull()
        if (lastMessage?.role == QaMessageRole.USER && lastMessage.content == question) {
            return session
        }
        return session.copy(
            messages = session.messages + QaConversationMessage(
                messageId = "${session.sessionId}-user-${session.messages.size + 1}",
                role = QaMessageRole.USER,
                content = question,
                focusTargetId = session.focusTargetId,
            ),
        )
    }

    /**
     * 从结构化结果中提取本轮真实观察到的图节点。
     */
    private fun observedNodeIds(result: GraphPatchResult): List<String> {
        return (
            result.findings.flatMap { finding ->
                finding.references.mapNotNull(ResultEvidenceReference::nodeId)
            } +
                result.investigationThreads.flatMap(InvestigationThread::targetNodeIds) +
                result.candidateChanges.flatMap(CandidateDraftChange::targetNodeIds)
            ).distinct()
    }

    /**
     * 从结构化结果中提取本轮真实观察到的源码文件。
     */
    private fun observedFilePaths(result: GraphPatchResult): List<String> {
        return (
            result.findings.flatMap { finding ->
                finding.references.mapNotNull(ResultEvidenceReference::filePath)
            } +
                result.investigationThreads.flatMap { thread ->
                    thread.evidence.flatMap { finding ->
                        finding.references.mapNotNull(ResultEvidenceReference::filePath)
                    }
                } +
                result.candidateChanges.flatMap { change ->
                    change.evidence.flatMap { finding ->
                        finding.references.mapNotNull(ResultEvidenceReference::filePath)
                    }
                }
            ).distinct()
    }

    /**
     * 继续取证被证据闸门挡住时，把阻塞原因传给会话服务。
     */
    private fun blockedReason(
        result: GraphPatchResult,
        modeContext: QaModeContext,
    ): String? {
        if (modeContext.effectiveMode != QaMode.INVESTIGATE) {
            return null
        }
        val sourceThreadId = modeContext.sourceThreadId ?: return null
        return result.investigationThreads
            .firstOrNull { thread ->
                thread.threadId == sourceThreadId && thread.status == InvestigationThreadStatus.BLOCKED
            }
            ?.evidenceGap
            ?.takeIf(String::isNotBlank)
    }
}
