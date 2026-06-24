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
 * 代码流语义提取器：把一个 [CodeSubjectHandle] 指向的方法体拆解成控制流图（分支/循环/异常），
 * 再在图上叠加方法调用、资源访问等关系，最终产出供前端渲染的语义分析结果。
 *
 * 内部使用双向 BFS：向下展开被调用的方法，向上反查调用方，配合预算策略控制规模。
 */
class CodeFlowSemanticExtractor(
    /** 方法调用语义解析器，用于补全调用与资源/事件等附加关系。 */
    private val invocationResolver: CodeInvocationSemanticResolver = CodeInvocationSemanticResolver(),
    /** 架构图索引的懒加载提供者，跨源关联时使用；为空表示不参与跨源扩展。 */
    private val architectureIndexProvider: (() -> ArchitectureGraphIndex?)? = null,
) {
    /**
     * 在读锁中执行：先索引主体方法，再通过 [JavaFlowSemanticBuilder] 或 [KotlinFlowSemanticBuilder]
     * 解析方法体；按预算沿下游（被调用方）和上游（调用方）展开，最终汇总为 [SemanticAnalysisResult]。
     */
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

    /** 一次遍历任务：携带目标方法与递归深度，用于 BFS 队列。 */
    private data class TraversalTask(
        val method: PsiMethod,
        val depth: Int,
    )
}

/**
 * 流程语义构建器的公共基类：封装了调用片段拼接、动作 / 决策 / 循环 / 终止等通用流程模板，
 * 具体语言的语法遍历由 [JavaFlowSemanticBuilder] / [KotlinFlowSemanticBuilder] 实现。
 */
