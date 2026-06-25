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

/**
 * 流程语义构建器的公共基类：封装了调用片段拼接、动作 / 决策 / 循环 / 终止等通用流程模板，
 * 具体语言的语法遍历由 [JavaFlowSemanticBuilder] / [KotlinFlowSemanticBuilder] 实现。
 */
internal abstract class BaseFlowSemanticBuilder(
    /** 当前正在分析的方法 PSI 节点。 */
    protected val method: PsiMethod,
    /** 归属方法对应的语义单元 ID，所有派生单元都通过 CONTAINS 关系挂到它名下。 */
    protected val ownerMethodUnitId: String,
    /** 累加器，负责生成并去重写入所有语义单元与关系。 */
    protected val accumulator: CodeSemanticAccumulator,
    /** 捕获策略：决定是否生成控制流、调用、异常路径等关系。 */
    protected val capturePolicy: SemanticCapturePolicy,
    /** 预算策略：限制单次分析的展开深度与每单元连接数。 */
    protected val budgetPolicy: TraversalBudgetPolicy,
) {
    /** 当前方法体内直接发现的可下行方法集合，用于驱动下一层 BFS。 */
    protected val discoveredMethods = linkedSetOf<PsiMethod>()
    /** 当前方法的签名缓存，用作派生单元 ID 的命名空间。 */
    protected val ownerSignature: String = methodSignature(method)
    /** 标记是否出现过调用解析失败，便于在结果中加诊断。 */
    private val invocationResolutionIncomplete = AtomicBoolean(false)

    /** 入口方法：解析执行计划，构建根片段并补隐式返回，最终返回新发现的方法与边界。 */
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

    /** 若开启控制流捕获且方法体存在正常出口，则在尾部追加一个隐式 RETURN 终止节点，模拟方法自然结束。 */
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

    /** 子类实现：把根级 PSI 元素列表翻译为一段流程片段。 */
    protected abstract fun buildRoots(roots: List<PsiElement>): FlowFragment

    /**
     * 安全包装的下游目标解析：捕获任何异常并标记为“解析未完成”，避免单点异常导致整次构建失败。
     */
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

    /** 当 [invocationResolutionIncomplete] 被置位时返回一条统一的警告诊断。 */
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

    /**
     * 把若干段顺序片段串成一条流水线：首个片段的入口成为合成片段入口；
     * 当上一段存在多个出口时插入 [MergeUnit] 汇合点，再连向下一段入口。
     */
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

    /**
     * 为一个可执行元素（方法调用、表达式等）创建动作单元；按预算把解析出的下游目标串成调用链，
     * 每个目标生成一个 [InvocationUnit] 并通过 INVOKES 关系指向被调方法。
     */
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

    /** 创建终止节点（return/throw）片段，不带出口，标识流程在此结束。 */
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

    /** 构建条件分支（if）片段：创建 IF 作用域，按 TRUE/FALSE 标签分别连接两个分支入口与出口。 */
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

    /** 循环条件相对循环体的位置：先判断后执行（while/for）或先执行后判断（do-while）。 */
    protected enum class LoopGuardPlacement {
        BEFORE_BODY,
        AFTER_BODY,
    }

    /**
     * 构建循环片段：登记循环作用域，按 [guardPlacement] 串联条件片段、循环体和更新片段，
     * 处理 LOOP_BODY / LOOP_BACK / LOOP_UPDATE / LOOP_EXIT 等边角色。
     */
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

/** Java 语法专用流程构建器：根据 [PsiStatement] 类型分发到对应的语句构建器。 */
