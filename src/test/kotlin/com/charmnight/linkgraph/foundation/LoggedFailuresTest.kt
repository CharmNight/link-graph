package com.charmnight.linkgraph.foundation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LoggedFailuresTest {
    private val logger = Logger.getInstance(LoggedFailuresTest::class.java)

    @Test
    fun orNullRethrowsIdeCancellation() {
        assertFailsWith<ProcessCanceledException> {
            LoggedFailures.orNull(logger, "test cancellation") {
                throw ProcessCanceledException()
            }
        }
    }

    @Test
    fun orDefaultRethrowsIdeCancellation() {
        assertFailsWith<ProcessCanceledException> {
            LoggedFailures.orDefault(logger, "test cancellation", defaultValue = "fallback") {
                throw ProcessCanceledException()
            }
        }
    }
}
