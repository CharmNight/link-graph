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

/** BaseFlowSemanticBuilder / JavaFlowSemanticBuilder / KotlinFlowSemanticBuilder /
 *  CodeFlowSupport（summarize 系列函数）已拆到独立文件（同包 internal 可见）。 */
