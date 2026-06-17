package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.EvidenceResolver
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.intellij.openapi.application.ReadAction

abstract class JvmIndexReadActionEvidenceResolver(
    private val jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter,
) : EvidenceResolver {
    final override fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        val index = context.jvmEvidenceIndex
            ?: runCatching { jvmEvidenceIndexAdapter.acquireIndex(context.project) }
            .getOrElse { error -> return indexUnavailable(goal, error) }
        return ReadAction.compute<ResolutionOutcome, RuntimeException> {
            resolveInReadAction(goal, context, index)
        }
    }

    protected abstract fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome

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
