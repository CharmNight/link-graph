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
    ): AsyncRequestLifecycleResult {
        val sanitized = settings.sanitized()
        val remoteConnection = sanitized.remoteConnectionOrNull()
        val remotePresetSelected = sanitized.usesRemoteProvider()
        val streamingSupported = remoteConnection?.preset?.capabilities?.supportsStreaming == true
        val executionMode = when {
            remoteConnection != null -> com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY
            !sanitized.llmEnabled -> disabledMode
            else -> com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE
        }
        val remoteRequested = executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY
        val timeoutMillis = (timeoutOverrideProvider()
            ?: sanitized.effectiveTimeoutSeconds().toLong() * 1_000L)
            .coerceAtLeast(1L)
        val timeoutSecondsText = ((timeoutMillis + 999L) / 1_000L).toString()
        val requestState = com.charmnight.linkgraph.application.model.AsyncRequestState.running(
            requestId = requestId,
            scene = sceneLabel,
            executionMode = executionMode,
            statusMessage = when (executionMode) {
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY -> "正在等待远程 LLM ${sceneLabel}响应"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE -> "正在执行${sceneLabel}本地规则"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED -> "正在处理${sceneLabel}请求"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK -> "正在执行${sceneLabel}"
            },
            detailMessage = when (executionMode) {
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (streamingSupported) {
                        "当前采用流式输出，界面会持续追加预览；最终会在结束后收敛为结构化结果。最长等待 ${timeoutSecondsText} 秒。"
                    } else {
                        "当前采用完整返回，不是流式输出。最长等待 ${timeoutSecondsText} 秒，超时后会停止等待并明确提示失败。"
                    }
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE -> when {
                    !sanitized.llmEnabled -> "远程 LLM 未启用，当前直接执行本地规则或模板，不会等待远程响应。"
                    remotePresetSelected -> "远程配置未就绪，当前直接执行本地规则或模板，不会等待远程响应。"
                    else -> "当前配置使用本地规则或模板执行，不会发起远程 LLM 请求。"
                }
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED ->
                    "LLM 生成功能未启用，当前不会发起远程请求。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "当前请求已回退到本地规则。"
            },
            streaming = executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY && streamingSupported,
            streamPhase = if (executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY && streamingSupported) {
                "STREAMING"
            } else {
                null
            },
            providerLabel = sanitized.providerPreset().toString(),
            model = if (remotePresetSelected) sanitized.effectiveModel() else null,
            endpointSummary = resolveEndpointSummary(
                if (remoteConnection != null) remoteConnection.requestUrl() else sanitized.effectiveEndpoint(),
                remotePresetSelected,
            ),
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )
        return AsyncRequestLifecycleResult(
            requestId = requestId,
            sceneLabel = sceneLabel,
            executionMode = executionMode,
            requestState = requestState,
            remoteRequested = remoteRequested,
            timeoutMillis = timeoutMillis,
            llmEnabled = sanitized.llmEnabled,
            remotePresetSelected = remotePresetSelected,
            streamingSupported = streamingSupported,
        )
    }

    /**
     * 根据请求展示信息构造超时终态。
     */
    fun buildTimedOutRequestState(
        presentation: AsyncRequestLifecycleResult,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState {
        val timeoutSecondsText = ((presentation.timeoutMillis + 999L) / 1_000L).toString()
        return com.charmnight.linkgraph.application.model.AsyncRequestState.timedOut(
            message = when (presentation.executionMode) {
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "${presentation.sceneLabel}超时，已停止等待远程 LLM 流式输出。"
                    } else {
                        "${presentation.sceneLabel}超时，已停止等待远程 LLM 完整返回。"
                    }
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "${presentation.sceneLabel}超时，已停止等待本地规则或模板结果。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED ->
                    "${presentation.sceneLabel}处理超时。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "${presentation.sceneLabel}超时。"
            },
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = presentation.executionMode,
            detailMessage = when (presentation.executionMode) {
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "当前采用流式输出，但在 ${timeoutSecondsText} 秒内仍未完成最终结构化收敛，请检查网络、模型配置或缩短超时时间后重试。"
                    } else {
                        "当前采用完整返回，不是流式输出。已达到 ${timeoutSecondsText} 秒等待上限，请检查网络、模型配置或缩短超时时间后重试。"
                    }
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "本地规则或模板执行超过 ${timeoutSecondsText} 秒仍未完成，请检查当前图规模、插件日志或测试桩。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED ->
                    "当前未启用远程 LLM，本次请求本应快速返回禁用说明；若持续超时，请检查插件线程状态。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "请求已进入回退路径，但在 ${timeoutSecondsText} 秒内仍未完成。"
            },
            startedAtEpochMillis = presentation.requestState.startedAtEpochMillis,
            streaming = presentation.requestState.streaming,
            streamPhase = presentation.requestState.streamPhase,
            previewText = presentation.requestState.previewText,
            previewUpdatedAtEpochMillis = presentation.requestState.previewUpdatedAtEpochMillis,
            finalizingStructuredResult = presentation.requestState.finalizingStructuredResult,
            providerLabel = presentation.requestState.providerLabel,
            model = presentation.requestState.model,
            endpointSummary = presentation.requestState.endpointSummary,
            promptPreviewAvailable = presentation.requestState.promptPreviewAvailable,
            requestedMode = presentation.requestState.requestedMode,
            effectiveMode = presentation.requestState.effectiveMode,
        )
    }

    /**
     * 根据请求展示信息构造成功终态。
     */
    fun buildSucceededRequestState(
        presentation: AsyncRequestLifecycleResult,
        successMessage: String,
        completedRemotely: Boolean,
        warnings: List<String>,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState {
        val executionMode = if (presentation.remoteRequested && !completedRemotely) {
            com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK
        } else {
            presentation.executionMode
        }
        val fallbackUsed = executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK
        val detailMessage = when {
            fallbackUsed -> warnings.firstOrNull()
                ?: "远程 LLM ${presentation.sceneLabel}失败，当前结果已回退到本地规则或模板。"
            completedRemotely && presentation.streamingSupported -> "远程 LLM 已完成流式输出，并已落地最终结构化结果。"
            completedRemotely -> "远程 LLM 已返回完整结果。当前仍不是流式输出。"
            executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED ->
                "LLM 生成功能已关闭，当前结果用于说明为何本次请求没有发起远程调用。"
            executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE && !presentation.llmEnabled ->
                "远程 LLM 未启用，当前结果来自本地规则或模板执行。"
            executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE && presentation.remotePresetSelected ->
                "远程配置未就绪，当前结果来自本地规则或模板执行。"
            else -> "当前结果来自本地规则或模板执行。"
        }
        return com.charmnight.linkgraph.application.model.AsyncRequestState.succeeded(
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = executionMode,
            statusMessage = when {
                fallbackUsed -> "${presentation.sceneLabel}完成，已回退到本地规则或模板结果。"
                executionMode == com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "${presentation.sceneLabel}完成，结果来自本地规则或模板。"
                else -> successMessage
            },
            detailMessage = detailMessage,
            startedAtEpochMillis = presentation.requestState.startedAtEpochMillis,
            streaming = presentation.requestState.streaming,
            fallbackUsed = fallbackUsed,
            streamPhase = if (presentation.requestState.streaming && completedRemotely) "COMPLETED" else presentation.requestState.streamPhase,
            previewText = presentation.requestState.previewText,
            previewUpdatedAtEpochMillis = presentation.requestState.previewUpdatedAtEpochMillis,
            finalizingStructuredResult = false,
            providerLabel = presentation.requestState.providerLabel,
            model = presentation.requestState.model,
            endpointSummary = presentation.requestState.endpointSummary,
            promptPreviewAvailable = presentation.requestState.promptPreviewAvailable,
            requestedMode = presentation.requestState.requestedMode,
            effectiveMode = presentation.requestState.effectiveMode,
        )
    }

    /**
     * 根据请求展示信息构造失败终态。
     */
    fun buildFailedRequestState(
        presentation: AsyncRequestLifecycleResult,
        message: String,
        detailMessageOverride: String? = null,
    ): com.charmnight.linkgraph.application.model.AsyncRequestState {
        return com.charmnight.linkgraph.application.model.AsyncRequestState.failed(
            message = message,
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = presentation.executionMode,
            detailMessage = detailMessageOverride ?: when (presentation.executionMode) {
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "当前采用流式输出，但在最终结构化收敛前失败。请检查请求地址、鉴权、模型配置或网络连通性后重试。"
                    } else {
                        "当前采用完整返回，不是流式输出。请检查请求地址、鉴权、模型配置或网络连通性后重试。"
                    }
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "当前走本地规则或模板执行，请检查插件日志、测试桩或当前输入图状态。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.DISABLED ->
                    "当前未启用远程 LLM，本次请求不应进入远程执行链路。"
                com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "远程请求已进入回退路径，但最终仍失败。"
            },
            startedAtEpochMillis = presentation.requestState.startedAtEpochMillis,
            streaming = presentation.requestState.streaming,
            streamPhase = presentation.requestState.streamPhase,
            previewText = presentation.requestState.previewText,
            previewUpdatedAtEpochMillis = presentation.requestState.previewUpdatedAtEpochMillis,
            finalizingStructuredResult = false,
            providerLabel = presentation.requestState.providerLabel,
            model = presentation.requestState.model,
            endpointSummary = presentation.requestState.endpointSummary,
            promptPreviewAvailable = presentation.requestState.promptPreviewAvailable,
            requestedMode = presentation.requestState.requestedMode,
            effectiveMode = presentation.requestState.effectiveMode,
        )
    }

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
    ): (String, Boolean) -> Unit {
        var lastPublishedAt = 0L
        var lastPublishedText = ""
        return fun(previewText: String, finalizing: Boolean) {
            if (project.isDisposed) {
                return
            }
            val now = System.currentTimeMillis()
            if (!finalizing && previewText == lastPublishedText) {
                return
            }
            if (!finalizing && now - lastPublishedAt < 120L) {
                return
            }
            lastPublishedAt = now
            lastPublishedText = previewText
            updatePreview(requestId, previewText, finalizing)
        }
    }

    /**
     * 安排异步请求超时回调。
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
                    com.intellij.openapi.application.ModalityState.defaultModalityState(),
                )
            },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )
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
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed) {
                        onCompleted(result)
                    }
                },
                modalityState,
            )
        }
    }

    /** 在后台读线程上以 ReadAction 同步执行计算并等待返回，避免在 EDT 上触发索引访问违规。 */
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
