package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextBudgetControllerTest {
    @Test
    fun trimsSectionsWithinCharacterBudget() {
        val controller = ContextBudgetController(maxCharacters = 10)

        val result = controller.trimSections(listOf("12345", "67890", "extra"))

        assertEquals(listOf("12345", "67890"), result)
    }

    @Test
    fun trimsSectionsWithinTokenBudgetAsWellAsCharacterBudget() {
        val controller = ContextBudgetController(maxCharacters = 1_000, maxTokens = 5)

        val result = controller.trimSections(listOf("alpha beta gamma", "delta epsilon", "zeta"))

        assertEquals(listOf("alpha beta gamma", "delta epsilon"), result)
    }

    @Test
    fun estimatesAsciiWordsNonAsciiAndPunctuation() {
        val controller = ContextBudgetController(maxCharacters = 1_000, maxTokens = 100)

        assertEquals(5, controller.estimateTokens("hello world, 你好!!!"))
    }
}
