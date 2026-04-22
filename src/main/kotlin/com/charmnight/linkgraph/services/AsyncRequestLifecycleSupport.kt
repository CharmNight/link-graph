package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 统一管理图问答、diff 问答、生成计划、代码草稿和链路讲解的异步请求生命周期。
 */
internal class AsyncRequestLifecycleSupport(
    /** 当前项目。 */
    private val project: Project,
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 测试环境下的超时覆盖值。 */
    private val timeoutOverrideProvider: () -> Long?,
) {
    /** 图问答请求跟踪器。 */
    private val auditRequestTracker = AsyncRequestTracker()
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

    fun beginAuditRequest(): Long = auditRequestTracker.beginRequest()

    fun completeAuditRequest(requestId: Long): Boolean = auditRequestTracker.finishRequest(requestId)

    fun beginDiffReviewRequest(): Long = diffReviewRequestTracker.beginRequest()

    fun completeDiffReviewRequest(requestId: Long): Boolean = diffReviewRequestTracker.finishRequest(requestId)

    fun beginGenerationPlanRequest(): Long = generationPlanRequestTracker.beginRequest()

    fun completeGenerationPlanRequest(requestId: Long): Boolean = generationPlanRequestTracker.finishRequest(requestId)

    fun beginGenerationPlanDiscussionRequest(): Long = generationPlanDiscussionRequestTracker.beginRequest()

    fun completeGenerationPlanDiscussionRequest(requestId: Long): Boolean = generationPlanDiscussionRequestTracker.finishRequest(requestId)

    fun beginCodeDraftRequest(): Long = codeDraftRequestTracker.beginRequest()

    fun completeCodeDraftRequest(requestId: Long): Boolean = codeDraftRequestTracker.finishRequest(requestId)

    fun beginBeautificationRequest(): Long = beautificationRequestTracker.beginRequest()

    fun completeBeautificationRequest(requestId: Long): Boolean = beautificationRequestTracker.finishRequest(requestId)

    /**
     * 使所有异步分析类请求失效。
     */
    fun invalidateRequests() {
        auditRequestTracker.invalidate()
        diffReviewRequestTracker.invalidate()
        generationPlanRequestTracker.invalidate()
        generationPlanDiscussionRequestTracker.invalidate()
        codeDraftRequestTracker.invalidate()
        beautificationRequestTracker.invalidate()
    }

    /**
     * 为异步请求构造前端展示状态和执行元数据。
     */
    fun buildAsyncRequestPresentation(
        requestId: Long,
        sceneLabel: String,
        settings: LinkGraphSettingsState,
        disabledMode: GraphEditorStateService.AsyncRequestExecutionMode = GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE,
        promptPreviewAvailable: Boolean = true,
    ): AsyncRequestPresentation {
        val sanitized = settings.sanitized()
        val remoteConnection = sanitized.remoteConnectionOrNull()
        val remotePresetSelected = sanitized.usesRemoteProvider()
        val streamingSupported = remoteConnection?.preset?.capabilities?.supportsStreaming == true
        val executionMode = when {
            remoteConnection != null -> GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY
            !sanitized.llmEnabled -> disabledMode
            else -> GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE
        }
        val remoteRequested = executionMode == GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY
        val timeoutMillis = (timeoutOverrideProvider()
            ?: sanitized.effectiveTimeoutSeconds().toLong() * 1_000L)
            .coerceAtLeast(1L)
        val timeoutSecondsText = ((timeoutMillis + 999L) / 1_000L).toString()
        val requestState = GraphEditorStateService.AsyncRequestState.running(
            requestId = requestId,
            scene = sceneLabel,
            executionMode = executionMode,
            statusMessage = when (executionMode) {
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY -> "正在等待远程 LLM ${sceneLabel}响应"
                GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE -> "正在执行${sceneLabel}本地规则"
                GraphEditorStateService.AsyncRequestExecutionMode.DISABLED -> "正在处理${sceneLabel}请求"
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK -> "正在执行${sceneLabel}"
            },
            detailMessage = when (executionMode) {
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (streamingSupported) {
                        "当前采用流式输出，界面会持续追加预览；最终会在结束后收敛为结构化结果。最长等待 ${timeoutSecondsText} 秒。"
                    } else {
                        "当前采用完整返回，不是流式输出。最长等待 ${timeoutSecondsText} 秒，超时后会停止等待并明确提示失败。"
                    }
                GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE -> when {
                    !sanitized.llmEnabled -> "远程 LLM 未启用，当前直接执行本地规则或模板，不会等待远程响应。"
                    remotePresetSelected -> "远程配置未就绪，当前直接执行本地规则或模板，不会等待远程响应。"
                    else -> "当前配置使用本地规则或模板执行，不会发起远程 LLM 请求。"
                }
                GraphEditorStateService.AsyncRequestExecutionMode.DISABLED ->
                    "LLM 生成功能未启用，当前不会发起远程请求。"
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "当前请求已回退到本地规则。"
            },
            streaming = executionMode == GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY && streamingSupported,
            streamPhase = if (executionMode == GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY && streamingSupported) {
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
        )
        return AsyncRequestPresentation(
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
        presentation: AsyncRequestPresentation,
    ): GraphEditorStateService.AsyncRequestState {
        val timeoutSecondsText = ((presentation.timeoutMillis + 999L) / 1_000L).toString()
        return GraphEditorStateService.AsyncRequestState.timedOut(
            message = when (presentation.executionMode) {
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "${presentation.sceneLabel}超时，已停止等待远程 LLM 流式输出。"
                    } else {
                        "${presentation.sceneLabel}超时，已停止等待远程 LLM 完整返回。"
                    }
                GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "${presentation.sceneLabel}超时，已停止等待本地规则或模板结果。"
                GraphEditorStateService.AsyncRequestExecutionMode.DISABLED ->
                    "${presentation.sceneLabel}处理超时。"
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
                    "${presentation.sceneLabel}超时。"
            },
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = presentation.executionMode,
            detailMessage = when (presentation.executionMode) {
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "当前采用流式输出，但在 ${timeoutSecondsText} 秒内仍未完成最终结构化收敛，请检查网络、模型配置或缩短超时时间后重试。"
                    } else {
                        "当前采用完整返回，不是流式输出。已达到 ${timeoutSecondsText} 秒等待上限，请检查网络、模型配置或缩短超时时间后重试。"
                    }
                GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "本地规则或模板执行超过 ${timeoutSecondsText} 秒仍未完成，请检查当前图规模、插件日志或测试桩。"
                GraphEditorStateService.AsyncRequestExecutionMode.DISABLED ->
                    "当前未启用远程 LLM，本次请求本应快速返回禁用说明；若持续超时，请检查插件线程状态。"
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
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
        )
    }

    /**
     * 根据请求展示信息构造成功终态。
     */
    fun buildSucceededRequestState(
        presentation: AsyncRequestPresentation,
        successMessage: String,
        completedRemotely: Boolean,
        warnings: List<String>,
    ): GraphEditorStateService.AsyncRequestState {
        val executionMode = if (presentation.remoteRequested && !completedRemotely) {
            GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK
        } else {
            presentation.executionMode
        }
        val fallbackUsed = executionMode == GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK
        val detailMessage = when {
            fallbackUsed -> warnings.firstOrNull()
                ?: "远程 LLM ${presentation.sceneLabel}失败，当前结果已回退到本地规则或模板。"
            completedRemotely && presentation.streamingSupported -> "远程 LLM 已完成流式输出，并已落地最终结构化结果。"
            completedRemotely -> "远程 LLM 已返回完整结果。当前仍不是流式输出。"
            executionMode == GraphEditorStateService.AsyncRequestExecutionMode.DISABLED ->
                "LLM 生成功能已关闭，当前结果用于说明为何本次请求没有发起远程调用。"
            executionMode == GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE && !presentation.llmEnabled ->
                "远程 LLM 未启用，当前结果来自本地规则或模板执行。"
            executionMode == GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE && presentation.remotePresetSelected ->
                "远程配置未就绪，当前结果来自本地规则或模板执行。"
            else -> "当前结果来自本地规则或模板执行。"
        }
        return GraphEditorStateService.AsyncRequestState.succeeded(
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = executionMode,
            statusMessage = when {
                fallbackUsed -> "${presentation.sceneLabel}完成，已回退到本地规则或模板结果。"
                executionMode == GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE ->
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
        )
    }

    /**
     * 根据请求展示信息构造失败终态。
     */
    fun buildFailedRequestState(
        presentation: AsyncRequestPresentation,
        message: String,
        detailMessageOverride: String? = null,
    ): GraphEditorStateService.AsyncRequestState {
        return GraphEditorStateService.AsyncRequestState.failed(
            message = message,
            requestId = presentation.requestId,
            scene = presentation.sceneLabel,
            executionMode = presentation.executionMode,
            detailMessage = detailMessageOverride ?: when (presentation.executionMode) {
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_READY ->
                    if (presentation.streamingSupported) {
                        "当前采用流式输出，但在最终结构化收敛前失败。请检查请求地址、鉴权、模型配置或网络连通性后重试。"
                    } else {
                        "当前采用完整返回，不是流式输出。请检查请求地址、鉴权、模型配置或网络连通性后重试。"
                    }
                GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE ->
                    "当前走本地规则或模板执行，请检查插件日志、测试桩或当前输入图状态。"
                GraphEditorStateService.AsyncRequestExecutionMode.DISABLED ->
                    "当前未启用远程 LLM，本次请求不应进入远程执行链路。"
                GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK ->
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
        )
    }

    /**
     * 把 runtime 关键信息追加到异步请求状态。
     * 这里只暴露 runId、capabilityId 和 failureReason 摘要，不把完整 runtime 内部状态塞进 UI snapshot。
     */
    fun withRuntimeMetadata(
        requestState: GraphEditorStateService.AsyncRequestState,
        runtimeState: AgentRunState,
    ): GraphEditorStateService.AsyncRequestState {
        val runtimeSummary = formatRuntimeDetail(runtimeState)
        val mergedDetail = listOfNotNull(requestState.detailMessage, runtimeSummary)
            .joinToString(separator = "\n")
        return requestState.copy(detailMessage = mergedDetail)
    }

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
        updatePreview: GraphEditorStateService.(Long, String, Boolean) -> Unit,
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
            session.mutate {
                updatePreview(requestId, previewText, finalizing)
            }
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
        state: GraphEditorStateService.AsyncRequestState,
    ) {
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "异步请求状态: phase=$phase, requestId=${state.requestId}, scene=${state.scene}, " +
                "executionMode=${state.executionMode}, streaming=${state.streaming}, " +
                "streamPhase=${state.streamPhase}, fallbackUsed=${state.fallbackUsed}, " +
                "statusMessage=${state.statusMessage}, detailMessage=${state.detailMessage}"
        }
    }

    private fun formatRuntimeDetail(runtimeState: AgentRunState): String {
        val sections = mutableListOf<String>()
        sections += runtimeHeader(runtimeState)
        sections += formatBudget(runtimeState.budget)
        runtimeState.stepRecords.forEach { record ->
            sections += formatStepRecord(record)
        }
        return sections.joinToString(separator = "\n")
    }

    private fun runtimeHeader(runtimeState: AgentRunState): String {
        return buildString {
            append("runtime runId=").append(runtimeState.runId)
            append(", capability=").append(runtimeState.capabilityId)
            runtimeState.failureReason?.let {
                append(", failureReason=").append(it.name)
            }
        }
    }

    private fun formatBudget(budget: RunBudget): String {
        return buildString {
            append("runtime budget steps=").append(budget.usedSteps).append('/').append(budget.maxSteps)
            append(", files=").append(budget.filesRead).append('/').append(budget.maxFilesRead)
            append(", snippets=").append(budget.snippetsRead).append('/').append(budget.maxSnippets)
            append(", lines=").append(budget.totalSnippetLinesRead).append('/').append(budget.maxTotalSnippetLines)
        }
    }

    private fun formatStepRecord(record: AgentStepRecord): String {
        return buildString {
            append("step[").append(record.stepIndex).append("]")
            append(" phase=").append(record.phase.name)
            append(", summary=").append(record.summary)
            record.toolName?.let { toolName ->
                append(", tool=").append(toolName)
            }
            record.nodeId?.let { nodeId ->
                append(", nodeId=").append(nodeId)
            }
        }
    }

    /**
     * 提炼前端可展示的 endpoint 摘要。
     */
    private fun resolveEndpointSummary(
        endpoint: String?,
        remotePresetSelected: Boolean,
    ): String? {
        if (!remotePresetSelected || endpoint.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val uri = URI(endpoint)
            buildString {
                append(uri.scheme ?: "https")
                append("://")
                append(uri.host ?: endpoint)
                uri.port.takeIf { it > 0 }?.let { append(":").append(it) }
                val path = uri.path?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
                if (path != null) {
                    append(path)
                }
            }
        }.getOrElse {
            endpoint
        }
    }
}

/**
 * 异步请求在前端展示所需的包装信息。
 */
internal data class AsyncRequestPresentation(
    /** 请求编号。 */
    val requestId: Long,
    /** 场景展示名称。 */
    val sceneLabel: String,
    /** 本次请求采用的执行模式。 */
    val executionMode: GraphEditorStateService.AsyncRequestExecutionMode,
    /** 面向前端的运行中状态。 */
    val requestState: GraphEditorStateService.AsyncRequestState,
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
