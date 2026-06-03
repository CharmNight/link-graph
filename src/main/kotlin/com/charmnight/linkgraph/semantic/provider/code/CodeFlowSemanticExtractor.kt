package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowEdgeRole
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MergeUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticBoundary
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticDiagnosticSeverity
import com.charmnight.linkgraph.semantic.model.SemanticIdFactory
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.code.relation.RelationExtractionContext
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.semantic.subject.methodDisplayName
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.charmnight.linkgraph.semantic.subject.sourcePathOf
import com.charmnight.linkgraph.semantic.subject.sourceRangeOf
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhileStatement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class CodeFlowSemanticExtractor(
    private val invocationResolver: CodeInvocationSemanticResolver = CodeInvocationSemanticResolver(),
    private val architectureIndexProvider: (() -> ArchitectureGraphIndex?)? = null,
) {
    fun extract(
        handle: CodeSubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        return ReadAction.compute<SemanticAnalysisResult, RuntimeException> {
            val subjectMethod = handle.methodPointer.element
            if (subjectMethod == null) {
                return@compute SemanticAnalysisResult(
                    subject = handle,
                    anchors = emptyList(),
                    semanticUnits = emptyList(),
                    relations = emptyList(),
                    diagnostics = listOf(
                        SemanticDiagnostic(
                            severity = SemanticDiagnosticSeverity.ERROR,
                            code = "subject-method-lost",
                            message = "当前代码主体在分析前已失效，请重新触发分析。",
                        ),
                    ),
                    boundaries = emptyList(),
                    sourceMappings = emptyList(),
                )
            }

            val accumulator = CodeSemanticAccumulator(handle)
            val subjectMethodUnit = accumulator.addMethod(subjectMethod)
            accumulator.addAnchor(subjectMethodUnit.id, "当前主体")
            val relationContext = if (capturePolicy.includeResourceReferences) {
                RelationExtractionContext(subjectMethod.project, architectureIndexProvider)
            } else {
                null
            }

            val downstreamQueue = ArrayDeque<TraversalTask>()
            val upstreamQueue = ArrayDeque<TraversalTask>()
            downstreamQueue.addLast(TraversalTask(subjectMethod, depth = 0))
            upstreamQueue.addLast(TraversalTask(subjectMethod, depth = 0))

            val processedDownstream = linkedSetOf<String>()
            val processedUpstream = linkedSetOf<String>()

            while (downstreamQueue.isNotEmpty()) {
                val task = downstreamQueue.removeFirst()
                val method = task.method
                val methodKey = methodSignature(method)
                if (!processedDownstream.add(methodKey)) {
                    continue
                }

                val ownerMethodUnit = accumulator.addMethod(method)
                val flowResult = when (classifyCodeSubject(method)) {
                    com.charmnight.linkgraph.semantic.subject.CodeSubjectKind.JAVA_METHOD -> {
                        JavaFlowSemanticBuilder(
                            method = method,
                            ownerMethodUnitId = ownerMethodUnit.id,
                            accumulator = accumulator,
                            capturePolicy = capturePolicy,
                            budgetPolicy = budgetPolicy,
                        ).build()
                    }

                    else -> {
                        KotlinFlowSemanticBuilder(
                            method = method,
                            ownerMethodUnitId = ownerMethodUnit.id,
                            accumulator = accumulator,
                            capturePolicy = capturePolicy,
                            budgetPolicy = budgetPolicy,
                        ).build()
                    }
                }

                flowResult.boundary?.let(accumulator::addBoundary)
                flowResult.diagnostics.forEach(accumulator::addDiagnostic)
                flowResult.discoveredMethods
                    .take(budgetPolicy.maxInvocationsPerUnit.coerceAtLeast(0))
                    .forEach { targetMethod ->
                        accumulator.addMethod(targetMethod)
                        if (task.depth < budgetPolicy.maxDownstreamDepth) {
                            downstreamQueue.addLast(TraversalTask(targetMethod, task.depth + 1))
                        }
                }

                if (capturePolicy.includeResourceReferences) {
                    val resolution = invocationResolver.resolve(method, relationContext!!)
                    accumulator.addResolution(resolution)
                    resolution.additionalMethods
                        .take(budgetPolicy.maxRelatedResourcesPerUnit.coerceAtLeast(0))
                        .forEach { additionalMethod ->
                            accumulator.addMethod(additionalMethod)
                            if (task.depth < budgetPolicy.maxDownstreamDepth) {
                                downstreamQueue.addLast(TraversalTask(additionalMethod, task.depth + 1))
                            }
                        }
                }
            }

            while (upstreamQueue.isNotEmpty()) {
                val task = upstreamQueue.removeFirst()
                val method = task.method
                val methodKey = methodSignature(method)
                if (!processedUpstream.add(methodKey)) {
                    continue
                }
                if (task.depth >= budgetPolicy.maxUpstreamDepth) {
                    continue
                }
                val targetUnit = accumulator.addMethod(method)
                val callers = resolveCallers(method, budgetPolicy.maxInvocationsPerUnit + 1)
                val visibleCallers = callers.take(budgetPolicy.maxInvocationsPerUnit.coerceAtLeast(0))
                if (callers.size > visibleCallers.size) {
                    accumulator.addDiagnostic(
                        SemanticDiagnostic(
                            severity = SemanticDiagnosticSeverity.WARNING,
                            code = "upstream-caller-truncated",
                            message = "上游调用方已按预算裁剪，未继续保留 ${callers.size - visibleCallers.size} 个方法。",
                        ),
                    )
                }
                visibleCallers.forEach { caller ->
                    val callerUnit = accumulator.addMethod(caller)
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.INVOKES,
                            fromUnitId = callerUnit.id,
                            toUnitId = targetUnit.id,
                        ),
                    )
                    upstreamQueue.addLast(TraversalTask(caller, task.depth + 1))
                }
            }

            accumulator.build()
        }
    }

    private data class TraversalTask(
        val method: PsiMethod,
        val depth: Int,
    )
}

