package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel

class AuditConversationService {
    private val recentOutcomeLimit: Int = 6

    fun applyModelTurn(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): AuditConversationTurnResult {
        val existingCandidateById = session.candidateChanges.associateBy(CandidateDraftChange::changeId)
        val newCandidateChanges = modelTurn.candidateChanges.filter { change -> change.changeId !in existingCandidateById }
        val mergedCandidateChanges = linkedMapOf<String, CandidateDraftChange>()
        session.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        modelTurn.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }

        val mergedThreads = normalizeExistingThreads(session)
        val newInvestigationThreadIds = mutableListOf<String>()
        modelTurn.investigationThreads.forEach { thread ->
            val existingThread = mergedThreads[thread.threadId]
            if (existingThread != null) {
                mergedThreads[thread.threadId] = mergeInvestigationThread(existingThread, thread, modelTurn)
                return@forEach
            }
            val mergeTargetId = resolveThreadMergeTarget(session, modelTurn, thread, mergedThreads)
            if (mergeTargetId == null) {
                mergedThreads[thread.threadId] = mergeInvestigationThread(null, thread, modelTurn)
                newInvestigationThreadIds += thread.threadId
                return@forEach
            }
            val mergeTarget = mergedThreads[mergeTargetId] ?: return@forEach
            mergedThreads[mergeTargetId] = mergeInvestigationThread(mergeTarget, thread, modelTurn)
        }

        val promotedThreadIds = mergedThreads.values
            .filter { thread -> thread.status == InvestigationThreadStatus.OPEN }
            .filter { thread -> overlapsWithAnyCandidate(thread, mergedCandidateChanges.values) }
            .map(InvestigationThread::threadId)
            .toSet()
        if (promotedThreadIds.isNotEmpty()) {
            promotedThreadIds.forEach { threadId ->
                val thread = mergedThreads[threadId] ?: return@forEach
                mergedThreads[threadId] = thread.copy(status = InvestigationThreadStatus.PROMOTED)
            }
        }

        val latestTurnOutcome = buildLatestTurnOutcome(
            session = session,
            modelTurn = modelTurn,
            mergedThreads = mergedThreads,
            mergedCandidateChanges = mergedCandidateChanges.values.toList(),
            newInvestigationThreadIds = newInvestigationThreadIds,
            promotedThreadIds = promotedThreadIds,
        )
        if (latestTurnOutcome != null) {
            val thread = mergedThreads[latestTurnOutcome.threadId]
            if (thread != null) {
                mergedThreads[latestTurnOutcome.threadId] = thread.copy(
                    latestTurnOutcomeId = latestTurnOutcome.outcomeId,
                    status = when (latestTurnOutcome.status) {
                        InvestigationTurnOutcomeStatus.PROMOTED_TO_CANDIDATE -> InvestigationThreadStatus.PROMOTED
                        InvestigationTurnOutcomeStatus.DISMISSED -> InvestigationThreadStatus.DISMISSED
                        InvestigationTurnOutcomeStatus.BLOCKED -> InvestigationThreadStatus.BLOCKED
                        InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS,
                        InvestigationTurnOutcomeStatus.OPEN_NO_PROGRESS,
                        -> thread.status
                    },
                )
            }
        }

        val newInvestigationThreads = newInvestigationThreadIds.mapNotNull { threadId ->
            mergedThreads[threadId]
        }

