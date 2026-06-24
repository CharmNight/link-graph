package com.charmnight.linkgraph.semantic.provider.code

import com.intellij.psi.PsiMethod
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
