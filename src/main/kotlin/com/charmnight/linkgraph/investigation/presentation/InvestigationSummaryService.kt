package com.charmnight.linkgraph.investigation.presentation

import com.charmnight.linkgraph.investigation.domain.GateDecision
import com.charmnight.linkgraph.investigation.domain.InvestigationRequest

/**
 * 负责把已通过闸门的证据总结为用户可读文本。
 */
interface InvestigationSummaryService {
    /**
     * 基于已接受证据生成总结。
     */
    fun summarize(
        request: InvestigationRequest,
        gateDecision: GateDecision.Accepted,
    ): String
}

/**
 * 使用确定性模板生成取证总结。
 */
class TemplateInvestigationSummaryService : InvestigationSummaryService {
    /**
     * 生成只引用已接受事实的总结。
     */
    override fun summarize(
        request: InvestigationRequest,
        gateDecision: GateDecision.Accepted,
    ): String {
        val facts = gateDecision.facts.joinToString("；") { fact ->
            "${fact.symbolSignature} @ ${fact.filePath}:${fact.startLine ?: "?"}-${fact.endLine ?: "?"}"
        }
        return "本轮已确认直接证据：$facts。"
    }
}

/**
 * 使用确定性模板生成无直接证据时的阻塞说明。
 */
class InvestigationNoEvidencePresenter {
    /**
     * 生成不调用 LLM 的无证据说明。
     */
    fun present(
        request: InvestigationRequest,
        decision: GateDecision.NeedsMoreEvidence,
    ): String {
        val required = decision.requiredEvidence.joinToString("；")
        return "本轮没有拿到可进入 LLM 上下文的直接证据。风险线程=${request.threadId}。下一步需要：$required"
    }
}
