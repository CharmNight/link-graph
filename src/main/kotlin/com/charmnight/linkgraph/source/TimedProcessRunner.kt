package com.charmnight.linkgraph.source

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 在限定时间和输出预算内等待已启动的子进程。 */
internal object TimedProcessRunner {
    data class Result(
        val exitCode: Int?,
        val timedOut: Boolean,
        val outputLimitExceeded: Boolean,
    )

    fun run(
        process: Process,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Result {
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.use { input -> input.readBytesBounded(maxOutputBytes) }
        }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
            outputFuture.cancel(true)
            return Result(exitCode = null, timedOut = true, outputLimitExceeded = false)
        }
        val output = runCatching { outputFuture.get(1, TimeUnit.SECONDS) }.getOrNull()
        return Result(
            exitCode = process.exitValue(),
            timedOut = false,
            outputLimitExceeded = output == null,
        )
    }
}
