package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.workflow.CurrentSubjectGraphRequestTracker
import org.jetbrains.concurrency.CancellablePromise

/**
 * 主题图请求的协调器。
 *
 * 同一时刻只允许一个"主题图分析"请求处于活动状态。
 * 新请求开始前会取消旧请求；旧请求的结果即使到达也会被当作过期丢弃。
 * 用 [currentAnalysisPromise] 持有当前的异步结果以便取消，
 * 用 [requestTracker] 维护单调递增的请求 ID 用于判断"是不是最新请求"。
 */
internal class SubjectGraphRequestCoordinator {
    /** 请求 ID 跟踪器，提供"是不是最新请求"判断。 */
    private val requestTracker = CurrentSubjectGraphRequestTracker()

    /** 当前正在运行的分析异步结果；可能为 null 表示无活动请求。 */
    @Volatile
    private var currentAnalysisPromise: CancellablePromise<AnalysisOutcomeAsyncResult>? = null

    /**
     * 开始一个新请求：取消旧请求并取得新的请求 ID。
     * @return 新的请求 ID，调用方应保存并在结果到达时校验
     */
    fun beginRequest(): Long {
        cancelCurrentAnalysis()
        return requestTracker.beginRequest()
    }

    /** 判断给定 ID 是否仍是最新请求。用于过滤掉过期请求的副作用。 */
    fun isLatest(requestId: Long): Boolean = requestTracker.isLatest(requestId)

    /** 替换当前分析异步结果。供调用方在派发新异步任务时记录引用。 */
    fun replaceCurrentAnalysis(promise: CancellablePromise<AnalysisOutcomeAsyncResult>) {
        currentAnalysisPromise = promise
    }

    /** 取消当前分析并清空引用。无活动请求时为空操作。 */
    fun cancelCurrentAnalysis() {
        currentAnalysisPromise?.cancel()
        currentAnalysisPromise = null
    }
}
