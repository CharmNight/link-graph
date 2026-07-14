package com.charmnight.linkgraph.application.request

import java.util.EnumMap

internal enum class AsyncRequestScene {
    QA,
    DIFF_REVIEW,
    GENERATION_PLAN,
    GENERATION_PLAN_DISCUSSION,
    CODE_DRAFT,
    BEAUTIFICATION,
}

internal data class InvalidatedAsyncRequests(
    val qaRequestId: Long? = null,
    val diffReviewRequestId: Long? = null,
    val generationPlanRequestId: Long? = null,
    val generationPlanDiscussionRequestId: Long? = null,
    val codeDraftRequestId: Long? = null,
    val beautificationRequestId: Long? = null,
)

/**
 * 以项目级 generation 为边界，统一维护所有异步场景的当前活跃请求。
 *
 * begin、finish 与整体失效都在同一个短临界区完成，避免多个原子字段组合更新时
 * 暴露部分失效或旧 generation 覆盖新 generation 的中间状态。
 */
internal class AsyncRequestRegistry {
    private data class ActiveRequest(
        val requestId: Long,
        val generation: Long,
    )

    private val lock = Any()
    private var generation = 0L
    private val nextRequestIds = EnumMap<AsyncRequestScene, Long>(AsyncRequestScene::class.java)
    private val activeRequests =
        EnumMap<AsyncRequestScene, ActiveRequest>(AsyncRequestScene::class.java)

    fun beginRequest(scene: AsyncRequestScene): Long = synchronized(lock) {
        val requestId = Math.addExact(nextRequestIds[scene] ?: 0L, 1L)
        nextRequestIds[scene] = requestId
        activeRequests[scene] = ActiveRequest(
            requestId = requestId,
            generation = generation,
        )
        requestId
    }

    fun finishRequest(scene: AsyncRequestScene, requestId: Long): Boolean = synchronized(lock) {
        val activeRequest = activeRequests[scene] ?: return@synchronized false
        if (activeRequest.requestId != requestId || activeRequest.generation != generation) {
            return@synchronized false
        }
        activeRequests.remove(scene)
        true
    }

    fun isRequestActive(scene: AsyncRequestScene, requestId: Long): Boolean = synchronized(lock) {
        val activeRequest = activeRequests[scene] ?: return@synchronized false
        activeRequest.requestId == requestId && activeRequest.generation == generation
    }

    fun invalidateAll(): InvalidatedAsyncRequests = synchronized(lock) {
        val invalidated = InvalidatedAsyncRequests(
            qaRequestId = activeRequests[AsyncRequestScene.QA]?.requestId,
            diffReviewRequestId = activeRequests[AsyncRequestScene.DIFF_REVIEW]?.requestId,
            generationPlanRequestId = activeRequests[AsyncRequestScene.GENERATION_PLAN]?.requestId,
            generationPlanDiscussionRequestId =
                activeRequests[AsyncRequestScene.GENERATION_PLAN_DISCUSSION]?.requestId,
            codeDraftRequestId = activeRequests[AsyncRequestScene.CODE_DRAFT]?.requestId,
            beautificationRequestId = activeRequests[AsyncRequestScene.BEAUTIFICATION]?.requestId,
        )
        generation = Math.addExact(generation, 1L)
        activeRequests.clear()
        invalidated
    }
}
