package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.agent.model.ResultEvidenceLevel

/**
 * 问答会话状态机核心：负责把模型一轮回答并入既有会话，
 * 维护候选变更、调查线索、轮次结果与焦点目标的演化关系。
 */
class QaConversationService {
    /** 返回结果中保留的最近轮次结果数量，用于 UI 上的滚动展示。 */
    private val recentOutcomeLimit: Int = 6

    /**
     * 把模型一轮回答应用到当前会话上，得到更新后的会话与对外暴露的本轮摘要。
     *
     * 处理顺序：先合并候选变更，再处理调查线索（新建 / 合并 / 升级），随后生成
     * 本轮的轮次结果与下一条 assistant 消息，并刷新焦点目标。
     */
    fun applyModelTurn(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
    ): QaConversationTurnResult {
        // 旧候选按 ID 索引，用于区分本轮新增与已存在的候选
        val existingCandidateById = session.candidateChanges.associateBy(CandidateDraftChange::changeId)
        // 本轮首次出现的候选，单独标记为“新增”返回给上层
        val newCandidateChanges = modelTurn.candidateChanges.filter { change -> change.changeId !in existingCandidateById }
        // 旧候选在前，新候选在后，按 ID 去重保留最新版本
        val mergedCandidateChanges = linkedMapOf<String, CandidateDraftChange>()
        session.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }
        modelTurn.candidateChanges.forEach { change ->
            mergedCandidateChanges[change.changeId] = change
        }

        // 将现有调查线索放入有序 map，便于按 ID 命中并保持插入顺序
        val mergedThreads = normalizeExistingThreads(session)
        // 记录本轮新创建的线索 ID，便于在上层做“新增线索”高亮
        val newInvestigationThreadIds = mutableListOf<String>()
        modelTurn.investigationThreads.forEach { thread ->
            val existingThread = mergedThreads[thread.threadId]
            if (existingThread != null) {
                // 同 ID 直接覆盖合并
                mergedThreads[thread.threadId] = mergeInvestigationThread(existingThread, thread, modelTurn)
                return@forEach
            }
            // 没有 ID 命中时，尝试按节点/步骤重叠寻找可合并的旧线索
            val mergeTargetId = resolveThreadMergeTarget(session, modelTurn, thread, mergedThreads)
            if (mergeTargetId == null) {
                // 完全没有重叠的旧线索：作为新线索登记
                mergedThreads[thread.threadId] = mergeInvestigationThread(null, thread, modelTurn)
                newInvestigationThreadIds += thread.threadId
                return@forEach
            }
            val mergeTarget = mergedThreads[mergeTargetId] ?: return@forEach
            // 命中可合并的旧线索，但保留旧 ID 避免引用断裂
            mergedThreads[mergeTargetId] = mergeInvestigationThread(mergeTarget, thread, modelTurn)
        }

        // 仍处于打开状态、且与候选变更存在节点/步骤交集的线索，整体升级为 PROMOTED
        val promotedThreadIds = mergedThreads.values
            .filter { thread -> thread.status == InvestigationThreadStatus.OPEN }
            .filter { thread -> overlapsWithAnyCandidate(thread, mergedCandidateChanges.values) }
            .map(InvestigationThread::threadId)
            .toSet()
        if (promotedThreadIds.isNotEmpty()) {
            // 把命中候选变更的线索状态整体改写为 PROMOTED
            promotedThreadIds.forEach { threadId ->
                val thread = mergedThreads[threadId] ?: return@forEach
                mergedThreads[threadId] = thread.copy(status = InvestigationThreadStatus.PROMOTED)
            }
        }