private abstract class BaseFlowSemanticBuilder(
    protected val method: PsiMethod,
    protected val ownerMethodUnitId: String,
    protected val accumulator: CodeSemanticAccumulator,
    protected val capturePolicy: SemanticCapturePolicy,
    protected val budgetPolicy: TraversalBudgetPolicy,
) {
    protected val discoveredMethods = linkedSetOf<PsiMethod>()
    protected val ownerSignature: String = methodSignature(method)
    private val invocationResolutionIncomplete = AtomicBoolean(false)

    fun build(): FlowBuildResult {
        val executionPlan = resolveExecutionPlan(method)
        val boundary = executionPlan.boundary
        if (executionPlan.roots.isEmpty()) {
            return FlowBuildResult(
                discoveredMethods = emptyList(),
                boundary = boundary,
            )
        }
        val fragment = buildRoots(executionPlan.roots)
            .let(::attachImplicitMethodCompletion)
        fragment.entryUnitId?.let { entryUnitId ->
            if (capturePolicy.includeControlFlow) {
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = ownerMethodUnitId,
                            toUnitId = entryUnitId,
                            flowEdgeRole = FlowEdgeRole.ENTRY,
                        ),
                    )
                }
        }
        return FlowBuildResult(
            discoveredMethods = discoveredMethods.toList(),
            boundary = boundary,
            diagnostics = invocationResolutionDiagnostics(),
        )
    }

    private fun attachImplicitMethodCompletion(fragment: FlowFragment): FlowFragment {
        if (!capturePolicy.includeControlFlow || fragment.exits.isEmpty()) {
            return fragment
        }
        val implicitReturn = terminalFragment(
            element = method,
            title = "返回",
            terminalKind = "RETURN",
        )
        return sequenceFragments(listOf(fragment, implicitReturn))
    }

    protected abstract fun buildRoots(roots: List<PsiElement>): FlowFragment

    protected fun resolveDownstreamTargetsSafely(
        element: PsiElement,
        includeNestedLambdas: Boolean = true,
    ): List<ResolvedDownstreamTargetMethod> {
        return runCatching { resolveDownstreamTargets(element, includeNestedLambdas) }
            .getOrElse {
                invocationResolutionIncomplete.set(true)
                emptyList()
            }
    }

    private fun invocationResolutionDiagnostics(): List<SemanticDiagnostic> {
        if (!invocationResolutionIncomplete.get()) {
            return emptyList()
        }
        return listOf(
            SemanticDiagnostic(
                severity = SemanticDiagnosticSeverity.WARNING,
                code = "invocation-resolution-incomplete",
                message = "部分调用目标解析失败，当前图谱保留已确认的源码流程节点。",
            ),
        )
    }

    protected fun sequenceFragments(fragments: List<FlowFragment>): FlowFragment {
        var entryUnitId: String? = null
        var entryElement: PsiElement? = null
        var exits = linkedSetOf<FlowExit>()

        fragments.filter { fragment -> fragment.entryUnitId != null || fragment.exits.isNotEmpty() }
            .forEach { fragment ->
                if (entryUnitId == null) {
                    entryUnitId = fragment.entryUnitId
                    entryElement = fragment.entryElement
                }
                if (exits.isNotEmpty() && fragment.entryUnitId != null && capturePolicy.includeControlFlow) {
                    if (exits.size > 1) {
                        val mergeUnit = accumulator.addMerge(
                            ownerSignature = ownerSignature,
                            element = fragment.entryElement ?: method,
                            title = "汇合",
                            ownerMethodUnitId = ownerMethodUnitId,
                        )
                        exits.forEach { exit ->
                            accumulator.addRelation(
                                SemanticRelation(
                                    kind = SemanticRelationKind.CONTROL_FLOW,
                                    fromUnitId = exit.unitId,
                                    toUnitId = mergeUnit.id,
                                    label = exit.label,
                                    flowEdgeRole = exit.flowEdgeRole,
                                ),
                            )
                        }
                        accumulator.addRelation(
                            SemanticRelation(
                                kind = SemanticRelationKind.CONTROL_FLOW,
                                fromUnitId = mergeUnit.id,
                                toUnitId = fragment.entryUnitId,
                            ),
                        )
                    } else {
                        exits.forEach { exit ->
                            accumulator.addRelation(
                                SemanticRelation(
                                    kind = SemanticRelationKind.CONTROL_FLOW,
                                    fromUnitId = exit.unitId,
                                    toUnitId = fragment.entryUnitId,
                                    label = exit.label,
                                    flowEdgeRole = exit.flowEdgeRole,
                                ),
                            )
                        }
                    }
                }
                exits = fragment.exits.toCollection(linkedSetOf())
            }

        return FlowFragment(
            entryUnitId = entryUnitId,
            exits = exits,
            entryElement = entryElement,
        )
    }

    protected fun actionFragment(
        element: PsiElement,
        title: String,
        actionKind: String = "ACTION",
        targetMethodsOverride: List<ResolvedDownstreamTargetMethod>? = null,
    ): FlowFragment {
        val actionUnit = accumulator.addAction(
            ownerSignature = ownerSignature,
            element = element,
            title = title,
            actionKind = actionKind,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        if (!capturePolicy.includeInvocations) {
            return FlowFragment(
                entryUnitId = actionUnit.id,
                exits = linkedSetOf(FlowExit(actionUnit.id)),
                entryElement = element,
            )
        }

        val targets = targetMethodsOverride ?: resolveDownstreamTargetsSafely(element)
        val visibleTargets = targets.take(budgetPolicy.maxInvocationsPerUnit.coerceAtLeast(0))
        if (targets.size > visibleTargets.size) {
            accumulator.addDiagnostic(
                SemanticDiagnostic(
                    severity = SemanticDiagnosticSeverity.WARNING,
                    code = "downstream-invocation-truncated",
                    message = "调用目标已按预算裁剪，未继续保留 ${targets.size - visibleTargets.size} 个目标。",
                ),
            )
        }

        var previousVisibleUnitId = actionUnit.id
        visibleTargets.forEach { target ->
            discoveredMethods += target.method
            val targetUnit = accumulator.addMethod(target.method)
            val invocationUnit = accumulator.addInvocation(
                ownerSignature = ownerSignature,
                sourceUnitId = previousVisibleUnitId,
                targetSignature = targetUnit.signature,
                title = "调用 ${targetUnit.title}",
                element = element,
                ownerMethodUnitId = ownerMethodUnitId,
            )
            if (capturePolicy.includeControlFlow) {
                accumulator.addRelation(
                    SemanticRelation(
                        kind = SemanticRelationKind.CONTROL_FLOW,
                        fromUnitId = previousVisibleUnitId,
                        toUnitId = invocationUnit.id,
                    ),
                )
            }
            accumulator.addRelation(
                SemanticRelation(
                    kind = SemanticRelationKind.INVOKES,
                    fromUnitId = invocationUnit.id,
                    toUnitId = targetUnit.id,
                    metadata = mapOf(
                        "relation.confidence" to target.confidence.name,
                        "jvm.dispatch.kind" to target.dispatchKind,
                    ),
                ),
            )
            previousVisibleUnitId = invocationUnit.id
        }

        return FlowFragment(
            entryUnitId = actionUnit.id,
            exits = linkedSetOf(FlowExit(previousVisibleUnitId)),
            entryElement = element,
        )
    }

    /**
     * 控制结构头部里的表达式过去只参与标题拼接，没有进入统一语义链。
     * 这里把“能解析到可追踪调用”的头部表达式落成独立片段，避免条件/循环头里的调用在图里丢失。
     */
    protected fun executableHeaderFragment(
        element: PsiElement?,
        actionKind: String,
    ): FlowFragment? {
        element ?: return null
        if (resolveDownstreamTargetsSafely(element).isEmpty()) {
            return null
        }
        return actionFragment(
            element = element,
            title = summarizeExecutable(element),
            actionKind = actionKind,
        )
    }

    protected fun terminalFragment(
        element: PsiElement,
        title: String,
        terminalKind: String,
    ): FlowFragment {
        val terminalUnit = accumulator.addTerminal(
            ownerSignature = ownerSignature,
            element = element,
            title = title,
            terminalKind = terminalKind,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        return FlowFragment(
            entryUnitId = terminalUnit.id,
            exits = linkedSetOf(),
            entryElement = element,
        )
    }

    protected fun decisionFragment(
        element: PsiElement,
        title: String,
        trueFragment: FlowFragment,
        falseFragment: FlowFragment,
        guardFragment: FlowFragment? = null,
    ): FlowFragment {
        val decisionUnit = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = element,
            title = title,
            scopeKind = "IF",
            scopeCategory = FlowScopeCategory.BRANCH,
            ownerMethodUnitId = ownerMethodUnitId,
        )

        if (capturePolicy.includeControlFlow && guardFragment != null) {
            guardFragment.exits.forEach { exit ->
                accumulator.addRelation(
                    SemanticRelation(
                        kind = SemanticRelationKind.CONTROL_FLOW,
                        fromUnitId = exit.unitId,
                        toUnitId = decisionUnit.id,
                        label = exit.label,
                    ),
                )
            }
        }

        if (capturePolicy.includeControlFlow) {
            if (trueFragment.entryUnitId != null) {
                accumulator.addRelation(
                    SemanticRelation(
                        kind = SemanticRelationKind.CONTROL_FLOW,
                        fromUnitId = decisionUnit.id,
                        toUnitId = trueFragment.entryUnitId,
                        label = "TRUE",
                        flowEdgeRole = FlowEdgeRole.TRUE_BRANCH,
                    ),
                )
            }

            if (falseFragment.entryUnitId != null) {
                accumulator.addRelation(
                    SemanticRelation(
                        kind = SemanticRelationKind.CONTROL_FLOW,
                        fromUnitId = decisionUnit.id,
                        toUnitId = falseFragment.entryUnitId,
                        label = "FALSE",
                        flowEdgeRole = FlowEdgeRole.FALSE_BRANCH,
                    ),
                )
            }
        }

        val exits = linkedSetOf<FlowExit>()
        if (trueFragment.entryUnitId == null) {
            exits += FlowExit(decisionUnit.id, "TRUE", FlowEdgeRole.TRUE_BRANCH)
        }
        exits += trueFragment.exits
        if (falseFragment.entryUnitId == null) {
            exits += FlowExit(decisionUnit.id, "FALSE", FlowEdgeRole.FALSE_BRANCH)
        }
        exits += falseFragment.exits

        return FlowFragment(
            entryUnitId = guardFragment?.entryUnitId ?: decisionUnit.id,
            exits = exits,
            entryElement = guardFragment?.entryElement ?: element,
        )
    }

    protected enum class LoopGuardPlacement {
        BEFORE_BODY,
        AFTER_BODY,
    }

    protected fun loopFragment(
        element: PsiElement,
        title: String,
        scopeKind: String,
        bodyFragment: FlowFragment,
        guardFragment: FlowFragment? = null,
        guardPlacement: LoopGuardPlacement = LoopGuardPlacement.BEFORE_BODY,
        postBodyFragment: FlowFragment? = null,
        hasStructuredExit: Boolean = true,
    ): FlowFragment {
        val loopUnit = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = element,
            title = title,
            scopeKind = scopeKind,
            scopeCategory = when (guardPlacement) {
                LoopGuardPlacement.BEFORE_BODY -> FlowScopeCategory.LOOP_PRE_TEST
                LoopGuardPlacement.AFTER_BODY -> FlowScopeCategory.LOOP_POST_TEST
            },
            incomplete = !hasStructuredExit,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        if (capturePolicy.includeControlFlow) {
            if (guardFragment != null) {
                guardFragment.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = loopUnit.id,
                            label = exit.label,
                            flowEdgeRole = FlowEdgeRole.NORMAL,
                        ),
                    )
                }
            }
            if (bodyFragment.entryUnitId != null) {
                accumulator.addRelation(
                    SemanticRelation(
                        kind = SemanticRelationKind.CONTROL_FLOW,
                        fromUnitId = loopUnit.id,
                        toUnitId = bodyFragment.entryUnitId,
                        label = "TRUE",
                        flowEdgeRole = FlowEdgeRole.LOOP_BODY,
                    ),
                )
            }
            val loopBackTarget = guardFragment?.entryUnitId ?: loopUnit.id
            if (postBodyFragment != null && postBodyFragment.entryUnitId != null) {
                bodyFragment.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = postBodyFragment.entryUnitId,
                            label = exit.label ?: "LOOP_NEXT",
                            flowEdgeRole = FlowEdgeRole.LOOP_UPDATE,
                        ),
                    )
                }
                postBodyFragment.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = loopBackTarget,
                            label = exit.label ?: "LOOP_BACK",
                            flowEdgeRole = FlowEdgeRole.LOOP_BACK,
                        ),
                    )
                }
            } else {
                bodyFragment.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = loopBackTarget,
                            label = exit.label ?: "LOOP_BACK",
                            flowEdgeRole = FlowEdgeRole.LOOP_BACK,
                        ),
                    )
                }
            }
        }

        val entryUnitId = when (guardPlacement) {
            LoopGuardPlacement.BEFORE_BODY -> guardFragment?.entryUnitId ?: loopUnit.id
            LoopGuardPlacement.AFTER_BODY -> bodyFragment.entryUnitId ?: guardFragment?.entryUnitId ?: loopUnit.id
        }
        val entryElement = when (guardPlacement) {
            LoopGuardPlacement.BEFORE_BODY -> guardFragment?.entryElement ?: element
            LoopGuardPlacement.AFTER_BODY -> bodyFragment.entryElement ?: guardFragment?.entryElement ?: element
        }
        return FlowFragment(
            entryUnitId = entryUnitId,
            exits = if (hasStructuredExit) {
                linkedSetOf(FlowExit(loopUnit.id, "FALSE", FlowEdgeRole.LOOP_EXIT))
            } else {
                linkedSetOf()
            },
            entryElement = entryElement,
        )
    }
}

