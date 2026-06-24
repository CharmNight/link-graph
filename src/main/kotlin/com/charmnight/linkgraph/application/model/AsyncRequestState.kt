package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.workbench.QaMode

/**
 * 异步请求的生命周期阶段，描述一次问答/补全请求从发起到结束所处的状态。
 */
enum class AsyncRequestPhase {
    /** 空闲：尚未发起任何请求。 */
    IDLE,
    /** 运行中：请求已发出，正在等待结果。 */
    RUNNING,
    /** 成功：请求已完成并拿到正常结果。 */
    SUCCEEDED,
    /** 失败：请求因业务或网络错误终止。 */
    FAILED,
    /** 超时：请求在限定时间内未返回。 */
    TIMED_OUT,
}

/**
 * 异步请求的执行通道，标识结果来源于本地规则、远端还是降级路径，
 * 用于在 UI 上区分回答的真实来源。
 */
enum class AsyncRequestExecutionMode {
    /** 功能关闭：请求未真正执行。 */
    DISABLED,
    /** 本地规则：使用本地启发式而非远端模型给出结果。 */
    LOCAL_RULE,
    /** 远端就绪：调用远端模型并成功返回。 */
    REMOTE_READY,
    /** 远端降级：远端不可用后回退到本地或缓存结果。 */
    REMOTE_FALLBACK,
}

/**
 * 异步请求的运行时快照，承载 UI 渲染与状态机推进所需的全部信息，
 * 包括阶段、执行通道、流式进度、预览文本、模型元数据以及问答模式等。
 * 通过伴生对象的工厂方法构造，避免直接散落构造参数。
 */
