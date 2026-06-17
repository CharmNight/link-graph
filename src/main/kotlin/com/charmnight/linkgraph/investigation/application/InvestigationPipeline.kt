package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.EvidenceCandidate
import com.charmnight.linkgraph.investigation.application.GateDecision
import com.charmnight.linkgraph.investigation.application.InvestigationRequest
import com.charmnight.linkgraph.investigation.application.InvestigationStatus
import com.charmnight.linkgraph.investigation.application.InvestigationTurnResult
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.application.EvidenceGate
import com.charmnight.linkgraph.investigation.application.EvidenceGoalPlanner
import com.charmnight.linkgraph.investigation.application.InvestigationNoEvidencePresenter
import com.charmnight.linkgraph.investigation.application.InvestigationSummaryProjector
import com.charmnight.linkgraph.investigation.application.TemplateInvestigationSummaryProjector
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.ResolverChain
import com.charmnight.linkgraph.investigation.resolving.java.JavaEnumConstantResolver
import com.charmnight.linkgraph.investigation.resolving.java.JavaMethodSymbolResolver
import com.charmnight.linkgraph.investigation.resolving.java.JavaOverrideResolver
import com.charmnight.linkgraph.investigation.resolving.java.JavaReflectionResolver
import com.charmnight.linkgraph.investigation.resolving.java.JavaSpiResolver
import com.charmnight.linkgraph.investigation.resolving.java.JvmEvidenceIndexAdapter
import com.charmnight.linkgraph.investigation.resolving.spring.SpringEventResolver
import com.intellij.openapi.project.Project

/**
 * 负责执行继续取证流水线。
 */
class InvestigationPipeline(
    /** 保存取证目标规划器。 */
    private val planner: EvidenceGoalPlanner,
    /** 保存 resolver 执行链。 */
    private val resolverChain: ResolverChain,
    /** 保存证据闸门。 */
    private val gate: EvidenceGate,
    /** 保存有直接证据时的总结服务。 */
    private val summaryService: InvestigationSummaryProjector,
    /** 保存无直接证据时的展示器。 */
    private val noEvidencePresenter: InvestigationNoEvidencePresenter,
    /** 保存当前项目。 */
    private val project: Project,
    /** 负责在 resolver 进入 read action 前准备共享 JVM index。 */
    private val jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) {
    /**
     * 执行一次继续取证。
     */
    fun run(request: InvestigationRequest): InvestigationTurnResult {
        val goals = planner.plan(request)
        val context = InvestigationContext(
            project = project,
            jvmEvidenceIndex = prepareJvmEvidenceIndex(goals),
        )
        val outcomes = goals.flatMap { goal ->
            // 核心约束：resolver 只能产出结构化证据或候选，不能直接把弱相关源码塞进 LLM 上下文。
            resolverChain.resolve(goal, context)
        }
        return when (val decision = gate.decide(outcomes)) {
            is GateDecision.Accepted -> InvestigationTurnResult(
                threadId = request.threadId,
                status = InvestigationStatus.RESOLVED,
                acceptedFacts = decision.facts,
                candidates = candidates(outcomes),
                requiredEvidence = emptyList(),
                outcomes = outcomes,
                summary = summaryService.summarize(request, decision),
            )

            is GateDecision.NeedsMoreEvidence -> InvestigationTurnResult(
                threadId = request.threadId,
                status = InvestigationStatus.NEEDS_MORE_EVIDENCE,
                acceptedFacts = emptyList(),
                candidates = candidates(outcomes),
                requiredEvidence = decision.requiredEvidence,
                outcomes = outcomes,
                summary = noEvidencePresenter.present(request, decision),
            )
        }
    }

    /**
     * 汇总候选结果，供 UI 展示但不进入 LLM 上下文。
     */
    private fun candidates(outcomes: List<ResolutionOutcome>): List<EvidenceCandidate> {
        return outcomes.flatMap { outcome ->
            when (outcome) {
                is ResolutionOutcome.CandidateOnly -> outcome.candidates
                is ResolutionOutcome.MultipleCandidates -> outcome.candidates
                is ResolutionOutcome.Failed,
                is ResolutionOutcome.Resolved,
                is ResolutionOutcome.Unresolved,
                -> emptyList()
            }
        }.distinctBy(EvidenceCandidate::candidateId)
    }

    private fun prepareJvmEvidenceIndex(goals: List<EvidenceGoal>): ArchitectureGraphIndex? {
        if (goals.none { goal -> goal.kind in jvmEvidenceGoalKinds }) {
            return null
        }
        return jvmEvidenceIndexAdapter.buildIndexOutsideReadAction(project)
    }

    companion object {
        private val jvmEvidenceGoalKinds = setOf(
            EvidenceGoalKind.ENUM_CONSTANT,
            EvidenceGoalKind.METHOD_SYMBOL,
            EvidenceGoalKind.METHOD_OVERRIDE,
            EvidenceGoalKind.SPI_BINDING,
            EvidenceGoalKind.REFLECTION_CALL,
            EvidenceGoalKind.SPRING_EVENT,
        )

        /**
         * 创建默认继续取证流水线。
         */
        fun default(
            project: Project,
            summaryService: InvestigationSummaryProjector = TemplateInvestigationSummaryProjector(),
        ): InvestigationPipeline {
            return InvestigationPipeline(
                planner = EvidenceGoalPlanner(),
                resolverChain = ResolverChain(
                    listOf(
                        JavaEnumConstantResolver(),
                        JavaMethodSymbolResolver(),
                        JavaOverrideResolver(),
                        JavaSpiResolver(),
                        JavaReflectionResolver(),
                        SpringEventResolver(),
                    ),
                ),
                gate = EvidenceGate(),
                summaryService = summaryService,
                noEvidencePresenter = InvestigationNoEvidencePresenter(),
                project = project,
            )
        }
    }
}
