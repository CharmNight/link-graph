package com.charmnight.linkgraph.semantic.provider.code

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtWhenEntry

/**
 * CodeFlowSemanticExtractor 的纯字符串 / 文档解析 helper（P2-1 拆分）。
 *
 * 这些函数无状态、把 Java/Kotlin 源码片段（switch label / when label / javadoc）转换为
 * 规范化文本，与 CodeFlowSemanticExtractor 的 PSI 遍历 + FlowFragment 构造主流程解耦后
 * 便于复用与单独测试。
 */

/**
 * 归一化 Java switch 分支 label：
 * - 去除 "->" / ":" 后缀
 * - "default" → "DEFAULT"
 * - "case X" → "X"
 */
internal fun normalizeSwitchBranchLabel(rawLabel: String): String {
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

/**
 * 归一化 Kotlin when 分支 label：去除 "->" 后缀；"else" → "DEFAULT"。
 */
internal fun normalizeWhenBranchLabel(entry: KtWhenEntry): String {
    val normalized = entry.text
        .substringBefore("->")
        .trim()
    return if (normalized.equals("else", ignoreCase = true)) {
        "DEFAULT"
    } else {
        normalized
    }
}

/**
 * 抽取 PsiMethod 的 javadoc 摘要文本：
 * - 剥离 `/**` / `*/` 边界
 * - 每行去除前导 `*`
 * - 截到首个 `@tag` 之前
 * - 拼接为单行非空字符串；全空时返回 null
 */
internal fun methodDocSummary(method: PsiMethod): String? {
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

/**
 * 判断 Java 循环条件是否非常量 `true`，用于决定是否生成结构化 LOOP_EXIT 边
 * （避免无限循环被画成可退出）。
 *
 * 用 JavaPsiFacade 的常量求值器；条件为 null 返回 false；常量求值为 true 返回 false
 * （意味着是无限循环，不画正常出口）；其他情况返回 true。
 */
internal fun javaHasStructuredNormalExit(condition: PsiExpression?, project: Project): Boolean {
    if (condition == null) {
        return false
    }
    val constant = JavaPsiFacade.getInstance(project)
        .constantEvaluationHelper
        .computeConstantExpression(condition)
    return constant != true
}

/**
 * 判断 Kotlin 循环条件是否常量 `true`，避免无限 while(true) 被画成有正常出口。
 *
 * 比 Java 版简单：直接拿条件文本（去括号、去空白）与 "true" 比较；不依赖 PSI 常量求值器。
 */
internal fun ktHasStructuredNormalExit(condition: KtExpression?): Boolean {
    val normalized = condition?.unwrapParentheses()?.text
        ?.replace(Regex("\\s+"), "")
        ?: return false
    return normalized != "true"
}

/** 反复剥离外层括号，返回最内层的 Kotlin 表达式，便于条件判断等场景统一处理。 */
internal fun KtExpression.unwrapParentheses(): KtExpression {
    var current: KtExpression = this
    while (current is KtParenthesizedExpression && current.expression != null) {
        current = current.expression!!
    }
    return current
}
