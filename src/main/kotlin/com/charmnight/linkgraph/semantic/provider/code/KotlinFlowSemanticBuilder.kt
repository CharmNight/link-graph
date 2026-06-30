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

internal class KotlinFlowSemanticBuilder(
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
    /** Kotlin hasStructuredNormalExit：详见 top-level fun ktHasStructuredNormalExit。 */
    private fun hasStructuredNormalExit(condition: KtExpression?): Boolean =
        com.charmnight.linkgraph.semantic.provider.code.ktHasStructuredNormalExit(condition)

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

/** 流程模型 CodeSemanticAccumulator / FlowFragment / FlowExit / LabeledBranchFragment / SwitchBranch /
 *  FlowBuildResult / toCaseFlowRole 已抽到 CodeFlowModels.kt（internal，同包可见）。 */

/** KtExpression.unwrapParentheses 已抽到 top-level（详见 CodeFlowSemanticExtractorHelpers.kt）。 */

/** 把任意文本规整为流程节点标题：压缩空白、长度超过 96 字符时截断加省略号。 */