private class JavaFlowSemanticBuilder(
    method: PsiMethod,
    ownerMethodUnitId: String,
    accumulator: CodeSemanticAccumulator,
    capturePolicy: SemanticCapturePolicy,
    budgetPolicy: TraversalBudgetPolicy,
) : BaseFlowSemanticBuilder(method, ownerMethodUnitId, accumulator, capturePolicy, budgetPolicy) {
    override fun buildRoots(roots: List<PsiElement>): FlowFragment {
        val fragments = roots.mapNotNull { root ->
            when (root) {
                is PsiCodeBlock -> buildCodeBlock(root)
                is PsiStatement -> buildStatement(root)
                is PsiExpression -> actionFragment(root, summarizeExecutable(root))
                else -> null
            }
        }
        return sequenceFragments(fragments)
    }

    private fun buildCodeBlock(block: PsiCodeBlock): FlowFragment {
        return sequenceFragments(block.statements.map(::buildStatement))
    }

    private fun buildStatement(statement: PsiStatement): FlowFragment {
        return when (statement) {
            is PsiBlockStatement -> buildCodeBlock(statement.codeBlock)
            is PsiIfStatement -> buildIfStatement(statement)
            is PsiForeachStatement -> {
                val iterationSourceFragment = executableHeaderFragment(statement.iteratedValue, actionKind = "ITERATION_SOURCE")
                val loopBody = loopFragment(
                    element = statement,
                    title = summarize("for (${statement.iterationParameter?.name ?: "_"} : ${statement.iteratedValue?.text ?: "items"})"),
                    scopeKind = "FOREACH",
                    bodyFragment = statement.body?.let { buildStatementOrExpression(it) } ?: FlowFragment(null, linkedSetOf()),
                )
                sequenceFragments(listOfNotNull(iterationSourceFragment, loopBody))
            }

            is PsiForStatement -> {
                val initialization = statement.initialization?.let(::buildStatementOrExpression)
                val updateFragment = statement.update?.let(::buildStatementOrExpression)
                val loopBody = loopFragment(
                    element = statement,
                    title = summarize("for (${statement.condition?.text ?: "..."})"),
                    scopeKind = "FOR",
                    bodyFragment = statement.body?.let { buildStatementOrExpression(it) } ?: FlowFragment(null, linkedSetOf()),
                    guardFragment = executableHeaderFragment(statement.condition, actionKind = "CONDITION"),
                    postBodyFragment = updateFragment,
                    hasStructuredExit = hasStructuredNormalExit(statement.condition),
                )
                sequenceFragments(listOfNotNull(initialization, loopBody))
            }

            is PsiWhileStatement -> loopFragment(
                element = statement,
                title = summarize("while (${statement.condition?.text ?: "..."})"),
                scopeKind = "WHILE",
                bodyFragment = statement.body?.let { buildStatementOrExpression(it) } ?: FlowFragment(null, linkedSetOf()),
                guardFragment = executableHeaderFragment(statement.condition, actionKind = "CONDITION"),
                hasStructuredExit = hasStructuredNormalExit(statement.condition),
            )

            is PsiDoWhileStatement -> {
                val bodyFragment = statement.body?.let { buildStatementOrExpression(it) } ?: FlowFragment(null, linkedSetOf())
                loopFragment(
                    element = statement,
                    title = summarize("do-while (${statement.condition?.text ?: "..."})"),
                    scopeKind = "DO_WHILE",
                    bodyFragment = bodyFragment,
                    guardFragment = executableHeaderFragment(statement.condition, actionKind = "CONDITION"),
                    guardPlacement = LoopGuardPlacement.AFTER_BODY,
                    hasStructuredExit = hasStructuredNormalExit(statement.condition),
                )
            }

            is PsiSwitchStatement -> buildSwitchStatement(statement)
            is PsiTryStatement -> buildTryStatement(statement)
            is PsiReturnStatement -> buildReturnStatement(statement)
            is PsiThrowStatement -> terminalFragment(
                statement,
                buildThrowTitle(statement.exception),
                "THROW",
            )
            is PsiExpressionStatement -> buildExpressionStatement(statement)
            is PsiDeclarationStatement -> buildDeclarationStatement(statement)
            else -> FlowFragment(null, linkedSetOf())
        }
    }

    private fun buildExpressionStatement(statement: PsiExpressionStatement): FlowFragment {
        val methodCall = statement.expression as? PsiMethodCallExpression
        return if (methodCall != null && methodCall.argumentList.expressions.any { argument -> argument is PsiLambdaExpression }) {
            buildMethodCallWithLambdaBodies(methodCall)
        } else {
            actionFragment(statement.expression, summarizeExecutable(statement.expression))
        }
    }

    private fun buildStatementOrExpression(element: PsiElement): FlowFragment {
        return when (element) {
            is PsiStatement -> buildStatement(element)
            is PsiExpression -> actionFragment(element, summarizeExecutable(element))
            else -> FlowFragment(null, linkedSetOf())
        }
    }

    private fun buildMethodCallWithLambdaBodies(expression: PsiMethodCallExpression): FlowFragment {
        val resolvedMethod = expression.resolveMethod()
        val actionFragment = actionFragment(
            element = expression,
            title = summarizeExecutable(expression),
            targetMethodsOverride = resolveDownstreamTargetsSafely(expression, includeNestedLambdas = false),
        )
        val lambdaFragments = expression.argumentList.expressions
            .filterIsInstance<PsiLambdaExpression>()
            .map { lambdaExpression ->
                buildLambdaScopeFragment(
                    lambdaExpression = lambdaExpression,
                    ownerMethod = resolvedMethod,
                    fallbackName = expression.methodExpression.referenceName,
                )
            }
        return sequenceFragments(listOf(actionFragment) + lambdaFragments)
    }

    private fun buildLambdaScopeFragment(
        lambdaExpression: PsiLambdaExpression,
        ownerMethod: PsiMethod?,
        fallbackName: String?,
    ): FlowFragment {
        val lambdaScope = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = lambdaExpression,
            title = buildJavaLambdaTitle(ownerMethod, fallbackName, lambdaExpression),
            scopeKind = "LAMBDA",
            ownerMethodUnitId = ownerMethodUnitId,
        )
        val bodyFragment = when (val body = lambdaExpression.body) {
            is PsiCodeBlock -> buildCodeBlock(body)
            null -> FlowFragment(null, linkedSetOf())
            else -> buildStatementOrExpression(body)
        }
        if (capturePolicy.includeControlFlow && bodyFragment.entryUnitId != null) {
            accumulator.addRelation(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = lambdaScope.id,
                    toUnitId = bodyFragment.entryUnitId,
                ),
            )
        }
        val exits = if (bodyFragment.entryUnitId == null) {
            linkedSetOf(FlowExit(lambdaScope.id))
        } else {
            bodyFragment.exits
        }
        return FlowFragment(
            entryUnitId = lambdaScope.id,
            exits = exits,
            entryElement = lambdaExpression,
        )
    }

    private fun buildIfStatement(statement: PsiIfStatement): FlowFragment {
        val trueFragment = statement.thenBranch?.let(::buildStatementOrExpression) ?: FlowFragment(null, linkedSetOf())
        val falseFragment = statement.elseBranch?.let(::buildStatementOrExpression) ?: FlowFragment(null, linkedSetOf())
        return decisionFragment(
            element = statement,
            title = summarize("if (${statement.condition?.text ?: "condition"})"),
            trueFragment = trueFragment,
            falseFragment = falseFragment,
            guardFragment = executableHeaderFragment(statement.condition, actionKind = "CONDITION"),
        )
    }

    private fun buildSwitchStatement(statement: PsiSwitchStatement): FlowFragment {
        val switchUnit = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = statement,
            title = summarize("switch (${statement.expression?.text ?: "..."})"),
            scopeKind = "SWITCH",
            scopeCategory = FlowScopeCategory.SWITCH,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        val branchFragments = buildSwitchBranches(statement).map { branch ->
            LabeledBranchFragment(
                label = branch.label,
                fragment = sequenceFragments(branch.statements.map(::buildStatement)),
            )
        }
        if (capturePolicy.includeControlFlow) {
            branchFragments.forEach { branch ->
                branch.fragment.entryUnitId?.let { entryUnitId ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = switchUnit.id,
                            toUnitId = entryUnitId,
                            label = branch.label,
                            flowEdgeRole = branch.label.toCaseFlowRole(),
                        ),
                    )
                }
            }
        }
        val exits = linkedSetOf<FlowExit>()
        branchFragments.forEach { branch ->
            if (branch.fragment.entryUnitId == null) {
                exits += FlowExit(switchUnit.id, branch.label, branch.label.toCaseFlowRole())
            }
            exits += branch.fragment.exits
        }
        return FlowFragment(
            entryUnitId = switchUnit.id,
            exits = exits.ifEmpty { linkedSetOf(FlowExit(switchUnit.id, flowEdgeRole = FlowEdgeRole.DEFAULT)) },
            entryElement = statement,
        )
    }

    private fun buildSwitchBranches(statement: PsiSwitchStatement): List<SwitchBranch> {
        val bodyStatements = statement.body?.statements.orEmpty()
        val branches = mutableListOf<SwitchBranch>()
        var currentLabel: String? = null
        var currentStatements = mutableListOf<PsiStatement>()

        fun flushCurrentBranch() {
            val label = currentLabel ?: return
            branches += SwitchBranch(
                label = label,
                statements = currentStatements.toList(),
            )
            currentLabel = null
            currentStatements = mutableListOf()
        }

        bodyStatements.forEach { candidate ->
            if (candidate is PsiSwitchLabelStatementBase) {
                flushCurrentBranch()
                currentLabel = normalizeSwitchBranchLabel(candidate.text)
            } else if (currentLabel != null) {
                currentStatements += candidate
            }
        }
        flushCurrentBranch()
        return branches
    }

    private fun normalizeSwitchBranchLabel(rawLabel: String): String {
        val normalized = rawLabel
            .substringBefore("->")
            .removeSuffix(":")
            .trim()
        return when {
            normalized.equals("default", ignoreCase = true) -> "DEFAULT"
            normalized.startsWith("case ") -> normalized.removePrefix("case ").trim()
            else -> normalized
        }
    }

    private fun hasStructuredNormalExit(condition: PsiExpression?): Boolean {
        if (condition == null) {
            return false
        }
        val constant = JavaPsiFacade.getInstance(method.project)
            .constantEvaluationHelper
            .computeConstantExpression(condition)
        return constant != true
    }

    private fun buildTryStatement(statement: PsiTryStatement): FlowFragment {
        val tryScope = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = statement,
            title = "try",
            scopeKind = "TRY",
            scopeCategory = FlowScopeCategory.TRY,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        val tryFragment = statement.tryBlock?.let(::buildCodeBlock) ?: FlowFragment(null, linkedSetOf())
        if (capturePolicy.includeControlFlow && tryFragment.entryUnitId != null) {
            accumulator.addRelation(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = tryScope.id,
                    toUnitId = tryFragment.entryUnitId,
                    flowEdgeRole = FlowEdgeRole.NORMAL,
                ),
            )
        }
        val catchFragments = if (capturePolicy.includeExceptionPath) {
            statement.catchSections.mapNotNull(::buildCatchSection)
        } else {
            emptyList()
        }
        val finallyFragment = statement.finallyBlock?.let(::buildCodeBlock)
        if (capturePolicy.includeControlFlow) {
            catchFragments.forEach { catchFragment ->
                catchFragment.entryUnitId?.let { entryUnitId ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = tryScope.id,
                            toUnitId = entryUnitId,
                            label = "EXCEPTION",
                            flowEdgeRole = FlowEdgeRole.EXCEPTION,
                        ),
                    )
                }
            }
        }

        val normalFragment = FlowFragment(
            entryUnitId = tryScope.id,
            exits = linkedSetOf<FlowExit>().apply {
                addAll(tryFragment.exits)
                catchFragments.forEach { addAll(it.exits) }
            },
            entryElement = statement,
        )
        return if (finallyFragment == null) {
            normalFragment
        } else {
            sequenceFragments(listOf(normalFragment, finallyFragment))
        }
    }

    private fun buildCatchSection(catchSection: PsiCatchSection): FlowFragment? {
        val block = catchSection.catchBlock ?: return null
        return buildCodeBlock(block)
    }

    private fun buildReturnStatement(statement: PsiReturnStatement): FlowFragment {
        val action = statement.returnValue
            ?.takeIf { expression -> resolveDownstreamTargetsSafely(expression).isNotEmpty() || expression.text != null }
            ?.let { expression -> actionFragment(expression, summarizeExecutable(expression)) }
        val terminal = terminalFragment(statement, "返回", "RETURN")
        return if (action == null || action.entryUnitId == null) {
            terminal
        } else {
            if (capturePolicy.includeControlFlow) {
                action.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = terminal.entryUnitId!!,
                            label = exit.label,
                        ),
                    )
                }
            }
            FlowFragment(
                entryUnitId = action.entryUnitId,
                exits = linkedSetOf(),
                entryElement = action.entryElement,
            )
        }
    }

    private fun buildDeclarationStatement(statement: PsiDeclarationStatement): FlowFragment {
        val fragments = statement.declaredElements.mapNotNull { element ->
            when (element) {
                is PsiVariable -> element.initializer?.let {
                    actionFragment(statement, summarizeExecutable(statement))
                }

                else -> null
            }
        }
        return sequenceFragments(fragments)
    }
}

