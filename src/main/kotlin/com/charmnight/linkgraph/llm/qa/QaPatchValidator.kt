package com.charmnight.linkgraph.llm.qa

import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus

/**
 * GraphQaPatchService 的纯校验 / 推断 helper（P2-1 深度拆分）。
 *
 * 这些函数无状态、根据候选变更 / 风险线程 / 证据列表 / 用户问题做证据等级判断、
 * claimType 推断、声明降级 / 提升、问题意图识别；与 GraphQaPatchService 的
 * orchestrator 主流程解耦后便于复用与单独测试。
 */

/** 按文件扩展名推断编程语言（"KOTLIN" / "JAVA" / "TEXT"）。 */
internal fun inferLanguage(filePath: String): String = when {
    filePath.endsWith(".kt", ignoreCase = true) -> "KOTLIN"
    filePath.endsWith(".java", ignoreCase = true) -> "JAVA"
    else -> "TEXT"
}

/** 返回可编辑符号签名：流程类节点优先从元数据取所属方法签名，其他节点直接返回 signature 字段。 */
internal fun editableSymbolSignature(node: GraphNode): String? = when (node.type) {
    NodeType.FLOW_SCOPE, NodeType.FLOW_ACTION, NodeType.TERMINAL ->
        node.metadata["flow.ownerMethod"]
            ?: node.metadata["flow.anchorMethod"]
            ?: node.signature
    else -> node.signature
}

/**
 * 归一化风险线程列表：丢弃无证据项，并补齐 claimType、summary、evidenceGap、recommendedQuestion 等字段。
 */
internal fun normalizeInvestigationThreads(threads: List<InvestigationThread>): List<InvestigationThread> {
    return threads.mapNotNull { thread ->
        val normalizedEvidence = thread.evidence.distinctBy(ResultEvidenceFinding::id)
        if (normalizedEvidence.isEmpty()) {
            return@mapNotNull null
        }
        thread.copy(
            claimType = thread.claimType ?: inferClaimType(normalizedEvidence),
            summary = thread.summary.ifBlank {
                normalizedEvidence.firstOrNull()?.claim ?: thread.title
            },
            evidenceGap = thread.evidenceGap.ifBlank {
                inferEvidenceGap(normalizedEvidence)
            },
            recommendedQuestion = thread.recommendedQuestion.ifBlank {
                buildRecommendedQuestion(thread.title, normalizedEvidence)
            },
            evidence = normalizedEvidence,
        )
    }
}

/** 判断风险线程是否可被提升为候选变更：状态为 OPEN、有目标节点且证据等级达到直接证据。 */
internal fun isEligibleForCandidatePromotion(thread: InvestigationThread): Boolean {
    return thread.status == InvestigationThreadStatus.OPEN &&
        (thread.targetNodeIds.isNotEmpty() || thread.targetStepIds.isNotEmpty()) &&
        thread.evidence.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
                finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
        }
}

/** 把风险线程转换为候选变更，保留原有证据与目标节点。 */
internal fun candidateFromThread(thread: InvestigationThread): CandidateDraftChange {
    val normalizedEvidence = thread.evidence.distinctBy(ResultEvidenceFinding::id)
    return CandidateDraftChange(
        changeId = promotedChangeIdForThread(thread.threadId),
        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
        title = promotedCandidateTitle(thread),
        targetStepIds = thread.targetStepIds,
        targetNodeIds = thread.targetNodeIds,
        beforeState = null,
        afterState = thread.summary.takeIf { it.isNotBlank() },
        reason = thread.summary.ifBlank { thread.evidenceGap },
        impactSummary = thread.evidenceGap.ifBlank { thread.recommendedQuestion },
        claimType = thread.claimType ?: inferClaimType(normalizedEvidence),
        evidence = normalizedEvidence,
    )
}

/** 根据线程 ID 生成对应的候选变更 ID，保持前缀一致便于追溯。 */
internal fun promotedChangeIdForThread(threadId: String): String =
    if (threadId.startsWith("thread-")) {
        "change-${threadId.removePrefix("thread-")}"
    } else {
        "change-$threadId"
    }

/** 生成风险线程提升为候选变更后的展示标题，依次回退到原始标题、摘要与推荐问题。 */
internal fun promotedCandidateTitle(thread: InvestigationThread): String {
    val rawTitle = thread.title.trim()
    if (rawTitle.isNotBlank() && rawTitle != thread.threadId) {
        return rawTitle
    }
    val summary = thread.summary.trim()
    if (summary.isNotBlank()) {
        return summary
    }
    val recommendedQuestion = thread.recommendedQuestion.trim()
    if (recommendedQuestion.isNotBlank()) {
        return recommendedQuestion
    }
    return thread.threadId
}

/** 根据证据等级推断声明类型：包含直接证据视为 CODE_FACT，否则视为 RISK_HINT。 */
internal fun inferClaimType(evidence: List<ResultEvidenceFinding>): String =
    if (evidence.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
                finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
        }
    ) {
        "CODE_FACT"
    } else {
        "RISK_HINT"
    }

