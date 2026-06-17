package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.investigation.application.GateDecision
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome

/**
 * 负责把 resolver 输出转换为可进入 LLM 上下文的证据集合。
 */
class EvidenceGate {
    /**
     * 裁决一组 resolver 输出。
     */
    fun decide(outcomes: List<ResolutionOutcome>): GateDecision {
        val acceptedFacts = outcomes
            .filterIsInstance<ResolutionOutcome.Resolved>()
            .flatMap(ResolutionOutcome.Resolved::facts)
            .filter(::isAcceptableFact)
            .distinctBy { fact ->
                // 同一 resolver 可能从调用点和目标符号两个目标推导出同一事实，这里按真实符号与位置去重。
                listOf(fact.resolverId, fact.level.name, fact.symbolSignature, fact.filePath, fact.startLine, fact.endLine)
            }
        if (acceptedFacts.isNotEmpty()) {
            return GateDecision.Accepted(
                facts = acceptedFacts,
                outcomes = outcomes,
            )
        }
        return GateDecision.NeedsMoreEvidence(
            outcomes = outcomes,
            requiredEvidence = requiredEvidence(outcomes),
            reason = "本轮没有拿到可进入 LLM 上下文的直接证据。",
        )
    }

    /**
     * 判断事实是否满足进入 LLM 上下文的最低条件。
     */
    private fun isAcceptableFact(fact: EvidenceFact): Boolean {
        if (fact.level !in acceptedLevels) {
            return false
        }
        return fact.symbolSignature.isNotBlank() &&
            fact.filePath.isNotBlank() &&
            fact.resolverId.isNotBlank() &&
            fact.whyResolved.isNotBlank()
    }

    /**
     * 汇总所有 resolver 给出的后续证据需求。
     */
    private fun requiredEvidence(outcomes: List<ResolutionOutcome>): List<String> {
        val evidence = outcomes.flatMap { outcome ->
            when (outcome) {
                is ResolutionOutcome.CandidateOnly -> outcome.requiredEvidence
                is ResolutionOutcome.MultipleCandidates -> outcome.requiredEvidence
                is ResolutionOutcome.Unresolved -> outcome.requiredEvidence
                is ResolutionOutcome.Failed -> listOf("修复 ${outcome.resolverId} 执行失败：${outcome.error}")
                is ResolutionOutcome.Resolved -> emptyList()
            }
        }.filter(String::isNotBlank).distinct()
        return evidence.ifEmpty {
            listOf("补充更明确的源码符号、配置绑定、运行时 traceId 或人工确认。")
        }
    }

    private companion object {
        /** 保存允许进入 LLM 上下文的证据等级。 */
        private val acceptedLevels = setOf(
            EvidenceLevel.DIRECT_SOURCE_RESOLVED,
            EvidenceLevel.DIRECT_FRAMEWORK_RESOLVED,
            EvidenceLevel.DIRECT_GRAPH_RESOLVED,
            EvidenceLevel.CONFIG_RESOLVED,
        )
    }
}
