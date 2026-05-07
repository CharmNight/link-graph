package com.charmnight.linkgraph.workbench

class QaModeClassifier {
    private val answerKeywords = listOf(
        "如何触发",
        "谁调用",
        "为什么",
        "是什么",
        "解释",
        "介绍",
        "入口在哪",
    )
    private val reviewKeywords = listOf(
        "有没有问题",
        "风险",
        "遗漏",
        "审计",
    )
    private val changeKeywords = listOf(
        "修改",
        "调整",
        "修复",
        "改成",
        "生成草稿",
        "候选变更",
    )

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
        return when {
            answerKeywords.any(normalized::contains) -> QaMode.ANSWER
            reviewKeywords.any(normalized::contains) -> QaMode.REVIEW
            changeKeywords.any(normalized::contains) -> QaMode.CHANGE
            else -> QaMode.AUTO
        }
    }
}