/** 把证据不足的候选变更降级为风险线程，保留原证据与目标节点。 */
internal fun threadFromWeakCandidateChange(change: CandidateDraftChange): InvestigationThread {
    val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
    return InvestigationThread(
        threadId = "thread-${change.changeId}",
        status = InvestigationThreadStatus.OPEN,
        title = change.title,
        targetStepIds = change.targetStepIds,
        targetNodeIds = change.targetNodeIds,
        summary = change.reason.ifBlank { change.impactSummary },
        evidenceGap = inferEvidenceGap(normalizedEvidence),
        recommendedQuestion = buildRecommendedQuestion(change.title, normalizedEvidence),
        claimType = change.claimType ?: inferClaimType(normalizedEvidence),
        evidence = normalizedEvidence,
    )
}

/** 根据证据等级推断当前证据缺口描述，便于 UI 提示用户该线索需要补什么证据。 */
internal fun inferEvidenceGap(evidence: List<ResultEvidenceFinding>): String = when {
    evidence.any { it.evidenceLevel == ResultEvidenceLevel.CALLSITE_ONLY } ->
        "当前只看到调用点，没有看到被调实现或完整分支。"
    evidence.any { it.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED } ->
        "当前上下文没有直接观察到这条行为对应的源码、节点或分支。"
    else ->
        "当前证据还不足以把这条结论提升为可入草稿的真实变更。"
}

/** 生成下一轮推荐的追问问题，根据当前证据缺口给出明确取证方向。 */
internal fun buildRecommendedQuestion(
    title: String,
    evidence: List<ResultEvidenceFinding>,
): String = when {
    evidence.any { it.evidenceLevel == ResultEvidenceLevel.CALLSITE_ONLY } ->
        "请继续取证：沿着这条调用继续展开被调实现，确认“$title”是否真的成立。"
    evidence.any { it.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED } ->
        "请继续取证：补充能直接证明“$title”的源码片段、条件分支或图节点。"
    else ->
        "请继续取证：核对“$title”的直接源码证据，再决定是否进入草稿。"
}

/**
 * 启发式判断用户问题是否明确要求修改代码：包含祈使语或修改类关键词时返回 true。
 *
 * 优先匹配明确的祈使/修改关键词；其次匹配"修改/调整/修复"等动词开头；
 * "为什么/是否/解释/介绍"等讨论性词返回 false。
 */
internal fun questionExplicitlyRequestsChange(question: String): Boolean {
    val normalizedQuestion = question.replace(Regex("\\s+"), "")
    if (normalizedQuestion.isBlank()) {
        return false
    }
    val imperativeMarkers = listOf(
        "请把",
        "请将",
        "改成",
        "改为",
        "调整成",
        "调整为",
        "修成",
        "修复成",
        "补上",
        "加上",
        "怎么改",
        "如何改",
        "写成待确认变更",
        "写成可编辑图",
        "生成代码diff",
        "生成diff",
        "输出diff",
        "给出diff",
    )
    if (imperativeMarkers.any(normalizedQuestion::contains)) {
        return true
    }
    if (Regex("^(请)?(直接)?(修改|调整|修正|修复|改|修|补|加|将)").containsMatchIn(normalizedQuestion)) {
        return true
    }
    val discussionMarkers = listOf(
        "为什么",
        "为何",
        "是否",
        "是不是",
        "哪里",
        "在哪",
        "解释",
        "介绍",
        "讲解",
        "确认",
        "分析",
        "说明",
    )
    if (discussionMarkers.any(normalizedQuestion::contains)) {
        return false
    }
    return false
}

/** 判断当前结果是否具备进入"待确认候选变更"路径的资格：远程结果或受信任的 runtime 证据均可。 */
internal fun canUseConfirmableCandidatePath(
    source: LlmResultSource,
    runtimeEvidenceTrusted: Boolean,
): Boolean = source != LlmResultSource.LOCAL_RULE || runtimeEvidenceTrusted

/**
 * 当远程仍以 patch 形式返回结果时，把每条 operation 转换为候选变更，便于统一后续归一化流程。
 *
 * 每条候选变更携带：
 * - changeId = operation.id
 * - title 回退链（operation.title / summary / elementId）
 * - targetNodeIds = 节点 + 边端点（去重）
 * - 子 patch（仅含本 operation 对应的 added/removed ID）
 * - claimType = operation.metadata["draft.claimType"]
 * - evidence = 调用方传入的 findings（通常是 base.findings）
 */
internal fun deriveCandidateChanges(
    patch: com.charmnight.linkgraph.model.GraphPatch?,
    findings: List<ResultEvidenceFinding>,
): List<CandidateDraftChange> {
    patch ?: return emptyList()
    return patch.operations.map { operation ->
        CandidateDraftChange(
            changeId = operation.id,
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = operation.title ?: operation.summary ?: operation.elementId,
            targetNodeIds = listOfNotNull(operation.node?.id, operation.edge?.fromNodeId, operation.edge?.toNodeId).distinct(),
            beforeState = null,
            afterState = operation.summary ?: operation.title,
            reason = "由远程问答建议生成。",
            impactSummary = patch.summary ?: "",
            claimType = operation.metadata["draft.claimType"],
            evidence = findings,
            graphPatch = com.charmnight.linkgraph.model.GraphPatch(
                summary = patch.summary,
                operations = listOf(operation),
                addedNodeIds = patch.addedNodeIds.filter { it == operation.elementId },
                removedNodeIds = patch.removedNodeIds.filter { it == operation.elementId },
                addedEdgeIds = patch.addedEdgeIds.filter { it == operation.elementId },
                removedEdgeIds = patch.removedEdgeIds.filter { it == operation.elementId },
            ),
        )
    }
}
