package com.charmnight.linkgraph.llm.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RunBudgetTest {
    @Test
    fun usesDocumentedDefaultLimits() {
        val budget = RunBudget()

        assertEquals(10, budget.maxSteps)
        assertEquals(15, budget.maxFilesRead)
        assertEquals(30, budget.maxSnippets)
        assertEquals(120, budget.maxSnippetLines)
        assertEquals(1_200, budget.maxTotalSnippetLines)
        assertEquals(90, budget.maxRuntimeSeconds)
    }

    @Test
    fun recordsStepFileAndSnippetLineUsage() {
        val budget = RunBudget()
            .recordStep()
            .recordFileRead(snippetLines = 40)
            .recordFileRead(snippetLines = 35)

        assertEquals(1, budget.usedSteps)
        assertEquals(2, budget.filesRead)
        assertEquals(2, budget.snippetsRead)
        assertEquals(75, budget.totalSnippetLinesRead)
    }

    @Test
    fun marksSnippetLineLimitWhenSingleSnippetIsTooLarge() {
        val budget = RunBudget(maxSnippetLines = 10)
            .recordFileRead(snippetLines = 11)

        assertEquals(true, budget.snippetLineLimitExceeded)
    }
}
