package com.charmnight.linkgraph.investigation.resolving

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.intellij.openapi.project.Project

/**
 * 封装 resolver 执行时所需的上下文。
 */
data class InvestigationContext(
    /** 保存当前 IDEA 项目。 */
    val project: Project,
    /** 保存 resolver 链路进入 read action 前准备好的 JVM 架构索引。 */
    val jvmEvidenceIndex: ArchitectureGraphIndex? = null,
)

/**
 * 定义一个确定性取证解析器。
 */
interface EvidenceResolver {
    /** 保存解析器稳定标识。 */
    val id: String

    /**
     * 判断解析器是否支持当前取证目标。
     */
    fun supports(goal: EvidenceGoal): Boolean

    /**
     * 执行确定性取证解析。
     */
    fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome
}

/**
 * 按顺序执行支持当前目标的解析器。
 */
class ResolverChain(
    /** 保存可用解析器列表。 */
    private val resolvers: List<EvidenceResolver>,
) {
    /**
     * 对一个目标执行所有匹配的解析器。
     */
    fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): List<ResolutionOutcome> {
        val supportedResolvers = resolvers.filter { resolver -> resolver.supports(goal) }
        if (supportedResolvers.isEmpty()) {
            return listOf(
                ResolutionOutcome.Unresolved(
                    resolverId = "resolver-chain",
                    reason = "没有 resolver 支持当前取证目标：${goal.kind}",
                    requiredEvidence = listOf("补充更明确的源码符号、图节点或运行时 trace。"),
                ),
            )
        }
        return supportedResolvers.map { resolver ->
            runCatching {
                resolver.resolve(goal, context)
            }.getOrElse { error ->
                ResolutionOutcome.Failed(
                    resolverId = resolver.id,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }
}
