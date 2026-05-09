package com.charmnight.linkgraph.workbench

class QaModeClassifier {
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
                "审计",
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

    private data class ModeRule(
        val mode: QaMode,
        val priority: Int,
        val keywords: List<String>,
    ) {
        fun matches(question: String): Boolean = keywords.any(question::contains)
    }

    fun classify(
        requestedMode: QaMode,
        question: String,
        sourceThreadId: String?,
    ): QaMode {
        if (requestedMode != QaMode.AUTO) {
            return requestedMode
        }
        if (!sourceThreadId.isNullOrBlank()) {
            return QaMode.INVESTIGATE
        }
        val normalized = question.trim()
        return modeRules.firstOrNull { rule -> rule.matches(normalized) }?.mode ?: QaMode.AUTO
    }
}
