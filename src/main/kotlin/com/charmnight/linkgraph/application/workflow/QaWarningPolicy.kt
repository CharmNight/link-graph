package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.workbench.QaMode

/**
 * 负责按 QA 模式过滤结果警告。
 *
 * 当前仍保持前端 `warnings: List<String>` 契约，并使用稳定分类前缀做模式边界。
 */
internal class QaWarningPolicy {
    fun filterForMode(
        warnings: List<String>,
        effectiveMode: QaMode,
    ): List<String> {
        if (effectiveMode != QaMode.ANSWER) {
            return warnings.map(::displayText)
        }
        return warnings
            .filter(::isRuntimeWarning)
            .map(::displayText)
    }

    private fun isRuntimeWarning(warning: String): Boolean {
        return when (categoryOf(warning)) {
            WarningCategory.RUNTIME -> true
            WarningCategory.BUSINESS -> false
            null -> false
        }
    }

    private fun categoryOf(warning: String): WarningCategory? {
        val prefix = warning.substringBefore(":", missingDelimiterValue = "").trim().uppercase()
        return WarningCategory.entries.firstOrNull { category -> category.name == prefix }
    }

    private fun displayText(warning: String): String {
        return when (categoryOf(warning)) {
            null -> warning
            else -> warning.substringAfter(":", missingDelimiterValue = warning).trim()
        }
    }

    private enum class WarningCategory {
        RUNTIME,
        BUSINESS,
    }
}
