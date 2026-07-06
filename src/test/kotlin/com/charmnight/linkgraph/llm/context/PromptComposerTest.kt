package com.charmnight.linkgraph.llm.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    @Test
    fun composeMessagesKeepsUserGoalWhenSystemPromptIsLong() {
        val composer = PromptComposer(ContextBudgetController(maxCharacters = 120, maxTokens = 30))

        val composition = composer.composeMessages(
            systemSections = listOf(
                PromptSection("system rule " + "x ".repeat(100), PromptSectionPriority.USER_GOAL),
            ),
            userSections = listOf(
                PromptSection("actual user question", PromptSectionPriority.USER_GOAL),
                PromptSection("schema contract", PromptSectionPriority.SCHEMA),
            ),
        )

        assertTrue(composition.userPrompt.contains("actual user question"))
        assertTrue(composition.userPrompt.contains("schema contract"))
    }

    @Test
    fun lazySectionsWithDifferentRenderersAreNotEqualValueObjects() {
        val first = PromptSection.lazy(priority = PromptSectionPriority.GRAPH) { "first" }
        val second = PromptSection.lazy(priority = PromptSectionPriority.GRAPH) { "second" }

        assertNotEquals(first, second)
    }
}
