package com.charmnight.linkgraph.investigation.resolving

import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.intellij.openapi.application.ReadAction

abstract class ReadActionEvidenceResolver : EvidenceResolver {
    final override fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        return ReadAction.compute<ResolutionOutcome, RuntimeException> {
            resolveInReadAction(goal, context)
        }
    }

    protected abstract fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome
}
