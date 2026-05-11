package com.charmnight.linkgraph.application.request

import java.util.concurrent.atomic.AtomicLong

/**
 * 统一追踪异步请求的“已分配编号”和“当前活跃请求”。
 * 仅靠“最新编号”无法阻止同一请求在成功之后又被自己的超时回调覆盖；
 * 因此这里显式记录当前活跃请求，并在成功、失败、超时或整体失效时主动结束。
 */
internal class AsyncRequestTracker {
    /** 记录下一个可分配的请求编号。 */
    private val nextRequestId = AtomicLong(0)
    /** 记录当前仍然有效的活跃请求编号。 */
    private val activeRequestId = AtomicLong(0)

    /**
     * 开始新的异步请求，并把它标记为当前活跃请求。
     */
    fun beginRequest(): Long {
        // 每次开始请求都生成新编号，并原子地替换当前活跃请求。
        val requestId = nextRequestId.incrementAndGet()
        activeRequestId.set(requestId)
        return requestId
    }

    /**
     * 判断指定请求是否仍然处于活跃状态。
     */
    fun isActive(requestId: Long): Boolean = activeRequestId.get() == requestId

    /**
     * 在请求结束时尝试清除活跃标记。
     */
    fun finishRequest(requestId: Long): Boolean = activeRequestId.compareAndSet(requestId, 0)

    /**
     * 直接使当前活跃请求失效。
     */
    fun invalidate() {
        activeRequestId.set(0)
    }
}