        val focusTargetId = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: latestTurnOutcome?.threadId
            ?: newInvestigationThreads.firstOrNull()?.threadId
            ?: resolveFocusedThreadId(session, modelTurn, mergedThreads)
            ?: session.focusTargetId
        val nextMessage = AuditConversationMessage(
            messageId = buildMessageId(session, modelTurn),
            role = AuditMessageRole.ASSISTANT,
            content = modelTurn.answer,
            focusTargetId = focusTargetId,
            turnOutcomeId = latestTurnOutcome?.outcomeId,
        )
        val nextTurnOutcomes = (session.turnOutcomes + listOfNotNull(latestTurnOutcome)).takeLast(50)
        return AuditConversationTurnResult(
            session = session.copy(
                messages = session.messages + nextMessage,
                candidateChanges = mergedCandidateChanges.values.toList(),
                investigationThreads = mergedThreads.values.toList(),
                turnOutcomes = nextTurnOutcomes,
                focusTargetId = nextMessage.focusTargetId,
            ),
            newCandidateChanges = newCandidateChanges,
            newInvestigationThreads = newInvestigationThreads,
            latestTurnOutcome = latestTurnOutcome,
            recentTurnOutcomes = nextTurnOutcomes.takeLast(recentOutcomeLimit),
            draftWrites = emptyList(),
        )
    }

    private fun normalizeExistingThreads(
        session: AuditConversationSession,
    ): LinkedHashMap<String, InvestigationThread> {
        val threads = linkedMapOf<String, InvestigationThread>()
        session.investigationThreads.forEach { thread ->
            threads[thread.threadId] = thread
        }
        return threads
    }

    private fun mergeInvestigationThread(
        existing: InvestigationThread?,
        incoming: InvestigationThread,
        modelTurn: AuditModelTurn,
    ): InvestigationThread {
        return InvestigationThread(
            threadId = existing?.threadId ?: incoming.threadId,
            status = normalizedThreadStatus(existing, incoming, modelTurn),
            title = incoming.title.ifBlank { existing?.title.orEmpty() },
            targetStepIds = (existing?.targetStepIds.orEmpty() + incoming.targetStepIds).distinct(),
            targetNodeIds = (existing?.targetNodeIds.orEmpty() + incoming.targetNodeIds).distinct(),
            summary = incoming.summary.ifBlank { existing?.summary.orEmpty() },
            evidenceGap = incoming.evidenceGap.ifBlank { existing?.evidenceGap.orEmpty() },
            recommendedQuestion = incoming.recommendedQuestion.ifBlank { existing?.recommendedQuestion.orEmpty() },
            claimType = incoming.claimType ?: existing?.claimType,
            evidence = (existing?.evidence.orEmpty() + incoming.evidence).distinctBy(ResultEvidenceFinding::id),
            latestTurnOutcomeId = existing?.latestTurnOutcomeId,
            resolution = incoming.resolution ?: existing?.resolution,
        )
    }

    private fun normalizedThreadStatus(
        existing: InvestigationThread?,
        incoming: InvestigationThread,
        modelTurn: AuditModelTurn,
    ): InvestigationThreadStatus {
        return when {
            modelTurn.blockedReason != null &&
                modelTurn.sourceThreadId == (existing?.threadId ?: incoming.threadId) ->
                InvestigationThreadStatus.BLOCKED
            incoming.status != InvestigationThreadStatus.OPEN -> incoming.status
            existing?.status == InvestigationThreadStatus.BLOCKED -> InvestigationThreadStatus.OPEN
            else -> InvestigationThreadStatus.OPEN
        }
    }

    private fun buildLatestTurnOutcome(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        mergedThreads: Map<String, InvestigationThread>,
        mergedCandidateChanges: List<CandidateDraftChange>,
        newInvestigationThreadIds: List<String>,
        promotedThreadIds: Set<String>,
    ): InvestigationTurnOutcome? {
        val focusThreadId = resolveOutcomeThreadId(
            session = session,
            modelTurn = modelTurn,
            mergedThreads = mergedThreads,
            mergedCandidateChanges = mergedCandidateChanges,
            newInvestigationThreadIds = newInvestigationThreadIds,
            promotedThreadIds = promotedThreadIds,
        ) ?: return null
        val currentThread = mergedThreads[focusThreadId] ?: return null
        val previousThread = normalizeExistingThreads(session)[focusThreadId]
        val previousOutcome = session.turnOutcomes.lastOrNull { outcome -> outcome.threadId == focusThreadId }
        val candidateThreadId = mergedCandidateChanges.firstNotNullOfOrNull { change ->
            resolveCandidateThreadId(change, mergedThreads)
        }
        val currentStrongestEvidenceLevel = strongestEvidenceLevel(currentThread.evidence)
        val previousStrongestEvidenceLevel = previousOutcome?.strongestEvidenceLevel
            ?: strongestEvidenceLevel(previousThread?.evidence.orEmpty())
        val observedNodeIds = (
            currentThread.targetNodeIds +
                modelTurn.observedNodeIds +
                currentThread.evidence.flatMap { finding ->
                    finding.references.mapNotNull { reference -> reference.nodeId }
                }
            ).distinct()
        val observedFilePaths = (
            modelTurn.observedFilePaths +
                currentThread.evidence.flatMap { finding ->
                    finding.references.mapNotNull { reference -> reference.filePath }
                }
            ).distinct()
        val previousObservedNodeIds = previousOutcome?.observedNodeIds.orEmpty()
        val previousObservedFilePaths = previousOutcome?.observedFilePaths.orEmpty()
        val evidenceDelta = InvestigationEvidenceDelta(
            addedNodeIds = observedNodeIds.filterNot(previousObservedNodeIds::contains),
            addedFilePaths = observedFilePaths.filterNot(previousObservedFilePaths::contains),
            previousStrongestEvidenceLevel = previousStrongestEvidenceLevel,
            currentStrongestEvidenceLevel = currentStrongestEvidenceLevel,
            hitRecommendedQuestion = modelTurn.sourceThreadId == focusThreadId && observedNodeIds.isNotEmpty(),
        )
        val strongestEvidenceImproved = compareEvidenceLevel(
            currentStrongestEvidenceLevel,
            previousStrongestEvidenceLevel,
        ) > 0
        val textualProgress = previousThread != null && (
            currentThread.summary != previousThread.summary ||
                currentThread.evidenceGap != previousThread.evidenceGap ||
                currentThread.recommendedQuestion != previousThread.recommendedQuestion
            )
        val status = when {
            modelTurn.blockedReason != null -> InvestigationTurnOutcomeStatus.BLOCKED
            currentThread.status == InvestigationThreadStatus.DISMISSED -> InvestigationTurnOutcomeStatus.DISMISSED
            candidateThreadId == focusThreadId || focusThreadId in promotedThreadIds ->
                InvestigationTurnOutcomeStatus.PROMOTED_TO_CANDIDATE
            evidenceDelta.addedNodeIds.isNotEmpty() ||
                evidenceDelta.addedFilePaths.isNotEmpty() ||
                strongestEvidenceImproved ||
                textualProgress ||
                previousThread == null ->
                InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS
            else -> InvestigationTurnOutcomeStatus.OPEN_NO_PROGRESS
        }
        return InvestigationTurnOutcome(
            outcomeId = buildTurnOutcomeId(session, focusThreadId),
            threadId = focusThreadId,
            status = status,
            summary = currentThread.title.ifBlank { currentThread.summary },
            detail = when (status) {
                InvestigationTurnOutcomeStatus.PROMOTED_TO_CANDIDATE ->
                    "本轮已经拿到可升级为候选变更的直接证据。"
                InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS ->
                    "本轮补充了新的取证信息，但还不足以直接进入草稿。"
                InvestigationTurnOutcomeStatus.OPEN_NO_PROGRESS ->
                    "本轮仍停留在原有风险线程上，没有获得可升级的新证据。"
                InvestigationTurnOutcomeStatus.DISMISSED ->
                    "本轮结果已明确排除这条风险线程。"
                InvestigationTurnOutcomeStatus.BLOCKED ->
                    modelTurn.blockedReason ?: "本轮继续取证时遇到阻塞。"
            },
            candidateChangeId = mergedCandidateChanges.firstOrNull { change ->
                resolveCandidateThreadId(change, mergedThreads) == focusThreadId
            }?.changeId,
            blockedReason = modelTurn.blockedReason,
            evidenceDelta = evidenceDelta,
            observedNodeIds = observedNodeIds,
            observedFilePaths = observedFilePaths,
            strongestEvidenceLevel = currentStrongestEvidenceLevel,
        )
    }

    private fun resolveOutcomeThreadId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        mergedThreads: Map<String, InvestigationThread>,
        mergedCandidateChanges: List<CandidateDraftChange>,
        newInvestigationThreadIds: List<String>,
        promotedThreadIds: Set<String>,
    ): String? {
        val sourceThreadId = modelTurn.sourceThreadId?.takeIf { it in mergedThreads.keys }
        if (sourceThreadId != null) {
            return sourceThreadId
        }
        val candidateThreadId = mergedCandidateChanges.firstNotNullOfOrNull { change ->
            resolveCandidateThreadId(change, mergedThreads)
        }
        if (candidateThreadId != null) {
            return candidateThreadId
        }
        if (newInvestigationThreadIds.isNotEmpty()) {
            return newInvestigationThreadIds.first()
        }
        if (promotedThreadIds.isNotEmpty()) {
            return promotedThreadIds.first()
        }
        return resolveFocusedThreadId(session, modelTurn, mergedThreads)
    }

    private fun resolveThreadMergeTarget(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        newThread: InvestigationThread,
        mergedThreads: Map<String, InvestigationThread>,
    ): String? {
        if (newThread.status != InvestigationThreadStatus.OPEN) {
            return null
        }
        val sourceThreadId = modelTurn.sourceThreadId?.takeIf { it in mergedThreads.keys }
        if (sourceThreadId != null) {
            val sourceThread = mergedThreads[sourceThreadId] ?: return null
            if (sourceThread.status == InvestigationThreadStatus.OPEN && threadsOverlap(sourceThread, newThread)) {
                return sourceThreadId
            }
        }
        if (!isFollowUpInvestigationQuestion(session)) {
            return null
        }
        val overlappingThreadIds = mergedThreads.values
            .filter { thread -> thread.status == InvestigationThreadStatus.OPEN }
            .filter { thread -> threadsOverlap(thread, newThread) }
            .map(InvestigationThread::threadId)
        if (session.focusTargetId != null && session.focusTargetId in overlappingThreadIds) {
            return session.focusTargetId
        }
        return overlappingThreadIds.singleOrNull()
    }

    private fun resolveFocusedThreadId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
        mergedThreads: Map<String, InvestigationThread>,
    ): String? {
        val sourceThreadId = modelTurn.sourceThreadId
        if (sourceThreadId != null && sourceThreadId in mergedThreads.keys) {
            return sourceThreadId
        }
        return if (isFollowUpInvestigationQuestion(session)) {
            session.focusTargetId?.takeIf { it in mergedThreads.keys }
        } else {
            null
        }
    }

    private fun buildMessageId(
        session: AuditConversationSession,
        modelTurn: AuditModelTurn,
    ): String {
        val suffix = session.messages.size + 1
        val focusPart = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: modelTurn.sourceThreadId
            ?: modelTurn.investigationThreads.firstOrNull()?.threadId
            ?: "reply"
        return "${session.sessionId}-assistant-$suffix-$focusPart"
    }

    private fun buildTurnOutcomeId(
        session: AuditConversationSession,
        threadId: String,
    ): String {
        val suffix = session.turnOutcomes.size + 1
        return "${session.sessionId}-turn-$suffix-$threadId"
    }

    private fun resolveCandidateThreadId(
        change: CandidateDraftChange,
        mergedThreads: Map<String, InvestigationThread>,
    ): String? {
        return mergedThreads.values.firstOrNull { thread ->
            change.targetNodeIds.intersect(thread.targetNodeIds.toSet()).isNotEmpty() ||
                change.targetStepIds.intersect(thread.targetStepIds.toSet()).isNotEmpty()
        }?.threadId
    }

    private fun overlapsWithAnyCandidate(
        thread: InvestigationThread,
        candidates: Collection<CandidateDraftChange>,
    ): Boolean {
        return candidates.any { change ->
            change.targetNodeIds.intersect(thread.targetNodeIds.toSet()).isNotEmpty()
                || change.targetStepIds.intersect(thread.targetStepIds.toSet()).isNotEmpty()
        }
    }

    private fun threadsOverlap(
        left: InvestigationThread,
        right: InvestigationThread,
    ): Boolean {
        return left.targetNodeIds.intersect(right.targetNodeIds.toSet()).isNotEmpty()
            || left.targetStepIds.intersect(right.targetStepIds.toSet()).isNotEmpty()
    }

    private fun strongestEvidenceLevel(
        evidence: List<ResultEvidenceFinding>,
    ): ResultEvidenceLevel? {
        return evidence.maxByOrNull { finding -> evidenceRank(finding.evidenceLevel) }?.evidenceLevel
    }

    private fun compareEvidenceLevel(
        left: ResultEvidenceLevel?,
        right: ResultEvidenceLevel?,
    ): Int {
        return evidenceRank(left) - evidenceRank(right)
    }

    private fun evidenceRank(level: ResultEvidenceLevel?): Int {
        return when (level) {
            ResultEvidenceLevel.NOT_OBSERVED -> 0
            ResultEvidenceLevel.CALLSITE_ONLY -> 1
            ResultEvidenceLevel.DIRECT_GRAPH -> 2
            ResultEvidenceLevel.DIRECT_SOURCE -> 3
            null -> -1
        }
    }

    private fun isFollowUpInvestigationQuestion(session: AuditConversationSession): Boolean {
        val lastUserMessage = session.messages.lastOrNull { message -> message.role == AuditMessageRole.USER } ?: return false
        return lastUserMessage.content.contains("继续取证")
    }
}