data class AsyncRequestState(
    /** 当前所处的生命周期阶段。 */
    val phase: AsyncRequestPhase = AsyncRequestPhase.IDLE,
    /** 请求的唯一追踪号，便于关联日志与结果。 */
    val requestId: Long? = null,
    /** 触发该请求的业务场景标识，例如补全、问答等。 */
    val scene: String? = null,
    /** 该请求实际使用的执行通道。 */
    val executionMode: AsyncRequestExecutionMode? = null,
    /** 给用户看的一句话状态描述。 */
    val statusMessage: String? = null,
    /** 终态下的错误信息。 */
    val errorMessage: String? = null,
    /** 比状态更细的诊断说明，用于排查问题。 */
    val detailMessage: String? = null,
    /** 请求发起时间戳（毫秒）。 */
    val startedAtEpochMillis: Long? = null,
    /** 请求结束时间戳（毫秒）。 */
    val finishedAtEpochMillis: Long? = null,
    /** 是否为流式响应。 */
    val streaming: Boolean = false,
    /** 是否走了降级路径拿到结果。 */
    val fallbackUsed: Boolean = false,
    /** 流式响应所处的内部阶段标签。 */
    val streamPhase: String? = null,
    /** 当前已积累的预览文本。 */
    val previewText: String? = null,
    /** 预览文本最后一次刷新的时间戳（毫秒）。 */
    val previewUpdatedAtEpochMillis: Long? = null,
    /** 是否正在将流式片段整理为最终结构化结果。 */
    val finalizingStructuredResult: Boolean = false,
    /** 模型供应商的展示名。 */
    val providerLabel: String? = null,
    /** 实际使用的模型标识。 */
    val model: String? = null,
    /** 端点摘要，便于在 UI 上展示来源。 */
    val endpointSummary: String? = null,
    /** 是否有可展示的提示词预览。 */
    val promptPreviewAvailable: Boolean = false,
    /** 用户最初请求的问答模式。 */
    val requestedMode: QaMode? = null,
    /** 经过权限/降级裁决后实际生效的问答模式。 */
    val effectiveMode: QaMode? = null,
) {
    companion object {
        /** 构造一个处于运行中状态的请求快照，默认填入当前时间为起始时间。 */
        fun running(
            requestId: Long? = null,
            scene: String? = null,
            executionMode: AsyncRequestExecutionMode? = null,
            statusMessage: String? = null,
            detailMessage: String? = null,
            startedAtEpochMillis: Long = System.currentTimeMillis(),
            streaming: Boolean = false,
            streamPhase: String? = null,
            previewText: String? = null,
            previewUpdatedAtEpochMillis: Long? = null,
            finalizingStructuredResult: Boolean = false,
            providerLabel: String? = null,
            model: String? = null,
            endpointSummary: String? = null,
            promptPreviewAvailable: Boolean = false,
            requestedMode: QaMode? = null,
            effectiveMode: QaMode? = null,
        ) = AsyncRequestState( // 运行中状态构造器实现，统一补全阶段与起始时间
            phase = AsyncRequestPhase.RUNNING,
            requestId = requestId,
            scene = scene,
            executionMode = executionMode,
            statusMessage = statusMessage,
            detailMessage = detailMessage,
            startedAtEpochMillis = startedAtEpochMillis,
            streaming = streaming,
            streamPhase = streamPhase,
            previewText = previewText,
            previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
            finalizingStructuredResult = finalizingStructuredResult,
            providerLabel = providerLabel,
            model = model,
            endpointSummary = endpointSummary,
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )

        /** 构造一个成功结束的请求快照，默认填入当前时间为完成时间。 */
        fun succeeded(
            requestId: Long? = null,
            scene: String? = null,
            executionMode: AsyncRequestExecutionMode? = null,
            statusMessage: String? = null,
            detailMessage: String? = null,
            startedAtEpochMillis: Long? = null,
            finishedAtEpochMillis: Long = System.currentTimeMillis(),
            streaming: Boolean = false,
            fallbackUsed: Boolean = false,
            streamPhase: String? = null,
            previewText: String? = null,
            previewUpdatedAtEpochMillis: Long? = null,
            finalizingStructuredResult: Boolean = false,
            providerLabel: String? = null,
            model: String? = null,
            endpointSummary: String? = null,
            promptPreviewAvailable: Boolean = false,
            requestedMode: QaMode? = null,
            effectiveMode: QaMode? = null,
        ) = AsyncRequestState(
            phase = AsyncRequestPhase.SUCCEEDED,
            requestId = requestId,
            scene = scene,
            executionMode = executionMode,
            statusMessage = statusMessage,
            detailMessage = detailMessage,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAtEpochMillis,
            streaming = streaming,
            fallbackUsed = fallbackUsed,
            streamPhase = streamPhase,
            previewText = previewText,
            previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
            finalizingStructuredResult = finalizingStructuredResult,
            providerLabel = providerLabel,
            model = model,
            endpointSummary = endpointSummary,
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )

        /** 构造一个失败结束的请求快照，把错误信息写入消息字段。 */
        fun failed(
            message: String,
            requestId: Long? = null,
            scene: String? = null,
            executionMode: AsyncRequestExecutionMode? = null,
            detailMessage: String? = null,
            startedAtEpochMillis: Long? = null,
            finishedAtEpochMillis: Long = System.currentTimeMillis(),
            streaming: Boolean = false,
            streamPhase: String? = null,
            previewText: String? = null,
            previewUpdatedAtEpochMillis: Long? = null,
            finalizingStructuredResult: Boolean = false,
            providerLabel: String? = null,
            model: String? = null,
            endpointSummary: String? = null,
            promptPreviewAvailable: Boolean = false,
            requestedMode: QaMode? = null,
            effectiveMode: QaMode? = null,
        ) = terminal(
            phase = AsyncRequestPhase.FAILED,
            message = message,
            requestId = requestId,
            scene = scene,
            executionMode = executionMode,
            detailMessage = detailMessage,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAtEpochMillis,
            streaming = streaming,
            streamPhase = streamPhase,
            previewText = previewText,
            previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
            finalizingStructuredResult = finalizingStructuredResult,
            providerLabel = providerLabel,
            model = model,
            endpointSummary = endpointSummary,
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )

        /** 构造一个超时结束的请求快照，复用终态构造逻辑。 */
        fun timedOut(
            message: String,
            requestId: Long? = null,
            scene: String? = null,
            executionMode: AsyncRequestExecutionMode? = null,
            detailMessage: String? = null,
            startedAtEpochMillis: Long? = null,
            finishedAtEpochMillis: Long = System.currentTimeMillis(),
            streaming: Boolean = false,
            streamPhase: String? = null,
            previewText: String? = null,
            previewUpdatedAtEpochMillis: Long? = null,
            finalizingStructuredResult: Boolean = false,
            providerLabel: String? = null,
            model: String? = null,
            endpointSummary: String? = null,
            promptPreviewAvailable: Boolean = false,
            requestedMode: QaMode? = null,
            effectiveMode: QaMode? = null,
        ) = terminal(
            phase = AsyncRequestPhase.TIMED_OUT,
            message = message,
            requestId = requestId,
            scene = scene,
            executionMode = executionMode,
            detailMessage = detailMessage,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAtEpochMillis,
            streaming = streaming,
            streamPhase = streamPhase,
            previewText = previewText,
            previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
            finalizingStructuredResult = finalizingStructuredResult,
            providerLabel = providerLabel,
            model = model,
            endpointSummary = endpointSummary,
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )

        /** failed/timedOut 共用的终态构造器：统一写入完成时间与错误消息。 */
        private fun terminal(
            phase: AsyncRequestPhase,
            message: String,
            requestId: Long?,
            scene: String?,
            executionMode: AsyncRequestExecutionMode?,
            detailMessage: String?,
            startedAtEpochMillis: Long?,
            finishedAtEpochMillis: Long,
            streaming: Boolean,
            streamPhase: String?,
            previewText: String?,
            previewUpdatedAtEpochMillis: Long?,
            finalizingStructuredResult: Boolean,
            providerLabel: String?,
            model: String?,
            endpointSummary: String?,
            promptPreviewAvailable: Boolean,
            requestedMode: QaMode?,
            effectiveMode: QaMode?,
        ) = AsyncRequestState(
            phase = phase,
            requestId = requestId,
            scene = scene,
            executionMode = executionMode,
            errorMessage = message,
            detailMessage = detailMessage,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAtEpochMillis,
            streaming = streaming,
            streamPhase = streamPhase,
            previewText = previewText,
            previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
            finalizingStructuredResult = finalizingStructuredResult,
            providerLabel = providerLabel,
            model = model,
            endpointSummary = endpointSummary,
            promptPreviewAvailable = promptPreviewAvailable,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )
    }
}
