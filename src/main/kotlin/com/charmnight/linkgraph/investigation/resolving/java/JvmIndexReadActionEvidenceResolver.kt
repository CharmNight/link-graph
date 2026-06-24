package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.EvidenceResolver
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.intellij.openapi.application.ReadAction

/**
 * 基于 JVM 索引做证据解析的抽象基类。
 *
 * 在 [ReadActionEvidenceResolver] 之上增加"取得架构索引"的统一流程：
 * 1) 取得（或惰性构造）架构索引；失败时返回索引不可用结果；
 * 2) 在读锁内调用子类实现 [resolveInReadAction] 完成具体解析。
 * 子类只需实现业务逻辑，不需要关心索引获取与读锁包装。
 */
abstract class JvmIndexReadActionEvidenceResolver(
    /** JVM 证据索引适配器，封装索引取得逻辑。 */
    private val jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter,
) : EvidenceResolver {
    /**
     * 模板方法：取得索引 + 读锁内执行解析。
     * final 修饰防止子类绕过索引获取或读锁包装。
     */
    final override fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        // 优先用上下文已经持有的索引；缺失时通过适配器取得
        val index = context.jvmEvidenceIndex
            ?: runCatching { jvmEvidenceIndexAdapter.acquireIndex(context.project) }
                // 索引不可用时返回优雅的失败结果，而不是抛异常打断流程
                .getOrElse { error -> return indexUnavailable(goal, error) }
        return ReadAction.compute<ResolutionOutcome, RuntimeException> {
            resolveInReadAction(goal, context, index)
        }
    }

    /**
     * 子类实现的实际解析逻辑，已在 ReadAction 上下文中调用，可直接访问 PSI 与索引。
     *
     * @param goal 证据目标
     * @param context 调查上下文
     * @param index 架构索引，由模板方法保证非空
     */
    protected abstract fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome

    /**
     * 索引不可用时的默认失败结果。
     * 子类可重写以给出更具体的提示，例如建议用户先重建索引。
     */
    protected open fun indexUnavailable(
        goal: EvidenceGoal,
        error: Throwable,
    ): ResolutionOutcome =
        ResolutionOutcome.Unresolved(
            resolverId = id,
            reason = "无法获取共享 ArchitectureGraphIndex：${error.message ?: error.javaClass.simpleName}",
            requiredEvidence = listOf("等待项目索引完成，或先刷新架构索引后重试。"),
        )
}
