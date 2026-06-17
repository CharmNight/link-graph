package com.charmnight.linkgraph.foundation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Centralized helpers for "swallow and degrade" code paths that previously used
 * `runCatching { ... }.getOrNull()` silently. Every failure is logged so the
 * degradation is observable; cancellation exceptions are always rethrown so IDE
 * cancellation cannot be mistaken for a recoverable failure.
 */
object LoggedFailures {
    /**
     * Runs [block] and returns its result, or `null` on failure. The failure is
     * logged at WARN level with [context] prepended so it can be traced.
     */
    inline fun <T> orNull(
        logger: Logger,
        context: String,
        block: () -> T?,
    ): T? {
        return try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("$context failed: ${e::class.java.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Runs [block] and returns its result, or [defaultValue] on failure. The
     * failure is logged at WARN level with [context] prepended.
     */
    inline fun <T> orDefault(
        logger: Logger,
        context: String,
        defaultValue: T,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("$context failed: ${e::class.java.simpleName}: ${e.message}")
            defaultValue
        }
    }
}
