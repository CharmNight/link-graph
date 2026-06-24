package com.charmnight.linkgraph.investigation.resolving

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
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
 *
 * 与 LLM 推断不同，确定性解析器给出可复现的结果（基于索引、PSI 等静态数据）。
 * 不同的解析器按 [supports] 决定是否处理某个取证目标，
 * 由 [ResolverChain] 串联起来形成完整的取证流程。
 */
interface EvidenceResolver {
    /** 保存解析器稳定标识。 */
    val id: String

    /**
     * 判断解析器是否支持当前取证目标。
     * 不同 resolver 处理不同种类的目标（方法符号、字段符号、资源等）。
     */
    fun supports(goal: EvidenceGoal): Boolean

    /**
     * 执行确定性取证解析。
     *
     * @param goal 取证目标
     * @param context 取证上下文，提供项目与索引
     * @return 解析结果
     */
    fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome
}

/**
 * 按顺序执行支持当前目标的解析器。
 *
 * 不是"找到第一个就停"，而是把所有支持的 resolver 都跑一遍，
 * 让上游可以从多个结果中选择最可信的。这种"宽松聚合"模式更适合
 * 证据类问题（多源佐证比单一来源更可靠）。
 *
 * @param resolvers 可用解析器列表
 */
class ResolverChain(
    /** 保存可用解析器列表。 */
    private val resolvers: List<EvidenceResolver>,
) {
    /**
     * 对一个目标执行所有匹配的解析器。
     *
     * @return 结果列表；没有任何匹配 resolver 时返回单一"未解析"结果
     */
    fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): List<ResolutionOutcome> {
        val supportedResolvers = resolvers.filter { resolver -> resolver.supports(goal) }
        // 没有匹配 resolver：返回单一未解析结果，避免返回空列表导致上游处理麻烦
        if (supportedResolvers.isEmpty()) {
            return listOf(
                ResolutionOutcome.Unresolved(
                    resolverId = "resolver-chain",
                    reason = "没有 resolver 支持当前取证目标：${goal.kind}",
                    requiredEvidence = listOf("补充更明确的源码符号、图节点或运行时 trace。"),
                ),
            )
        }
        // 每个 resolver 单独 try/catch：单个 resolver 失败不影响其他
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