private abstract class BaseFlowSemanticBuilder(
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
private class JavaFlowSemanticBuilder(
    method: PsiMethod,
    ownerMethodUnitId: String,
    accumulator: CodeSemanticAccumulator,
    capturePolicy: SemanticCapturePolicy,
    budgetPolicy: TraversalBudgetPolicy,
) : BaseFlowSemanticBuilder(method, ownerMethodUnitId, accumulator, capturePolicy, budgetPolicy) {
    /** Java 入口：根据元素类型分派给代码块 / 语句 / 表达式构建器，再串成一条流水线。 */
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

    /** 处理 Java 代码块：把每条语句依次构建并串联。 */
    private fun buildCodeBlock(block: PsiCodeBlock): FlowFragment {
        return sequenceFragments(block.statements.map(::buildStatement))
    }

    /** 按语句类型分发：块、if、各类循环、switch、try、return、throw、表达式、声明等。 */
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

    /** 处理表达式语句：若参数中含 lambda，则连带 lambda 体一起构建；否则作为普通动作。 */
    private fun buildExpressionStatement(statement: PsiExpressionStatement): FlowFragment {
        val methodCall = statement.expression as? PsiMethodCallExpression
        return if (methodCall != null && methodCall.argumentList.expressions.any { argument -> argument is PsiLambdaExpression }) {
            buildMethodCallWithLambdaBodies(methodCall)
        } else {
            actionFragment(statement.expression, summarizeExecutable(statement.expression))
        }
    }

    /** 对可能是语句或表达式的元素统一适配，分别走 [buildStatement] 或 [actionFragment]。 */
    private fun buildStatementOrExpression(element: PsiElement): FlowFragment {
        return when (element) {
            is PsiStatement -> buildStatement(element)
            is PsiExpression -> actionFragment(element, summarizeExecutable(element))
            else -> FlowFragment(null, linkedSetOf())
        }
    }

    /** 处理含 lambda 参数的方法调用：先创建动作，再为每个 lambda 参数单独生成作用域与流程。 */
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

    /** 为单个 lambda 参数建立 LAMBDA 作用域并把 lambda 体作为子流程挂上去。 */
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

    /** 构建 if 语句：解析 then/else 分支并交给通用 [decisionFragment]。 */
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

    /** 构建 switch 语句：登记 SWITCH 作用域后，把各 case 标签的语句归并成独立分支。 */
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

    /** 扫描 switch body，按 case/default 标签切分成多个 [SwitchBranch]，保留每个分支内的语句顺序。 */
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

    /** 把 switch 分支原始文本（`case X:`、`default ->` 等）归一化为统一的标签字符串。 */
    private fun normalizeSwitchBranchLabel(rawLabel: String): String =
        com.charmnight.linkgraph.semantic.provider.code.normalizeSwitchBranchLabel(rawLabel)

    /** 判断循环条件是否非常量 `true`，用于决定是否生成结构化 LOOP_EXIT 边（避免无限循环被画成可退出）。 */
    private fun hasStructuredNormalExit(condition: PsiExpression?): Boolean {
        if (condition == null) {
            return false
        }
        val constant = JavaPsiFacade.getInstance(method.project)
            .constantEvaluationHelper
            .computeConstantExpression(condition)
        return constant != true
    }

    /** 构建 try/catch/finally：登记 TRY 作用域，try 块为正常分支，每个 catch 以 EXCEPTION 边接入。 */
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

    /** 构建单个 catch 块的流程，作为异常路径分支入口。 */
    private fun buildCatchSection(catchSection: PsiCatchSection): FlowFragment? {
        val block = catchSection.catchBlock ?: return null
        return buildCodeBlock(block)
    }

    /** 构建 return 语句：若有返回值表达式则先构建动作，再连接到 RETURN 终止节点。 */
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

    /** 构建变量声明语句：仅有初始化表达式时才产生动作片段。 */
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

/** Kotlin 语法专用流程构建器：基于 [KtExpression] 类型递归构建控制流。 */
private class KotlinFlowSemanticBuilder(
    method: PsiMethod,
    ownerMethodUnitId: String,
    accumulator: CodeSemanticAccumulator,
    capturePolicy: SemanticCapturePolicy,
    budgetPolicy: TraversalBudgetPolicy,
) : BaseFlowSemanticBuilder(method, ownerMethodUnitId, accumulator, capturePolicy, budgetPolicy) {
    /** Kotlin 入口：仅接受 [KtExpression]，按表达式类型分发构建。 */
    override fun buildRoots(roots: List<PsiElement>): FlowFragment {
        val fragments = roots.mapNotNull { root ->
            (root as? KtExpression)?.let(::buildExpression)
        }
        return sequenceFragments(fragments)
    }

    /** Kotlin 表达式分发：if、return、throw、try、for、while、do-while、when、二元、循环等。 */
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

    /** 构建 Kotlin when 表达式：登记 SWITCH 作用域，把每个 entry 当作带标签分支。 */
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

    /** 把 when entry 的条件文本归一化为分支标签，`else` 转为 `DEFAULT`。 */
    private fun normalizeWhenBranchLabel(entry: KtWhenEntry): String =
        com.charmnight.linkgraph.semantic.provider.code.normalizeWhenBranchLabel(entry)

    /** 判断 Kotlin 循环条件是否常量 `true`，避免无限 while(true) 被画成有正常出口。 */
    private fun hasStructuredNormalExit(condition: KtExpression?): Boolean {
        val normalized = condition?.unwrapParentheses()?.text
            ?.replace(Regex("\\s+"), "")
            ?: return false
        return normalized != "true"
    }

    /** 构建 Kotlin return 表达式：与 Java 版本类似，先构建返回值动作再连到 RETURN 终止。 */
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

    /** 构建 Kotlin try 表达式：登记 TRY 作用域，try 块作为正常入口，catch 子句以 EXCEPTION 边接入。 */
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

/**
 * 语义累加器：在单次分析过程中收集所有单元、关系、源码映射、锚点、诊断和边界，
 * 内部维护插入顺序并对相同 key 去重，最终由 [build] 输出不可变结果。
 */
private class CodeSemanticAccumulator(
    /** 当前分析的主体句柄。 */
    private val handle: CodeSubjectHandle,
) {
    private val units = linkedMapOf<String, SemanticUnit>()
    private val relations = linkedMapOf<String, SemanticRelation>()
    private val sourceMappings = linkedMapOf<String, SourceMapping>()
    private val anchors = linkedMapOf<String, SemanticAnchor>()
    private val diagnostics = mutableListOf<SemanticDiagnostic>()
    private val boundaries = mutableListOf<SemanticBoundary>()

    /** 收尾：把所有内部集合组装成不可变 [SemanticAnalysisResult]，并对诊断/边界做去重。 */
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

    /** 为指定单元登记一个锚点（前端用于高亮"当前主体"等标记）。 */
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

 /** 把方法 PSI 登记为 [MethodLikeUnit]，附带文档摘要与源码映射。 */
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

    /** 登记一个流程作用域（IF/SWITCH/TRY/LOOP/LAMBDA 等），自动挂上 CONTAINS 关系。 */
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

    /** 抽取方法 KDoc/Javadoc 描述段（截止到首个 `@` 标签前）拼接为单行摘要。 */
    private fun methodDocSummary(method: PsiMethod): String? =
        com.charmnight.linkgraph.semantic.provider.code.methodDocSummary(method)

    /** 登记一个动作单元（赋值、调用等），自动挂上 CONTAINS 关系与源码映射。 */
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

    /** 登记一个调用单元（带源单元与目标签名），用于在图中显式画出"在哪一步调用了谁"。 */
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

    /** 登记终止节点（return/throw），不带后续出口。 */
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

    /** 登记汇合节点（多个出口汇聚到一处后再继续），常用于 [sequenceFragments] 中的多路合并。 */
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

    /** 用复合 key 对关系去重后写入；key 包含 kind、两端单元、标签、边角色等关键字段。 */
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

    /** 追加一条流程边界（如方法入口/出口的边界描述）。 */
    fun addBoundary(boundary: SemanticBoundary) {
        boundaries += boundary
    }

    /** 追加一条诊断信息（警告/错误），最终在结果中按整体去重。 */
    fun addDiagnostic(diagnostic: SemanticDiagnostic) {
        diagnostics += diagnostic
    }

    /** 把调用解析器产出的附加单元/关系/源码映射合并进当前累加器。 */
    fun addResolution(resolution: CodeInvocationSemanticResolution) {
        resolution.semanticUnits.forEach { unit -> units.putIfAbsent(unit.id, unit) }
        resolution.relations.forEach(::addRelation)
        resolution.sourceMappings.forEach { mapping -> sourceMappings.putIfAbsent(mapping.targetUnitId, mapping) }
    }

    /** 添加 CONTAINS 关系：方法单元包含其下属的语义单元，形成层级结构。 */
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

    /** 把语义单元与源码位置（文件 + 文本区间）建立映射，前端用于点击跳转。 */
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

    /** 拼接语义元素的稳定 ID：命名空间 + 归属签名 + 判别符 + PSI 起始偏移。 */
    private fun semanticElementId(
        namespace: String,
        ownerSignature: String,
        element: PsiElement,
        discriminator: String,
    ): String {
        val startOffset = element.textRange?.startOffset ?: 0
        return SemanticIdFactory.compose(namespace, "$ownerSignature:$discriminator:$startOffset")
    }

    /** 修正可能的反转区间，保证 endOffset >= startOffset，避免 PSI 异常区间导致渲染崩溃。 */
    private fun normalizeTextRange(range: TextRange): TextRange {
        val safeEnd = range.endOffset.coerceAtLeast(range.startOffset)
        return TextRange(range.startOffset, safeEnd)
    }
}

/** 流程构建中的中间片段：入口单元、出口集合以及触发该片段的 PSI 元素。 */
private data class FlowFragment(
    val entryUnitId: String?,
    val exits: LinkedHashSet<FlowExit>,
    val entryElement: PsiElement? = null,
)

/** 流程出口：携带出口单元 ID 与可选的标签/边角色（TRUE/FALSE/EXCEPTION 等）。 */
private data class FlowExit(
    val unitId: String,
    val label: String? = null,
    val flowEdgeRole: FlowEdgeRole? = null,
)

/** 带标签的分支片段，用于 switch/when 等多路分支的中间表示。 */
private data class LabeledBranchFragment(
    val label: String,
    val fragment: FlowFragment,
)

/** switch 分支的中间结构：标签 + 该分支下的语句列表。 */
private data class SwitchBranch(
    val label: String,
    val statements: List<PsiStatement>,
)

/** 把分支标签转换为对应的流程边角色：DEFAULT 标签 → DEFAULT 边，其它 → CASE 边。 */
private fun String.toCaseFlowRole(): FlowEdgeRole {
    return if (this == "DEFAULT") {
        FlowEdgeRole.DEFAULT
    } else {
        FlowEdgeRole.CASE
    }
}

/** 反复剥离外层括号，返回最内层的 Kotlin 表达式，便于条件判断等场景统一处理。 */
private fun KtExpression.unwrapParentheses(): KtExpression {
    var current: KtExpression = this
    while (current is KtParenthesizedExpression && current.expression != null) {
        current = current.expression!!
    }
    return current
}

/** 单个方法的构建产物：发现的可下行方法列表、可选边界与诊断。 */
private data class FlowBuildResult(
    val discoveredMethods: List<PsiMethod>,
    val boundary: SemanticBoundary? = null,
    val diagnostics: List<SemanticDiagnostic> = emptyList(),
)

/** 把任意文本规整为流程节点标题：压缩空白、长度超过 96 字符时截断加省略号。 */
private fun summarize(text: String?): String {
    val normalized = normalizedSummaryText(text)
        ?: return "unknown"
    return if (normalized.length <= 96) normalized else normalized.take(93).trimEnd() + "..."
}

/** 根据 PSI 元素类型（Java/Kotlin 方法调用、构造器等）生成精简的动作标题。 */
private fun summarizeExecutable(element: PsiElement?): String {
    return when (element) {
        is PsiMethodCallExpression -> summarizeJavaMethodCall(element)
        is PsiNewExpression -> summarizeJavaConstructorCall(element)
        is KtQualifiedExpression -> summarizeKotlinQualifiedCall(element) ?: summarize(element.text)
        is KtCallExpression -> summarizeKotlinCall(element)
        else -> summarize(element?.text)
    }
}

/** 生成 throw 语句的标题，若无法提取被抛对象则退化为通用文案。 */
private fun buildThrowTitle(expression: PsiElement?): String {
    val thrown = summarizeExecutable(expression)
    return if (thrown == "unknown") {
        "抛出"
    } else {
        "抛出 $thrown"
    }
}

/** 把 Java 方法调用表达式压缩为 `receiver.method(arg, ...)` 形式的标题。 */
private fun summarizeJavaMethodCall(expression: PsiMethodCallExpression): String {
    val methodName = expression.methodExpression.referenceName
        ?: expression.methodExpression.text.substringAfterLast('.').takeIf { it.isNotBlank() }
        ?: "call"
    val qualifier = compactReceiver(expression.methodExpression.qualifierExpression?.text)
    val callee = listOfNotNull(qualifier, methodName).joinToString(".")
    return "$callee(${compactCallArguments(expression.argumentList.expressions.map { argument -> argument.text })})"
}

/** 把 Java `new X(...)` 表达式压缩为简洁标题，类名缺失时退化为 `object`。 */
private fun summarizeJavaConstructorCall(expression: PsiNewExpression): String {
    val className = expression.classOrAnonymousClassReference
        ?.referenceName
        ?.takeIf { it.isNotBlank() }
        ?: "object"
    val arguments = expression.argumentList?.expressions.orEmpty().map { argument -> argument.text }
    return "new $className(${compactCallArguments(arguments)})"
}

 /** 把 Kotlin 限定调用（`receiver.selector(...)`）压缩为简洁标题，无法解析时返回 null。 */
private fun summarizeKotlinQualifiedCall(expression: KtQualifiedExpression): String? {
    val selectorCall = expression.selectorExpression as? KtCallExpression ?: return null
    val receiver = compactReceiver(expression.receiverExpression.text)
    val call = summarizeKotlinCall(selectorCall)
    return listOfNotNull(receiver, call).joinToString(".").takeIf { it.isNotBlank() }
}

/** 把 Kotlin 普通调用表达式压缩为 `callee(arg, ...)` 形式标题。 */
private fun summarizeKotlinCall(expression: KtCallExpression): String {
    val callee = normalizedSummaryText(expression.calleeExpression?.text)
        ?: "call"
    val arguments = expression.valueArguments.map { argument ->
        argument.getArgumentExpression()?.text ?: argument.text
    }
    return "$callee(${compactCallArguments(arguments)})"
}

/** 把参数列表压缩成展示用字符串，含噪声参数时整体退化为 `...`，避免标题过长。 */
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

/** 压缩单个参数：含大括号/分号/等号或超长时退化为 `...`，否则保留规整文本。 */
private fun compactCallArgument(argument: String?): String {
    val normalized = normalizedSummaryText(argument) ?: return "..."
    return if (isNoisyCallArgument(normalized)) {
        "..."
    } else {
        normalized
    }
}

/** 压缩 receiver 文本：过长或含结构符号（花括号/等号）时返回 null，避免污染标题。 */
private fun compactReceiver(receiver: String?): String? {
    val normalized = normalizedSummaryText(receiver) ?: return null
    return if (normalized.length <= 40 && !normalized.any { char -> char == '{' || char == '}' || char == '=' }) {
        normalized
    } else {
        null
    }
}

/** 判断参数文本是否属于"噪声"（超长或含结构符号），用于决定是否省略。 */
private fun isNoisyCallArgument(argument: String): Boolean {
    return argument.length > 32 ||
        argument.any { char -> char == '{' || char == '}' || char == '=' || char == ';' }
}

/** 把文本中的所有空白（含换行）压缩为单个空格并 trim，返回 null 表示无有效内容。 */
private fun normalizedSummaryText(text: String?): String? {
    return text
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/** 为 Java lambda 生成展示标题：归属方法名 + λ + 参数列表，缺失信息时合理降级。 */
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