        // 生成本轮对外的轮次结果（升级/有进展/无进展/被阻塞/被排除）
        val latestTurnOutcome = buildLatestTurnOutcome(
            session = session,
            modelTurn = modelTurn,
            mergedThreads = mergedThreads,
            mergedCandidateChanges = mergedCandidateChanges.values.toList(),
            newInvestigationThreadIds = newInvestigationThreadIds,
            promotedThreadIds = promotedThreadIds,
        )
        if (latestTurnOutcome != null) {
            // 将本轮结果回写到对应线索：记录 outcomeId，并按结果状态决定线索是否同步升降级
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

        // 从有序 map 中按登记顺序还原本轮新增的线索对象，供 UI 展示
        val newInvestigationThreads = newInvestigationThreadIds.mapNotNull { threadId ->
            mergedThreads[threadId]
        }

        // 计算本轮下一条消息要聚焦的目标：优先候选变更，其次结果对应的线索，
        // 再次新增线索，最后退化到旧焦点
        val focusTargetId = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: latestTurnOutcome?.threadId
            ?: newInvestigationThreads.firstOrNull()?.threadId
            ?: resolveFocusedThreadId(session, modelTurn, mergedThreads)
            ?: session.focusTargetId
        val nextMessage = QaConversationMessage(
            messageId = buildMessageId(session, modelTurn),
            role = QaMessageRole.ASSISTANT,
            content = modelTurn.answer,
            focusTargetId = focusTargetId,
            turnOutcomeId = latestTurnOutcome?.outcomeId,
        )
        // 轮次结果只保留最近 50 条，避免会话无限增长
        val nextTurnOutcomes = (session.turnOutcomes + listOfNotNull(latestTurnOutcome)).takeLast(50)
        return QaConversationTurnResult(
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

    /**
     * 把会话中的现有调查线索装载为有序 map，方便按 ID 快速定位，
     * 同时保留线索的原始登记顺序。
     */
    private fun normalizeExistingThreads(
        session: QaConversationSession,
    ): LinkedHashMap<String, InvestigationThread> {
        val threads = linkedMapOf<String, InvestigationThread>()
        session.investigationThreads.forEach { thread ->
            threads[thread.threadId] = thread
        }
        return threads
    }

    /**
     * 把模型本轮产出的线索合并到既有线索（如存在）上，
     * 文本字段采用“新值优先、为空则回落旧值”的策略，
     * 节点/步骤/证据等集合字段做去重并集。
     */
    private fun mergeInvestigationThread(
        existing: InvestigationThread?,
        incoming: InvestigationThread,
        modelTurn: QaModelTurn,
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

    /**
     * 在线索合并时推算合并后的状态：当本轮显式标记了阻塞且来源就是该线索，
     * 则记为 BLOCKED；其余情况优先采纳新状态，旧 BLOCKED 线索若未继续阻塞则恢复 OPEN。
     */
    private fun normalizedThreadStatus(
        existing: InvestigationThread?,
        incoming: InvestigationThread,
        modelTurn: QaModelTurn,
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

    /**
     * 构造本轮对外的轮次结果：选定代表线索后，比对其上一轮状态，
     * 计算证据增量、文本变化、是否升级为候选变更，并据此确定结果状态与说明。
     */
    private fun buildLatestTurnOutcome(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
        mergedThreads: Map<String, InvestigationThread>,
        mergedCandidateChanges: List<CandidateDraftChange>,
        newInvestigationThreadIds: List<String>,
        promotedThreadIds: Set<String>,
    ): InvestigationTurnOutcome? {
        // 在多种候选条件下决定本轮结果归属哪条线索，找不到则视为无有效结果
        val focusThreadId = resolveOutcomeThreadId(
            session = session,
            modelTurn = modelTurn,
            mergedThreads = mergedThreads,
            mergedCandidateChanges = mergedCandidateChanges,
            newInvestigationThreadIds = newInvestigationThreadIds,
            promotedThreadIds = promotedThreadIds,
        ) ?: return null
        val currentThread = mergedThreads[focusThreadId] ?: return null
        // 上一轮的对应线索对象，用于判定“是否真有进展”
        val previousThread = normalizeExistingThreads(session)[focusThreadId]
        // 上一轮对应线索最近一次的轮次结果，用于取出可比较的观测节点与文件
        val previousOutcome = session.turnOutcomes.lastOrNull { outcome -> outcome.threadId == focusThreadId }
        // 找出本轮候选变更所命中的线索 ID，用于判断是否进入升级分支
        val candidateThreadId = mergedCandidateChanges.firstNotNullOfOrNull { change ->
            resolveCandidateThreadId(change, mergedThreads)
        }
        val currentStrongestEvidenceLevel = strongestEvidenceLevel(currentThread.evidence)
        val previousStrongestEvidenceLevel = previousOutcome?.strongestEvidenceLevel
            ?: strongestEvidenceLevel(previousThread?.evidence.orEmpty())
        // 合并线索自身目标、模型本轮显式观测、证据引用三方来源的节点 ID
        val observedNodeIds = (
            currentThread.targetNodeIds +
                modelTurn.observedNodeIds +
                currentThread.evidence.flatMap { finding ->
                    finding.references.mapNotNull { reference -> reference.nodeId }
                }
            ).distinct()
        // 文件路径只汇总模型观测与证据引用，线索本身不携带文件
        val observedFilePaths = (
            modelTurn.observedFilePaths +
                currentThread.evidence.flatMap { finding ->
                    finding.references.mapNotNull { reference -> reference.filePath }
                }
            ).distinct()
        val previousObservedNodeIds = previousOutcome?.observedNodeIds.orEmpty()
        val previousObservedFilePaths = previousOutcome?.observedFilePaths.orEmpty()
        // 计算本轮相对上一轮的证据增量，作为状态判定的重要输入
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
        // 即便证据等级没提升，只要摘要/缺口/推荐问题更新了，也算本轮有进展
        val textualProgress = previousThread != null && (
            currentThread.summary != previousThread.summary ||
                currentThread.evidenceGap != previousThread.evidenceGap ||
                currentThread.recommendedQuestion != previousThread.recommendedQuestion
            )
        // 综合阻塞、是否被升级为候选、证据/文本进展，给出对外状态
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

    /**
     * 决定本轮结果归属的调查线索 ID：依次按显式来源线索、候选变更命中的线索、
     * 本轮新增线索、本轮升级线索，最后退化到当前焦点线索。
     */
    private fun resolveOutcomeThreadId(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
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

    /**
     * 在没有 ID 命中的情况下，尝试为模型本轮新产出的线索寻找可合并的旧线索 ID。
     * 仅当线索仍处于 OPEN 且节点/步骤存在重叠时才会匹配，且优先选用当前焦点线索。
     */
    private fun resolveThreadMergeTarget(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
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

    /**
     * 推断本轮焦点所在的线索 ID：若模型本轮显式声明了来源线索则优先采用；
     * 否则当用户在追问取证时维持原来的焦点线索，避免上下文被打散。
     */
    private fun resolveFocusedThreadId(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
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

    /**
     * 构造本轮 assistant 消息的全局唯一 ID：拼接会话 ID、序号以及焦点目标，
     * 便于前端做幂等去重与定位。
     */
    private fun buildMessageId(
        session: QaConversationSession,
        modelTurn: QaModelTurn,
    ): String {
        val suffix = session.messages.size + 1
        val focusPart = modelTurn.candidateChanges.firstOrNull()?.changeId
            ?: modelTurn.sourceThreadId
            ?: modelTurn.investigationThreads.firstOrNull()?.threadId
            ?: "reply"
        return "${session.sessionId}-assistant-$suffix-$focusPart"
    }

    /** 构造本轮轮次结果的唯一 ID：由会话 ID、自增序号和归属线索 ID 拼接而成。 */
    private fun buildTurnOutcomeId(
        session: QaConversationSession,
        threadId: String,
    ): String {
        val suffix = session.turnOutcomes.size + 1
        return "${session.sessionId}-turn-$suffix-$threadId"
    }

    /** 在线索集合中找到与候选变更存在节点或步骤交集的首条线索 ID。 */
    private fun resolveCandidateThreadId(
        change: CandidateDraftChange,
        mergedThreads: Map<String, InvestigationThread>,
    ): String? {
        return mergedThreads.values.firstOrNull { thread ->
            change.targetNodeIds.intersect(thread.targetNodeIds.toSet()).isNotEmpty() ||
                change.targetStepIds.intersect(thread.targetStepIds.toSet()).isNotEmpty()
        }?.threadId
    }

    /** 判断调查线索是否与任意一个候选变更共享目标节点或步骤，从而决定是否可升级。 */
    private fun overlapsWithAnyCandidate(
        thread: InvestigationThread,
        candidates: Collection<CandidateDraftChange>,
    ): Boolean {
        return candidates.any { change ->
            change.targetNodeIds.intersect(thread.targetNodeIds.toSet()).isNotEmpty()
                || change.targetStepIds.intersect(thread.targetStepIds.toSet()).isNotEmpty()
        }
    }

    /** 判断两条调查线索是否存在节点或步骤层面的重叠，用于推断是否应合并。 */
    private fun threadsOverlap(
        left: InvestigationThread,
        right: InvestigationThread,
    ): Boolean {
        return left.targetNodeIds.intersect(right.targetNodeIds.toSet()).isNotEmpty()
            || left.targetStepIds.intersect(right.targetStepIds.toSet()).isNotEmpty()
    }

    /** 在一组证据中找出强度等级最高的一条，作为该线索的当前最强证据。 */
    private fun strongestEvidenceLevel(
        evidence: List<ResultEvidenceFinding>,
    ): ResultEvidenceLevel? {
        return evidence.maxByOrNull { finding -> evidenceRank(finding.evidenceLevel) }?.evidenceLevel
    }

    /** 比较两个证据等级的强弱，正值表示左侧更强，用于判断本轮证据是否提升。 */
    private fun compareEvidenceLevel(
        left: ResultEvidenceLevel?,
        right: ResultEvidenceLevel?,
    ): Int {
        return evidenceRank(left) - evidenceRank(right)
    }

    /** 把证据等级映射为可比的数值：未观测为 0，调用点证据为 1，图谱直证为 2，源码直证为 3，缺失视为 -1。 */
    private fun evidenceRank(level: ResultEvidenceLevel?): Int {
        return when (level) {
            ResultEvidenceLevel.NOT_OBSERVED -> 0
            ResultEvidenceLevel.CALLSITE_ONLY -> 1
            ResultEvidenceLevel.DIRECT_GRAPH -> 2
            ResultEvidenceLevel.DIRECT_SOURCE -> 3
            null -> -1
        }
    }

    /** 根据最近一条用户消息是否包含“继续取证”关键字，判断本轮是否为追问场景。 */
    private fun isFollowUpInvestigationQuestion(session: QaConversationSession): Boolean {
        val lastUserMessage = session.messages.lastOrNull { message -> message.role == QaMessageRole.USER } ?: return false
        return lastUserMessage.content.contains("继续取证")
    }
}
