package com.charmnight.linkgraph.llm.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptComposerTest {
    @Test
    fun keepsHighPrioritySectionsAheadOfLowPriorityLongSourceUnderTokenBudget() {
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 1_000, maxTokens = 4))

        val prompt = composer.composePrioritized(
            listOf(
                PromptSection("low priority source " + "x ".repeat(50), PromptSectionPriority.SOURCE),
                PromptSection("schema contract", PromptSectionPriority.SCHEMA),
                PromptSection("user goal", PromptSectionPriority.USER_GOAL),
            ),
        )

        assertTrue(prompt.contains("user goal"))
        assertTrue(prompt.contains("schema contract"))
        assertFalse(prompt.contains("low priority source"))
    }
}
