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

    @Test
    fun trimKeepsLongestPrefixWithinTokenBudgetForLongMixedText() {
        val controller = ContextBudgetController(maxCharacters = 20_000, maxTokens = 900)
        val text = buildString {
            repeat(1_500) { index ->
                append("alpha_$index, ")
                append("中文")
                append(index)
                append("! ")
            }
        }

        val result = controller.trim(text)

        assertEquals(true, result.length < text.length)
        assertEquals(true, controller.estimateTokens(result) <= 900)
        assertEquals(true, controller.estimateTokens(text.take(result.length + 1)) > 900)
    }
}
