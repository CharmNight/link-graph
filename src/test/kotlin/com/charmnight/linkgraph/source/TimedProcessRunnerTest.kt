package com.charmnight.linkgraph.source

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimedProcessRunnerTest {
    @Test
    fun forciblyDestroysProcessAfterTimeout() {
        val process = NeverEndingProcess()

        val result = TimedProcessRunner.run(
            process = process,
            timeoutMillis = 1,
            maxOutputBytes = 1024,
        )

        assertTrue(result.timedOut)
        assertTrue(process.destroyForciblyCalled)
        assertNull(result.exitCode)
    }

    private class NeverEndingProcess : Process() {
        var destroyForciblyCalled: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyForciblyCalled

        override fun exitValue(): Int = if (destroyForciblyCalled) 137 else error("process still running")

        override fun destroy() = Unit

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            return this
        }

        override fun isAlive(): Boolean = !destroyForciblyCalled
    }
}
