package com.charmnight.linkgraph.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class QaModeClassifierTest {
    private val classifier = QaModeClassifier()

    @Test
    fun autoClassifiesTriggerQuestionsAsAnswer() {
        assertEquals(
            QaMode.ANSWER,
            classifier.classify(
                requestedMode = QaMode.AUTO,
                question = "这个方法是如何触发的？",
                sourceThreadId = null,
            ),
        )
    }

    @Test
    fun autoClassifiesRiskQuestionsAsReview() {
        assertEquals(
            QaMode.REVIEW,
            classifier.classify(
                requestedMode = QaMode.AUTO,
                question = "这里有没有问题？",
                sourceThreadId = null,
            ),
        )
    }

    @Test
    fun autoPrefersAnswerIntentForExplanationQuestionsThatMentionRisk() {
        assertEquals(
            QaMode.ANSWER,
            classifier.classify(
                requestedMode = QaMode.AUTO,
                question = "请解释为什么这里有风险",
                sourceThreadId = null,
            ),
        )
    }

    @Test
    fun autoClassifiesChangeQuestionsAsChange() {
        assertEquals(
            QaMode.CHANGE,
            classifier.classify(
                requestedMode = QaMode.AUTO,
                question = "请调整这里的判断",
                sourceThreadId = null,
            ),
        )
    }

    @Test
    fun sourceThreadForcesInvestigateWhenAuto() {
        assertEquals(
            QaMode.INVESTIGATE,
            classifier.classify(
                requestedMode = QaMode.AUTO,
                question = "请继续取证",
                sourceThreadId = "thread-risk-1",
            ),
        )
    }

    @Test
    fun explicitModeIsRespected() {
        assertEquals(
            QaMode.ANSWER,
            classifier.classify(
                requestedMode = QaMode.ANSWER,
                question = "请修复这里",
                sourceThreadId = "thread-risk-1",
            ),
        )
    }
}
