package com.charmnight.linkgraph.application.request

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * IntelliJ 线程模型适配器（P2-1 真正的架构分解）。
 *
 * 从 AsyncRequestLifecycleSupport 抽出的独立 class，封装 IntelliJ 平台的
 * 线程调度、超时安排和流式预览节流——这些是与 IntelliJ API 紧耦合的基础设施，
 * 与异步请求的"生命周期状态管理"是完全不同的关注点。
 *
 * 职责：
 * - 在后台线程池执行耗时任务，完成后回到 EDT 回调（[runBackgroundTask]）
 * - 在后台读线程上以 ReadAction 同步执行（[computeOnBackgroundReadThread]）
 * - 安排异步请求超时回调（[scheduleAsyncRequestTimeout]）
 * - 构造节流后的流式预览回写器（[createStreamingPreviewUpdater]）
 * - 条件记录异步请求状态日志（[logAsyncRequestEvent] / [logRuntimeTrace]）
 * - 把 runtime 状态追加到请求详情（[withRuntimeMetadata]）
 *
 * @param project 当前 IntelliJ 项目（用于判断 isDisposed）
 */
internal class AsyncTaskDispatcher(
    private val project: Project,
) {
    /**
     * 在后台线程池执行耗时任务，完成后回到指定 modality 状态派发回调结果。
     */
    fun <T> runBackgroundTask(
        work: () -> T,
        onCompleted: (Result<T>) -> Unit,
        modalityState: ModalityState = ModalityState.defaultModalityState(),
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater(
                { if (!project.isDisposed) onCompleted(result) },
                modalityState,
            )
        }
    }

    /**
     * 在后台读线程上以 ReadAction 同步执行计算并等待返回。
     */
    fun <T> computeOnBackgroundReadThread(action: () -> T): T {
        val future = AppExecutorUtil.getAppExecutorService().submit<T> {
            ReadAction.compute<T, RuntimeException>(action)
        }
        return try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    /**
     * 安排异步请求超时回调：超时后在 EDT 上检查请求是否仍然活跃，
     * 如果是则调用 onTimeout。
     */
    fun scheduleAsyncRequestTimeout(
        requestId: Long,
        timeoutMillis: Long,
        completeRequest: (Long) -> Boolean,
        onTimeout: () -> Unit,
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (project.isDisposed || !completeRequest(requestId)) {
                            return@invokeLater
                        }
                        onTimeout()
                    },
                    ModalityState.defaultModalityState(),
                )
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 构造节流后的流式预览回写器，避免每个 token 都触发整页同步。
     */
    fun createStreamingPreviewUpdater(
        requestId: Long,
        updatePreview: (Long, String, Boolean) -> Unit,
    ): (String, Boolean) -> Unit {
        var lastPublishedAt = 0L
        var lastPublishedText = ""
        return fun(previewText: String, finalizing: Boolean) {
            if (project.isDisposed) return
            val now = System.currentTimeMillis()
            if (!finalizing && previewText == lastPublishedText) return
            if (!finalizing && now - lastPublishedAt < 120L) return
            lastPublishedAt = now
            lastPublishedText = previewText
            updatePreview(requestId, previewText, finalizing)
        }
    }

    /**
     * 记录异步请求状态变化日志（仅在 debug 开启时输出）。
     */
    fun logAsyncRequestEvent(
        logger: Logger,
        phase: String,
        state: AsyncRequestState,
    ) {
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "异步请求状态: phase=$phase, requestId=${state.requestId}, scene=${state.scene}, " +
                "executionMode=${state.executionMode}, streaming=${state.streaming}, " +
                "streamPhase=${state.streamPhase}, fallbackUsed=${state.fallbackUsed}, " +
                "statusMessage=${state.statusMessage}, detailMessage=${state.detailMessage}"
        }
    }

    /**
     * 把 runtime 的运行头、预算和每一步记录以 debug 级别写入日志。
     */
    fun logRuntimeTrace(
        logger: Logger,
        runtimeState: AgentRunState,
    ) {
        if (!logger.isDebugEnabled) return
        debugLazy(logger.isDebugEnabled, logger::debug) {
            buildString {
                append("runtime trace: ").append(runtimeHeader(runtimeState))
                append(", ").append(formatBudget(runtimeState.budget))
            }
        }
        runtimeState.stepRecords.forEach { record ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "runtime step: ${formatStepRecord(record)}"
            }
        }
    }

    /**
     * 把 runtime 状态摘要追加到请求详情中。
     */
    fun withRuntimeMetadata(
        requestState: AsyncRequestState,
        runtimeState: AgentRunState,
    ): AsyncRequestState {
        val runtimeSummary = formatRuntimeDetail(runtimeState)
        val mergedDetail = listOfNotNull(requestState.detailMessage, runtimeSummary)
            .joinToString(separator = "\n")
        return requestState.copy(detailMessage = mergedDetail)
    }
}
