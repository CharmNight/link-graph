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

internal class JavaFlowSemanticBuilder(
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
    /** Java hasStructuredNormalExit：详见 top-level fun javaHasStructuredNormalExit。 */
    private fun hasStructuredNormalExit(condition: PsiExpression?): Boolean =
        com.charmnight.linkgraph.semantic.provider.code.javaHasStructuredNormalExit(condition, method.project)

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
