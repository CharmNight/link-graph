package com.charmnight.linkgraph.llm.context

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextBudgetControllerTest {
    @Test
    fun trimsSectionsWithinCharacterBudget() {
        val controller = ContextBudgetController(maxCharacters = 10)

        val result = controller.trimSections(listOf("12345", "67890", "extra"))

        assertEquals(listOf("12345", "67890"), result)
    }
}
