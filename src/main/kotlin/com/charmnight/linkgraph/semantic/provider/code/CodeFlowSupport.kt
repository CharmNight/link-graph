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

/** KtExpression.unwrapParentheses 已抽到 top-level（详见 CodeFlowSemanticExtractorHelpers.kt）。 */

/** 把任意文本规整为流程节点标题：压缩空白、长度超过 96 字符时截断加省略号。 */
internal fun summarize(text: String?): String {
    val normalized = normalizedSummaryText(text)
        ?: return "unknown"
    return if (normalized.length <= 96) normalized else normalized.take(93).trimEnd() + "..."
}

/** 根据 PSI 元素类型（Java/Kotlin 方法调用、构造器等）生成精简的动作标题。 */
internal fun summarizeExecutable(element: PsiElement?): String {
    return when (element) {
        is PsiMethodCallExpression -> summarizeJavaMethodCall(element)
        is PsiNewExpression -> summarizeJavaConstructorCall(element)
        is KtQualifiedExpression -> summarizeKotlinQualifiedCall(element) ?: summarize(element.text)
        is KtCallExpression -> summarizeKotlinCall(element)
        else -> summarize(element?.text)
    }
}

/** 生成 throw 语句的标题，若无法提取被抛对象则退化为通用文案。 */
internal fun buildThrowTitle(expression: PsiElement?): String {
    val thrown = summarizeExecutable(expression)
    return if (thrown == "unknown") {
        "抛出"
    } else {
        "抛出 $thrown"
    }
}

/** 把 Java 方法调用表达式压缩为 `receiver.method(arg, ...)` 形式的标题。 */
internal fun summarizeJavaMethodCall(expression: PsiMethodCallExpression): String {
    val methodName = expression.methodExpression.referenceName
        ?: expression.methodExpression.text.substringAfterLast('.').takeIf { it.isNotBlank() }
        ?: "call"
    val qualifier = compactReceiver(expression.methodExpression.qualifierExpression?.text)
    val callee = listOfNotNull(qualifier, methodName).joinToString(".")
    return "$callee(${compactCallArguments(expression.argumentList.expressions.map { argument -> argument.text })})"
}

/** 把 Java `new X(...)` 表达式压缩为简洁标题，类名缺失时退化为 `object`。 */
internal fun summarizeJavaConstructorCall(expression: PsiNewExpression): String {
    val className = expression.classOrAnonymousClassReference
        ?.referenceName
        ?.takeIf { it.isNotBlank() }
        ?: "object"
    val arguments = expression.argumentList?.expressions.orEmpty().map { argument -> argument.text }
    return "new $className(${compactCallArguments(arguments)})"
}

 /** 把 Kotlin 限定调用（`receiver.selector(...)`）压缩为简洁标题，无法解析时返回 null。 */
internal fun summarizeKotlinQualifiedCall(expression: KtQualifiedExpression): String? {
    val selectorCall = expression.selectorExpression as? KtCallExpression ?: return null
    val receiver = compactReceiver(expression.receiverExpression.text)
    val call = summarizeKotlinCall(selectorCall)
    return listOfNotNull(receiver, call).joinToString(".").takeIf { it.isNotBlank() }
}

/** 把 Kotlin 普通调用表达式压缩为 `callee(arg, ...)` 形式标题。 */
internal fun summarizeKotlinCall(expression: KtCallExpression): String {
    val callee = normalizedSummaryText(expression.calleeExpression?.text)
        ?: "call"
    val arguments = expression.valueArguments.map { argument ->
        argument.getArgumentExpression()?.text ?: argument.text
    }
    return "$callee(${compactCallArguments(arguments)})"
}

/** 把参数列表压缩成展示用字符串，含噪声参数时整体退化为 `...`，避免标题过长。 */
internal fun compactCallArguments(arguments: List<String>): String {
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
internal fun compactCallArgument(argument: String?): String {
    val normalized = normalizedSummaryText(argument) ?: return "..."
    return if (isNoisyCallArgument(normalized)) {
        "..."
    } else {
        normalized
    }
}

/** 压缩 receiver 文本：过长或含结构符号（花括号/等号）时返回 null，避免污染标题。 */
internal fun compactReceiver(receiver: String?): String? {
    val normalized = normalizedSummaryText(receiver) ?: return null
    return if (normalized.length <= 40 && !normalized.any { char -> char == '{' || char == '}' || char == '=' }) {
        normalized
    } else {
        null
    }
}

/** 判断参数文本是否属于"噪声"（超长或含结构符号），用于决定是否省略。 */
internal fun isNoisyCallArgument(argument: String): Boolean {
    return argument.length > 32 ||
        argument.any { char -> char == '{' || char == '}' || char == '=' || char == ';' }
}

/** 把文本中的所有空白（含换行）压缩为单个空格并 trim，返回 null 表示无有效内容。 */
internal fun normalizedSummaryText(text: String?): String? {
    return text
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/** 为 Java lambda 生成展示标题：归属方法名 + λ + 参数列表，缺失信息时合理降级。 */
internal fun buildJavaLambdaTitle(
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
