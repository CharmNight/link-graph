package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.workbench.QaMode

/**
 * 负责按 QA 模式过滤结果警告。
 *
 * 当前仍保持前端 `warnings: List<String>` 契约。新 warning 使用稳定分类前缀；
 * 旧 warning 在迁移期继续通过保守关键词兼容。
 */
internal class AuditWarningPolicy {
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
            null -> isLegacyRuntimeWarning(warning)
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

    private fun isLegacyRuntimeWarning(warning: String): Boolean {
        val normalized = warning.trim()
        return legacyRuntimeWarningKeywords.any(normalized::contains)
    }

    private enum class WarningCategory {
        RUNTIME,
        BUSINESS,
    }

    private companion object {
        val legacyRuntimeWarningKeywords = listOf(
            "远程 LLM",
            "回退",
            "重试",
            "请求地址",
            "API 密钥",
            "模型",
            "配置",
            "超时",
            "JSON 修复",
            "连接",
        )
    }
}
