package com.charmnight.linkgraph.workbench

/**
 * QA 模式分类器。
 *
 * 当用户请求的 QaMode 是 AUTO 时，按问题文本中的关键词推断实际应使用的模式。
 * 关键词命中按优先级排序（priority 高的先匹配）。
 *
 * 这种分类让用户不需要手动选择模式——只要问题里出现"如何触发""为什么"等关键词，
 * 就自动走 ANSWER 模式；出现"风险""复核"等关键词，就自动走 REVIEW 模式。
 */
class QaModeClassifier {
    /**
     * 模式规则列表，按 priority 降序排列。
     * priority 决定多条规则同时命中时的胜出者。
     */
    private val modeRules = listOf(
        ModeRule(
            mode = QaMode.ANSWER,
            priority = 30,
            keywords = listOf(
                "如何触发",
                "谁调用",
                "为什么",
                "是什么",
                "解释",
                "介绍",
                "入口在哪",
            ),
        ),
        ModeRule(
            mode = QaMode.REVIEW,
            priority = 20,
            keywords = listOf(
                "有没有问题",
                "风险",
                "遗漏",
                "复核",
            ),
        ),
        ModeRule(
            mode = QaMode.CHANGE,
            priority = 10,
            keywords = listOf(
                "修改",
                "调整",
                "修复",
                "改成",
                "生成草稿",
                "候选变更",
            ),
        ),
    ).sortedByDescending(ModeRule::priority)

    /**
     * 单条规则：模式 + 优先级 + 关键词列表。
     */
    private data class ModeRule(
        val mode: QaMode,
        val priority: Int,
        val keywords: List<String>,
    ) {
        /** 问题中包含任一关键词即视为命中。 */
        fun matches(question: String): Boolean = keywords.any(question::contains)
    }

    /**
     * 分类出最终 QA 模式。
     *
     * 决策顺序：
     * 1) 非 AUTO 模式：直接返回用户显式选择的模式；
     * 2) 有来源线程：固定走 INVESTIGATE（继续取证）；
     * 3) 否则按规则匹配；都没命中保留 AUTO（让上游再决定）。
     *
     * @param requestedMode 用户请求的模式
     * @param question 问题文本
     * @param sourceThreadId 来源线程 ID；非空表示是继续取证
     */
    fun classify(
        requestedMode: QaMode,
        question: String,
        sourceThreadId: String?,
    ): QaMode {
        // 显式指定模式优先
        if (requestedMode != QaMode.AUTO) {
            return requestedMode
        }
        // 继续取证线程固定走 INVESTIGATE
        if (!sourceThreadId.isNullOrBlank()) {
            return QaMode.INVESTIGATE
        }
        // 按规则匹配第一条命中的模式
        val normalized = question.trim()
        return modeRules.firstOrNull { rule -> rule.matches(normalized) }?.mode ?: QaMode.AUTO
    }
}
