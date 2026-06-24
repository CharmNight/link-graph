package com.charmnight.linkgraph.foundation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * "吞掉异常并降级"代码路径的集中化工具。
 *
 * 替代 `runCatching { ... }.getOrNull()` 的静默写法：每次失败都打 WARN 日志，
 * 让降级行为可观察；同时把 ProcessCanceledException 与 CancellationException 重新抛出，
 * 避免 IDE 取消操作被误当成可恢复失败。
 *
 * 不直接抛出错误信息（仅日志），所以这些函数适合用于"失败就降级"的场景，
 * 不适合必须严格成功的关键路径。
 */
object LoggedFailures {
    /**
     * 执行 [block]，成功返回结果，失败返回 null。
     * 失败时打 WARN 日志，前缀为 [context] 便于定位。
     *
     * @param logger 日志器
     * @param context 上下文描述，会拼接到日志前
     * @param block 待执行代码块
     */
    inline fun <T> orNull(
        logger: Logger,
        context: String,
        block: () -> T?,
    ): T? {
        return try {
            block()
        } catch (e: ProcessCanceledException) {
            // IDE 取消必须重新抛出，让上层处理
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消同样重新抛出
            throw e
        } catch (e: Throwable) {
            logger.warn("$context failed: ${e::class.java.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 执行 [block]，成功返回结果，失败返回 [defaultValue]。
     * 失败时打 WARN 日志，前缀为 [context] 便于定位。
     *
     * @param logger 日志器
     * @param context 上下文描述
     * @param defaultValue 失败时的回退值
     * @param block 待执行代码块
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
