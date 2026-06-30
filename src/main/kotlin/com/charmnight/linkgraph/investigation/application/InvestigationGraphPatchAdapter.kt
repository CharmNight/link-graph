package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.investigation.application.InvestigationStatus
import com.charmnight.linkgraph.investigation.application.InvestigationTurnResult
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.agent.model.ResultEvidenceLevel
import com.charmnight.linkgraph.agent.model.ResultEvidenceReference
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

/**
 * 把确定性继续取证结果适配为现有问答结果模型。
 *
 * 该层只做模型转换，不执行 resolver，也不调用 LLM。
 */
class InvestigationGraphPatchAdapter {
    /**
     * 把取证流水线结果转换成 UI 和会话服务可以消费的 `GraphPatchResult`。
     */
    fun toGraphPatchResult(
        request: ReplayableQaRequest,
        turnResult: InvestigationTurnResult,
    ): GraphPatchResult {
        val sourceThreadId = requireNotNull(request.sourceThreadId) {
            "继续取证结果必须带有 sourceThreadId。"
        }
        val sourceThread = sourceThread(request, sourceThreadId)
        val findings = turnResult.acceptedFacts.mapIndexed { index, fact ->
            fact.toFinding(index)
        }
        val targetNodeIds = (sourceThread?.targetNodeIds.orEmpty() + request.selectedNodeIds).distinct()
        val blockedReason = if (turnResult.status == InvestigationStatus.NEEDS_MORE_EVIDENCE) {
            turnResult.requiredEvidence.joinToString("；")
        } else {
            null
        }
        val threadUpdate = InvestigationThread(
            threadId = sourceThreadId,
            status = if (turnResult.status == InvestigationStatus.NEEDS_MORE_EVIDENCE) {
                InvestigationThreadStatus.BLOCKED
            } else {
                InvestigationThreadStatus.OPEN
            },
            title = sourceThread?.title.orEmpty(),
            targetNodeIds = targetNodeIds,
            summary = turnResult.summary,
            evidenceGap = blockedReason.orEmpty(),
            recommendedQuestion = if (turnResult.status == InvestigationStatus.NEEDS_MORE_EVIDENCE) {
                "请补充运行时 trace、配置绑定或更明确的源码符号后继续取证。"
            } else {
                ""
            },
            claimType = sourceThread?.claimType ?: "RISK_HINT",
            evidence = findings,
        )
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = request.question,
            answer = turnResult.summary,
            promptPreview = "",
            findings = findings,
            investigationThreads = listOf(threadUpdate),
            warnings = if (turnResult.status == InvestigationStatus.NEEDS_MORE_EVIDENCE) {
                listOf("继续取证没有拿到可进入 LLM 上下文的直接证据。")
            } else {
                emptyList()
            },
        )
    }

    /**
     * 从请求携带的历史会话中读取原始风险线程。
     */
    private fun sourceThread(
        request: ReplayableQaRequest,
        sourceThreadId: String,
    ): InvestigationThread? {
        return request.baseSession?.investigationThreads
            ?.firstOrNull { thread -> thread.threadId == sourceThreadId }
    }

    /**
     * 把取证事实转换为问答证据 finding。
     */
    private fun EvidenceFact.toFinding(index: Int): ResultEvidenceFinding {
        return ResultEvidenceFinding(
            id = factId.ifBlank { "investigation-finding-$index" },
            claim = claim,
            evidenceLevel = toResultEvidenceLevel(),
            references = listOf(
                ResultEvidenceReference(
                    filePath = filePath,
                    startLine = startLine,
                    endLine = endLine,
                ),
            ),
        )
    }

    /**
     * 把内部证据等级映射为 UI 现有的证据等级。
     */
    private fun EvidenceFact.toResultEvidenceLevel(): ResultEvidenceLevel {
        return when (level) {
            EvidenceLevel.DIRECT_GRAPH_RESOLVED -> ResultEvidenceLevel.DIRECT_GRAPH
            EvidenceLevel.DIRECT_SOURCE_RESOLVED,
            EvidenceLevel.DIRECT_FRAMEWORK_RESOLVED,
            EvidenceLevel.CONFIG_RESOLVED,
            -> ResultEvidenceLevel.DIRECT_SOURCE
            EvidenceLevel.CANDIDATE_ONLY,
            EvidenceLevel.MULTIPLE_CANDIDATES,
            EvidenceLevel.UNRESOLVED,
            -> ResultEvidenceLevel.NOT_OBSERVED
        }
    }
}
