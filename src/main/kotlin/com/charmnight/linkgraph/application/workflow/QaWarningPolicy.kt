package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.workbench.QaMode

/**
 * 负责按 QA 模式过滤结果警告。
 *
 * 不同 QA 模式对警告的关注度不同：
 * - ANSWER 模式只关心运行时类警告（业务类警告在纯回答模式下不重要）；
 * - 其他模式（REVIEW/CHANGE/INVESTIGATE）保留所有警告。
 *
 * 当前仍保持前端 `warnings: List<String>` 契约，并使用稳定分类前缀做模式边界。
 * 警告字符串格式为 "CATEGORY:实际文案"，便于按前缀分类。
 */
internal class QaWarningPolicy {
    /**
     * 按模式过滤并清洗警告。
     *
     * @param warnings 原始警告列表
     * @param effectiveMode 当前 QA 模式
     * @return 过滤后的、人类可读的警告列表
     */
    fun filterForMode(
        warnings: List<String>,
        effectiveMode: QaMode,
    ): List<String> {
        // 非 ANSWER 模式：所有警告都保留，但去掉前缀只展示文案
        if (effectiveMode != QaMode.ANSWER) {
            return warnings.map(::displayText)
        }
        // ANSWER 模式：只保留 RUNTIME 类警告
        return warnings
            .filter(::isRuntimeWarning)
            .map(::displayText)
    }

    /** 判断给定警告是否属于 RUNTIME 类。 */
    private fun isRuntimeWarning(warning: String): Boolean {
        return when (categoryOf(warning)) {
            WarningCategory.RUNTIME -> true
            WarningCategory.BUSINESS -> false
            null -> false
        }
    }

    /**
     * 解析警告的分类前缀。
     * 警告格式约定为 "CATEGORY:文案"，无前缀返回 null。
     */
    private fun categoryOf(warning: String): WarningCategory? {
        val prefix = warning.substringBefore(":", missingDelimiterValue = "").trim().uppercase()
        return WarningCategory.entries.firstOrNull { category -> category.name == prefix }
    }

    /**
     * 提取警告的人类可读文案：去掉分类前缀只保留正文。
     * 无前缀的警告原样返回。
     */
    private fun displayText(warning: String): String {
        return when (categoryOf(warning)) {
            null -> warning
            else -> warning.substringAfter(":", missingDelimiterValue = warning).trim()
        }
    }

    /** 警告分类枚举。 */
    private enum class WarningCategory {
        /** 运行时类警告：与执行结果直接相关，ANSWER 模式也保留。 */
        RUNTIME,

        /** 业务类警告：与业务规则相关，ANSWER 模式过滤掉。 */
        BUSINESS,
    }
}
