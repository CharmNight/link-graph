package com.charmnight.linkgraph.application.request

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 统一管理图问答、diff 问答、生成计划、代码草稿和链路讲解的异步请求生命周期。
 */
internal class AsyncRequestLifecycleSupport(
    /** 当前项目。 */
    private val project: Project,
    /** 测试环境下的超时覆盖值。 */
    private val timeoutOverrideProvider: () -> Long?,
) {
    // P2-1 真正的架构分解：线程调度委托给独立的 AsyncTaskDispatcher
    internal val taskDispatcher = AsyncTaskDispatcher(project)
    /** 图问答请求跟踪器。 */
    private val qaRequestTracker = AsyncRequestTracker()
    /** diff 审核请求跟踪器。 */
    private val diffReviewRequestTracker = AsyncRequestTracker()
    /** 生成计划请求跟踪器。 */
    private val generationPlanRequestTracker = AsyncRequestTracker()
    /** 实现建议追问请求跟踪器。 */
    private val generationPlanDiscussionRequestTracker = AsyncRequestTracker()
    /** 代码草稿请求跟踪器。 */
    private val codeDraftRequestTracker = AsyncRequestTracker()
    /** 链路讲解请求跟踪器。 */
    private val beautificationRequestTracker = AsyncRequestTracker()

    /** 标记一次新的图问答请求开始，返回该请求的唯一 ID 用于后续完成或取消。 */
    fun beginQaRequest(): Long = qaRequestTracker.beginRequest()

    /** 完成指定 ID 的图问答请求，返回是否确实由本次调用关闭了该请求。 */
    fun completeQaRequest(requestId: Long): Boolean = qaRequestTracker.finishRequest(requestId)

    /** 标记一次新的图 diff 评审请求开始，返回唯一请求 ID。 */
    fun beginDiffReviewRequest(): Long = diffReviewRequestTracker.beginRequest()

    /** 完成指定 ID 的图 diff 评审请求。 */
    fun completeDiffReviewRequest(requestId: Long): Boolean = diffReviewRequestTracker.finishRequest(requestId)

    /** 标记一次新的生成计划请求开始，返回唯一请求 ID。 */
    fun beginGenerationPlanRequest(): Long = generationPlanRequestTracker.beginRequest()

    /** 完成指定 ID 的生成计划请求。 */
    fun completeGenerationPlanRequest(requestId: Long): Boolean = generationPlanRequestTracker.finishRequest(requestId)

    /** 标记一次新的实现建议追问请求开始，返回唯一请求 ID。 */
    fun beginGenerationPlanDiscussionRequest(): Long = generationPlanDiscussionRequestTracker.beginRequest()

    /** 完成指定 ID 的实现建议追问请求。 */
    fun completeGenerationPlanDiscussionRequest(requestId: Long): Boolean = generationPlanDiscussionRequestTracker.finishRequest(requestId)

    /** 标记一次新的代码草稿生成请求开始，返回唯一请求 ID。 */
    fun beginCodeDraftRequest(): Long = codeDraftRequestTracker.beginRequest()

    /** 完成指定 ID 的代码草稿生成请求。 */
    fun completeCodeDraftRequest(requestId: Long): Boolean = codeDraftRequestTracker.finishRequest(requestId)

    /** 标记一次新的链路讲解请求开始，返回唯一请求 ID。 */
    fun beginBeautificationRequest(): Long = beautificationRequestTracker.beginRequest()

    /** 完成指定 ID 的链路讲解请求。 */
    fun completeBeautificationRequest(requestId: Long): Boolean = beautificationRequestTracker.finishRequest(requestId)

    /**
     * 使所有异步分析类请求失效。
     */
    fun invalidateRequests() {
        qaRequestTracker.invalidate()
        diffReviewRequestTracker.invalidate()
        generationPlanRequestTracker.invalidate()
        generationPlanDiscussionRequestTracker.invalidate()
        codeDraftRequestTracker.invalidate()
        beautificationRequestTracker.invalidate()
    }

    /**
     * 为异步请求构造前端展示状态和执行元数据。
     */
    fun buildAsyncRequestLifecycleResult(
        requestId: Long,
        sceneLabel: String,
        settings: LinkGraphSettingsState,
        disabledMode: com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode = com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE,
        promptPreviewAvailable: Boolean = false,
        requestedMode: QaMode? = null,
        effectiveMode: QaMode? = null,
    ): AsyncRequestLifecycleResult = com.charmnight.linkgraph.application.request.buildAsyncRequestLifecycleResult(
        requestId = requestId,
        sceneLabel = sceneLabel,
        settings = settings,
        timeoutOverrideMillis = timeoutOverrideProvider(),
        disabledMode = disabledMode,
        promptPreviewAvailable = promptPreviewAvailable,
        requestedMode = requestedMode,
        effectiveMode = effectiveMode,
    )

    /** 根据请求展示信息构造超时终态：详见 top-level fun buildTimedOutRequestState。 */
    fun buildTimedOutRequestState(
        presentation: AsyncRequestLifecycleResult,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState =
        com.charmnight.linkgraph.application.request.buildTimedOutRequestState(presentation)

    /** 根据请求展示信息构造成功终态：详见 top-level fun buildSucceededRequestState。 */
    fun buildSucceededRequestState(
        presentation: AsyncRequestLifecycleResult,
        successMessage: String,
        completedRemotely: Boolean,
        warnings: List<String>,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState =
        com.charmnight.linkgraph.application.request.buildSucceededRequestState(
            presentation = presentation,
            successMessage = successMessage,
            completedRemotely = completedRemotely,
            warnings = warnings,
        )

    /** 根据请求展示信息构造失败终态：详见 top-level fun buildFailedRequestState。 */
    fun buildFailedRequestState(
        presentation: AsyncRequestLifecycleResult,
        message: String,
        detailMessageOverride: String? = null,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState =
        com.charmnight.linkgraph.application.request.buildFailedRequestState(
            presentation = presentation,
            message = message,
            detailMessageOverride = detailMessageOverride,
        )

    /**
     * 把 runtime 关键信息追加到异步请求状态。
     * 这里只暴露 runId、capabilityId 和 failureReason 摘要，不把完整 runtime 内部状态塞进 UI snapshot。
     */
    fun withRuntimeMetadata(
        requestState: com.charmnight.linkgraph.application.model.AsyncRequestState,
        runtimeState: AgentRunState,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState {
        val runtimeSummary = formatRuntimeDetail(runtimeState)
        val mergedDetail = listOfNotNull(requestState.detailMessage, runtimeSummary)
            .joinToString(separator = "\n")
        return requestState.copy(detailMessage = mergedDetail)
    }

    /** 把 runtime 的运行头、预算和每一步记录以 debug 级别写入日志，仅在 logger 开启 debug 时输出。 */
    fun logRuntimeTrace(
        logger: Logger,
        runtimeState: AgentRunState,
    ) {
        if (!logger.isDebugEnabled) {
            return
        }
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
     * 构造节流后的流式预览回写器，避免每个 token 都触发整页同步。
     */
    fun createStreamingPreviewUpdater(
        requestId: Long,
        updatePreview: (Long, String, Boolean) -> Unit,
    ): (String, Boolean) -> Unit = taskDispatcher.createStreamingPreviewUpdater(requestId, updatePreview)

    /**
     * 安排异步请求超时回调。
     */
    fun scheduleAsyncRequestTimeout(
        requestId: Long,
        timeoutMillis: Long,
        completeRequest: (Long) -> Boolean,
        onTimeout: () -> Unit,
    ) {
        taskDispatcher.scheduleAsyncRequestTimeout(requestId, timeoutMillis, completeRequest, onTimeout)
    }

    /**
     * 记录异步请求状态变化日志。
     */
    fun logAsyncRequestEvent(
        logger: Logger,
        phase: String,
        state: com.charmnight.linkgraph.application.model.AsyncRequestState,
    ) {
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "异步请求状态: phase=$phase, requestId=${state.requestId}, scene=${state.scene}, " +
                "executionMode=${state.executionMode}, streaming=${state.streaming}, " +
                "streamPhase=${state.streamPhase}, fallbackUsed=${state.fallbackUsed}, " +
                "statusMessage=${state.statusMessage}, detailMessage=${state.detailMessage}"
        }
    }

    /** 在后台线程池执行耗时任务，完成后回到指定 modality 状态派发回调结果，避免阻塞 EDT。 */
    fun <T> runBackgroundTask(
        work: () -> T,
        onCompleted: (Result<T>) -> Unit,
        modalityState: ModalityState = ModalityState.defaultModalityState(),
    ) = taskDispatcher.runBackgroundTask(work, onCompleted, modalityState)

    /** 在后台读线程上以 ReadAction 同步执行计算并等待返回，避免在 EDT 上触发索引访问违规。 */
    fun <T> computeOnBackgroundReadThread(action: () -> T): T = taskDispatcher.computeOnBackgroundReadThread(action)

    /** 把 runtime 状态、预算和每一步记录格式化为多行文本，用于追加到请求详情中。 */
    private fun formatRuntimeDetail(runtimeState: AgentRunState): String =
        com.charmnight.linkgraph.application.request.formatRuntimeDetail(runtimeState)

    /** 输出包含 runId、capability 与失败原因的 runtime 头部摘要。 */
    private fun runtimeHeader(runtimeState: AgentRunState): String =
        com.charmnight.linkgraph.application.request.runtimeHeader(runtimeState)

    /** 把步数、文件、片段、代码行四类预算消耗格式化为单行文本。 */
    private fun formatBudget(budget: RunBudget): String =
        com.charmnight.linkgraph.application.request.formatBudget(budget)

    /** 把单步执行记录（序号、阶段、摘要、工具、节点）格式化为可读文本。 */
    private fun formatStepRecord(record: AgentStepRecord): String =
        com.charmnight.linkgraph.application.request.formatStepRecord(record)

    /**
     * 提炼前端可展示的 endpoint 摘要。
     */
    private fun resolveEndpointSummary(
        endpoint: String?,
        remotePresetSelected: Boolean,
    ): String? =
        com.charmnight.linkgraph.application.request.resolveEndpointSummary(endpoint, remotePresetSelected)
}

/**
 * 异步请求在前端展示所需的包装信息。
 */
internal data class AsyncRequestLifecycleResult(
    /** 请求编号。 */
    val requestId: Long,
    /** 场景展示名称。 */
    val sceneLabel: String,
    /** 本次请求采用的执行模式。 */
    val executionMode: com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode,
    /** 面向前端的运行中状态。 */
    val requestState: com.charmnight.linkgraph.application.model.AsyncRequestState,
    /** 是否实际发起了远程请求。 */
    val remoteRequested: Boolean,
    /** 超时时间，单位毫秒。 */
    val timeoutMillis: Long,
    /** 当前是否启用了 LLM。 */
    val llmEnabled: Boolean,
    /** 当前是否选择了远程供应商。 */
    val remotePresetSelected: Boolean,
    /** 当前远程能力是否支持流式。 */
    val streamingSupported: Boolean,
)