private class KotlinFlowSemanticBuilder(
    method: PsiMethod,
    ownerMethodUnitId: String,
    accumulator: CodeSemanticAccumulator,
    capturePolicy: SemanticCapturePolicy,
    budgetPolicy: TraversalBudgetPolicy,
) : BaseFlowSemanticBuilder(method, ownerMethodUnitId, accumulator, capturePolicy, budgetPolicy) {
    override fun buildRoots(roots: List<PsiElement>): FlowFragment {
        val fragments = roots.mapNotNull { root ->
            (root as? KtExpression)?.let(::buildExpression)
        }
        return sequenceFragments(fragments)
    }

    private fun buildExpression(expression: KtExpression): FlowFragment {
        return when (expression) {
            is KtBlockExpression -> sequenceFragments(expression.statements.map(::buildExpression))
            is KtIfExpression -> decisionFragment(
                element = expression,
                title = summarize("if (${expression.condition?.text ?: "condition"})"),
                trueFragment = expression.then?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
                falseFragment = expression.`else`?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
                guardFragment = executableHeaderFragment(expression.condition, actionKind = "CONDITION"),
            )

            is KtReturnExpression -> buildReturnExpression(expression)
            is KtThrowExpression -> terminalFragment(
                expression,
                buildThrowTitle(expression.thrownExpression),
                "THROW",
            )
            is KtTryExpression -> buildTryExpression(expression)
            is KtForExpression -> {
                val iterationSourceFragment = executableHeaderFragment(expression.loopRange, actionKind = "ITERATION_SOURCE")
                val loopBody = loopFragment(
                    element = expression,
                    title = summarize("for (${expression.loopParameter?.name ?: "_"} in ${expression.loopRange?.text ?: "items"})"),
                    scopeKind = "FOREACH",
                    bodyFragment = expression.body?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
                    hasStructuredExit = true,
                )
                sequenceFragments(listOfNotNull(iterationSourceFragment, loopBody))
            }

            is KtWhileExpression -> loopFragment(
                element = expression,
                title = summarize("while (${expression.condition?.text ?: "..."})"),
                scopeKind = "WHILE",
                bodyFragment = expression.body?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
                guardFragment = executableHeaderFragment(expression.condition, actionKind = "CONDITION"),
                hasStructuredExit = hasStructuredNormalExit(expression.condition),
            )

            is KtDoWhileExpression -> loopFragment(
                element = expression,
                title = summarize("do-while (${expression.condition?.text ?: "..."})"),
                scopeKind = "DO_WHILE",
                bodyFragment = expression.body?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
                guardFragment = executableHeaderFragment(expression.condition, actionKind = "CONDITION"),
                guardPlacement = LoopGuardPlacement.AFTER_BODY,
                hasStructuredExit = hasStructuredNormalExit(expression.condition),
            )

            is KtWhenExpression -> buildWhenExpression(expression)
            is KtBinaryExpression -> actionFragment(expression, summarizeExecutable(expression))
            is KtLoopExpression -> expression.body?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf())
            else -> actionFragment(expression, summarizeExecutable(expression))
        }
    }

    private fun buildWhenExpression(expression: KtWhenExpression): FlowFragment {
        val whenUnit = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = expression,
            title = summarize("when (${expression.subjectExpression?.text ?: "..."})"),
            scopeKind = "SWITCH",
            scopeCategory = FlowScopeCategory.SWITCH,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        val branchFragments = expression.entries.map { entry ->
            LabeledBranchFragment(
                label = normalizeWhenBranchLabel(entry),
                fragment = entry.expression?.let(::buildExpression) ?: FlowFragment(null, linkedSetOf()),
            )
        }
        if (capturePolicy.includeControlFlow) {
            branchFragments.forEach { branch ->
                branch.fragment.entryUnitId?.let { entryUnitId ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = whenUnit.id,
                            toUnitId = entryUnitId,
                            label = branch.label,
                            flowEdgeRole = branch.label.toCaseFlowRole(),
                        ),
                    )
                }
            }
        }
        val exits = linkedSetOf<FlowExit>()
        branchFragments.forEach { branch ->
            if (branch.fragment.entryUnitId == null) {
                exits += FlowExit(whenUnit.id, branch.label, branch.label.toCaseFlowRole())
            }
            exits += branch.fragment.exits
        }
        return FlowFragment(
            entryUnitId = whenUnit.id,
            exits = exits.ifEmpty { linkedSetOf(FlowExit(whenUnit.id, flowEdgeRole = FlowEdgeRole.DEFAULT)) },
            entryElement = expression,
        )
    }

    private fun normalizeWhenBranchLabel(entry: KtWhenEntry): String {
        val normalized = entry.text
            .substringBefore("->")
            .trim()
        return if (normalized.equals("else", ignoreCase = true)) {
            "DEFAULT"
        } else {
            normalized
        }
    }

    private fun hasStructuredNormalExit(condition: KtExpression?): Boolean {
        val normalized = condition?.unwrapParentheses()?.text
            ?.replace(Regex("\\s+"), "")
            ?: return false
        return normalized != "true"
    }

    private fun buildReturnExpression(expression: KtReturnExpression): FlowFragment {
        val action = expression.returnedExpression?.let(::buildExpression)
        val terminal = terminalFragment(expression, "返回", "RETURN")
        return if (action == null || action.entryUnitId == null) {
            terminal
        } else {
            if (capturePolicy.includeControlFlow) {
                action.exits.forEach { exit ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = exit.unitId,
                            toUnitId = terminal.entryUnitId!!,
                            label = exit.label,
                        ),
                    )
                }
            }
            FlowFragment(
                entryUnitId = action.entryUnitId,
                exits = linkedSetOf(),
                entryElement = action.entryElement,
            )
        }
    }

    private fun buildTryExpression(expression: KtTryExpression): FlowFragment {
        val tryScope = accumulator.addScope(
            ownerSignature = ownerSignature,
            element = expression,
            title = "try",
            scopeKind = "TRY",
            scopeCategory = FlowScopeCategory.TRY,
            ownerMethodUnitId = ownerMethodUnitId,
        )
        val tryFragment = expression.tryBlock.let(::buildExpression)
        if (capturePolicy.includeControlFlow && tryFragment.entryUnitId != null) {
            accumulator.addRelation(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = tryScope.id,
                    toUnitId = tryFragment.entryUnitId,
                    flowEdgeRole = FlowEdgeRole.NORMAL,
                ),
            )
        }
        val catchFragments = if (capturePolicy.includeExceptionPath) {
            expression.catchClauses.mapNotNull { clause -> clause.catchBody?.let(::buildExpression) }
        } else {
            emptyList()
        }
        if (capturePolicy.includeControlFlow) {
            catchFragments.forEach { catchFragment ->
                catchFragment.entryUnitId?.let { entryUnitId ->
                    accumulator.addRelation(
                        SemanticRelation(
                            kind = SemanticRelationKind.CONTROL_FLOW,
                            fromUnitId = tryScope.id,
                            toUnitId = entryUnitId,
                            label = "EXCEPTION",
                            flowEdgeRole = FlowEdgeRole.EXCEPTION,
                        ),
                    )
                }
            }
        }
        val finallyFragment = expression.finallyBlock?.finalExpression?.let(::buildExpression)
        val fragment = FlowFragment(
            entryUnitId = tryScope.id,
            exits = linkedSetOf<FlowExit>().apply {
                addAll(tryFragment.exits)
                catchFragments.forEach { addAll(it.exits) }
            },
            entryElement = expression,
        )
        return if (finallyFragment == null) {
            fragment
        } else {
            sequenceFragments(listOf(fragment, finallyFragment))
        }
    }
}

