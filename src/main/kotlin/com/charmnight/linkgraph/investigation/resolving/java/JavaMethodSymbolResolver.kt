package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext

/**
 * 使用统一 ArchitectureGraphIndex 解析普通方法符号，负责处理重载方法边界。
 *
 * 继承 [JvmIndexReadActionEvidenceResolver] 拿到"取得索引 + 读锁"的能力，
 * 自身只关注"如何从索引中找方法、如何处理多候选"。
 */
class JavaMethodSymbolResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-method-symbol"

    /**
     * 仅处理方法符号目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.METHOD_SYMBOL
    }

    /**
     * 在 read action 内执行方法解析。
     *
     * 三种结果分支：
     * - 唯一候选 → Resolved，附带唯一方法事实；
     * - 多候选 → MultipleCandidates，列出所有重载并要求更多证据；
     * - 无候选 → Unresolved，给出失败原因。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        val candidates = JvmInvestigationEvidenceSupport.resolveMethodCandidates(goal, index)
        return when {
            // 唯一命中：解析成功
            candidates.methods.size == 1 -> ResolutionOutcome.Resolved(
                resolverId = id,
                facts = listOf(
                    JvmInvestigationEvidenceSupport.methodFact(
                        goal = goal,
                        resolverId = id,
                        method = candidates.methods.single(),
                        whyResolved = "ArchitectureGraphIndex 按类名、方法名、参数和返回值解析到唯一 JvmMethodSymbol。",
                    ),
                ),
            )
            // 多候选：要求用户提供更多签名信息
            candidates.methods.size > 1 -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = candidates.methods.map { method ->
                    JvmInvestigationEvidenceSupport.methodCandidate(
                        goal = goal,
                        resolverId = id,
                        method = method,
                        reason = "同名方法存在多个重载，缺少完整方法签名。",
                    )
                },
                reason = "方法 ${goal.ownerClassName}.${goal.methodName} 命中多个重载。",
                requiredEvidence = listOf("补充完整方法签名、调用点源码位置或运行时 trace。"),
            )
            // 无候选：返回未解析
            else -> ResolutionOutcome.Unresolved(
                resolverId = id,
                reason = candidates.unresolvedReason ?: "未找到方法 ${goal.ownerClassName}.${goal.methodName}。",
                requiredEvidence = listOf("补充完整方法签名或当前调用点源码位置。"),
            )
        }
    }
}
