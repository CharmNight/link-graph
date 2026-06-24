package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.investigation.application.GateDecision
import com.charmnight.linkgraph.investigation.application.InvestigationRequest

/**
 * 负责把已通过闸门的证据总结为用户可读文本。
 *
 * 把"结构化的证据列表"转换为"一句话总结"，让用户能在 UI 上快速理解本轮调查得到了什么。
 * 抽象为接口便于不同实现（模板化 vs LLM 总结）共存。
 */
interface InvestigationSummaryProjector {
    /**
     * 基于已接受证据生成总结。
     *
     * @param request 原始调查请求
     * @param gateDecision 已通过闸门的接受决策（包含已确认事实）
     * @return 用户可读的总结文本
     */
    fun summarize(
        request: InvestigationRequest,
        gateDecision: GateDecision.Accepted,
    ): String
}

/**
 * 使用确定性模板生成取证总结。
 *
 * 不调用 LLM，纯字符串模板拼接，保证总结可复现、可测试。
 * 适合在用户只是想知道"本轮证据是什么"的场景使用。
 */
class TemplateInvestigationSummaryProjector : InvestigationSummaryProjector {
    /**
     * 生成只引用已接受事实的总结。
     * 每条事实以 "签名 @ 文件:行号" 形式呈现，用分号分隔。
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
 *
 * 与 [TemplateInvestigationSummaryProjector] 类似，但专门处理"还需要更多证据"的场景，
 * 给用户列出下一步需要补齐什么。
 */
class InvestigationNoEvidencePresenter {
    /**
     * 生成不调用 LLM 的无证据说明。
     *
     * @param request 原始调查请求
     * @param decision 决策（包含"还需要哪些证据"信息）
     * @return 用户可读的阻塞说明
     */
    fun present(
        request: InvestigationRequest,
        decision: GateDecision.NeedsMoreEvidence,
    ): String {
        val required = decision.requiredEvidence.joinToString("；")
        return "本轮没有拿到可进入 LLM 上下文的直接证据。风险线程=${request.threadId}。下一步需要：$required"
    }
}