private class CodeSemanticAccumulator(
    private val handle: CodeSubjectHandle,
) {
    private val units = linkedMapOf<String, SemanticUnit>()
    private val relations = linkedMapOf<String, SemanticRelation>()
    private val sourceMappings = linkedMapOf<String, SourceMapping>()
    private val anchors = linkedMapOf<String, SemanticAnchor>()
    private val diagnostics = mutableListOf<SemanticDiagnostic>()
    private val boundaries = mutableListOf<SemanticBoundary>()

    fun build(): SemanticAnalysisResult {
        return SemanticAnalysisResult(
            subject = handle,
            anchors = anchors.values.toList(),
            semanticUnits = units.values.toList(),
            relations = relations.values.toList(),
            diagnostics = diagnostics.distinct(),
            boundaries = boundaries.distinct(),
            sourceMappings = sourceMappings.values.toList(),
        )
    }

    fun addAnchor(
        targetUnitId: String,
        label: String,
    ) {
        anchors.putIfAbsent(
            targetUnitId,
            SemanticAnchor(
                id = "anchor:${targetUnitId.substringAfter(':')}",
                targetUnitId = targetUnitId,
                label = label,
            ),
        )
    }

    fun addMethod(method: PsiMethod): MethodLikeUnit {
        val signature = methodSignature(method)
        val unit = MethodLikeUnit(
            id = SemanticIdFactory.methodUnitId(signature),
            title = methodDisplayName(method),
            signature = signature,
            doc = methodDocSummary(method),
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, method.navigationElement ?: method)
        return unit
    }

    fun addScope(
        ownerSignature: String,
        element: PsiElement,
        title: String,
        scopeKind: String,
        scopeCategory: FlowScopeCategory? = null,
        incomplete: Boolean = false,
        ownerMethodUnitId: String,
    ): FlowScopeUnit {
        val unit = FlowScopeUnit(
            id = semanticElementId("scope", ownerSignature, element, scopeKind),
            title = title,
            scopeKind = scopeKind,
            scopeCategory = scopeCategory,
            incomplete = incomplete,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    private fun methodDocSummary(method: PsiMethod): String? {
        val raw = method.docComment?.text ?: return null
        return raw
            .removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .map { line -> line.trim().removePrefix("*").trim() }
            .takeWhile { line -> !line.startsWith("@") }
            .filter { line -> line.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
    }

    fun addAction(
        ownerSignature: String,
        element: PsiElement,
        title: String,
        actionKind: String,
        ownerMethodUnitId: String,
    ): FlowActionUnit {
        val unit = FlowActionUnit(
            id = semanticElementId("action", ownerSignature, element, actionKind),
            title = title,
            actionKind = actionKind,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addInvocation(
        ownerSignature: String,
        sourceUnitId: String,
        targetSignature: String,
        title: String,
        element: PsiElement,
        ownerMethodUnitId: String,
    ): InvocationUnit {
        val unit = InvocationUnit(
            id = SemanticIdFactory.compose(
                "invoke",
                "$ownerSignature:$sourceUnitId:$targetSignature:${element.textRange?.startOffset ?: 0}",
            ),
            title = title,
            targetSignature = targetSignature,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addTerminal(
        ownerSignature: String,
        element: PsiElement,
        title: String,
        terminalKind: String,
        ownerMethodUnitId: String,
    ): TerminalUnit {
        val unit = TerminalUnit(
            id = semanticElementId("terminal", ownerSignature, element, terminalKind),
            title = title,
            terminalKind = terminalKind,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addMerge(
        ownerSignature: String,
        element: PsiElement,
        title: String,
        ownerMethodUnitId: String,
    ): MergeUnit {
        val unit = MergeUnit(
            id = semanticElementId("merge", ownerSignature, element, title),
            title = title,
        )
        units.putIfAbsent(unit.id, unit)
        addSourceMapping(unit.id, element)
        addContains(ownerMethodUnitId, unit.id)
        return unit
    }

    fun addRelation(relation: SemanticRelation) {
        val key = listOf(
            relation.kind.name,
            relation.fromUnitId,
            relation.toUnitId,
            relation.label.orEmpty(),
            relation.flowEdgeRole?.name.orEmpty(),
            relation.incomplete.toString(),
            relation.synthetic.toString(),
            relation.provenance.name,
        ).joinToString("|")
        relations.putIfAbsent(key, relation)
    }

    fun addBoundary(boundary: SemanticBoundary) {
        boundaries += boundary
    }

    fun addDiagnostic(diagnostic: SemanticDiagnostic) {
        diagnostics += diagnostic
    }

    fun addResolution(resolution: CodeInvocationSemanticResolution) {
        resolution.semanticUnits.forEach { unit -> units.putIfAbsent(unit.id, unit) }
        resolution.relations.forEach(::addRelation)
        resolution.sourceMappings.forEach { mapping -> sourceMappings.putIfAbsent(mapping.targetUnitId, mapping) }
    }

    private fun addContains(
        fromUnitId: String,
        toUnitId: String,
    ) {
        addRelation(
            SemanticRelation(
                kind = SemanticRelationKind.CONTAINS,
                fromUnitId = fromUnitId,
                toUnitId = toUnitId,
            ),
        )
    }

    private fun addSourceMapping(
        unitId: String,
        element: PsiElement,
    ) {
        val file = element.containingFile ?: return
        val range = element.textRange ?: return
        sourceMappings.putIfAbsent(
            unitId,
            SourceMapping(
                sourcePath = sourcePathOf(file),
                sourceRange = sourceRangeOf(file, normalizeTextRange(range)),
                targetUnitId = unitId,
            ),
        )
    }

    private fun semanticElementId(
        namespace: String,
        ownerSignature: String,
        element: PsiElement,
        discriminator: String,
    ): String {
        val startOffset = element.textRange?.startOffset ?: 0
        return SemanticIdFactory.compose(namespace, "$ownerSignature:$discriminator:$startOffset")
    }

    private fun normalizeTextRange(range: TextRange): TextRange {
        val safeEnd = range.endOffset.coerceAtLeast(range.startOffset)
        return TextRange(range.startOffset, safeEnd)
    }
}

private data class FlowFragment(
    val entryUnitId: String?,
    val exits: LinkedHashSet<FlowExit>,
    val entryElement: PsiElement? = null,
)

private data class FlowExit(
    val unitId: String,
    val label: String? = null,
    val flowEdgeRole: FlowEdgeRole? = null,
)

private data class LabeledBranchFragment(
    val label: String,
    val fragment: FlowFragment,
)

private data class SwitchBranch(
    val label: String,
    val statements: List<PsiStatement>,
)

private fun String.toCaseFlowRole(): FlowEdgeRole {
    return if (this == "DEFAULT") {
        FlowEdgeRole.DEFAULT
    } else {
        FlowEdgeRole.CASE
    }
}

private fun KtExpression.unwrapParentheses(): KtExpression {
    var current: KtExpression = this
    while (current is KtParenthesizedExpression && current.expression != null) {
        current = current.expression!!
    }
    return current
}

private data class FlowBuildResult(
    val discoveredMethods: List<PsiMethod>,
    val boundary: SemanticBoundary? = null,
    val diagnostics: List<SemanticDiagnostic> = emptyList(),
)

private fun summarize(text: String?): String {
    val normalized = normalizedSummaryText(text)
        ?: return "unknown"
    return if (normalized.length <= 96) normalized else normalized.take(93).trimEnd() + "..."
}

private fun summarizeExecutable(element: PsiElement?): String {
    return when (element) {
        is PsiMethodCallExpression -> summarizeJavaMethodCall(element)
        is PsiNewExpression -> summarizeJavaConstructorCall(element)
        is KtQualifiedExpression -> summarizeKotlinQualifiedCall(element) ?: summarize(element.text)
        is KtCallExpression -> summarizeKotlinCall(element)
        else -> summarize(element?.text)
    }
}

private fun buildThrowTitle(expression: PsiElement?): String {
    val thrown = summarizeExecutable(expression)
    return if (thrown == "unknown") {
        "抛出"
    } else {
        "抛出 $thrown"
    }
}

private fun summarizeJavaMethodCall(expression: PsiMethodCallExpression): String {
    val methodName = expression.methodExpression.referenceName
        ?: expression.methodExpression.text.substringAfterLast('.').takeIf { it.isNotBlank() }
        ?: "call"
    val qualifier = compactReceiver(expression.methodExpression.qualifierExpression?.text)
    val callee = listOfNotNull(qualifier, methodName).joinToString(".")
    return "$callee(${compactCallArguments(expression.argumentList.expressions.map { argument -> argument.text })})"
}

private fun summarizeJavaConstructorCall(expression: PsiNewExpression): String {
    val className = expression.classOrAnonymousClassReference
        ?.referenceName
        ?.takeIf { it.isNotBlank() }
        ?: "object"
    val arguments = expression.argumentList?.expressions.orEmpty().map { argument -> argument.text }
    return "new $className(${compactCallArguments(arguments)})"
}

private fun summarizeKotlinQualifiedCall(expression: KtQualifiedExpression): String? {
    val selectorCall = expression.selectorExpression as? KtCallExpression ?: return null
    val receiver = compactReceiver(expression.receiverExpression.text)
    val call = summarizeKotlinCall(selectorCall)
    return listOfNotNull(receiver, call).joinToString(".").takeIf { it.isNotBlank() }
}

private fun summarizeKotlinCall(expression: KtCallExpression): String {
    val callee = normalizedSummaryText(expression.calleeExpression?.text)
        ?: "call"
    val arguments = expression.valueArguments.map { argument ->
        argument.getArgumentExpression()?.text ?: argument.text
    }
    return "$callee(${compactCallArguments(arguments)})"
}

private fun compactCallArguments(arguments: List<String>): String {
    if (arguments.isEmpty()) {
        return ""
    }
    val compactArguments = arguments.map(::compactCallArgument)
    return if (compactArguments.any { argument -> argument == "..." }) {
        "..."
    } else {
        compactArguments.joinToString(", ")
    }
}

private fun compactCallArgument(argument: String?): String {
    val normalized = normalizedSummaryText(argument) ?: return "..."
    return if (isNoisyCallArgument(normalized)) {
        "..."
    } else {
        normalized
    }
}

private fun compactReceiver(receiver: String?): String? {
    val normalized = normalizedSummaryText(receiver) ?: return null
    return if (normalized.length <= 40 && !normalized.any { char -> char == '{' || char == '}' || char == '=' }) {
        normalized
    } else {
        null
    }
}

private fun isNoisyCallArgument(argument: String): Boolean {
    return argument.length > 32 ||
        argument.any { char -> char == '{' || char == '}' || char == '=' || char == ';' }
}

private fun normalizedSummaryText(text: String?): String? {
    return text
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun buildJavaLambdaTitle(
    ownerMethod: PsiMethod?,
    fallbackName: String?,
    lambdaExpression: PsiLambdaExpression,
): String {
    val ownerName = ownerMethod?.name?.takeIf { it.isNotBlank() }
        ?: fallbackName?.takeIf { it.isNotBlank() }
        ?: "lambda"
    val parameterNames = lambdaExpression.parameterList.parameters
        .mapNotNull { parameter -> parameter.name }
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
    return if (parameterNames == null) {
        "$ownerName λ"
    } else {
        "$ownerName λ($parameterNames)"
    }
}
