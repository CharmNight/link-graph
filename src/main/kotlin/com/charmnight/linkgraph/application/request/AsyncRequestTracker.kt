package com.charmnight.linkgraph.application.request

import java.util.concurrent.atomic.AtomicLong

/**
 * 统一追踪异步请求的"已分配编号"和"当前活跃请求"。
 *
 * 仅靠"最新编号"无法阻止同一请求在成功之后又被自己的超时回调覆盖；
 * 因此这里显式记录当前活跃请求，并在成功、失败、超时或整体失效时主动结束。
 * 使用 AtomicLong 保证多线程下的对比与赋值是原子的。
 */
internal class AsyncRequestTracker {
    /** 记录下一个可分配的请求编号。单调递增。 */
    private val nextRequestId = AtomicLong(0)
    /** 记录当前仍然有效的活跃请求编号；0 表示无活跃请求。 */
    private val activeRequestId = AtomicLong(0)

    /**
     * 开始新的异步请求，并把它标记为当前活跃请求。
     * @return 新分配的请求 ID
     */
    fun beginRequest(): Long {
        // 每次开始请求都生成新编号，并原子地替换当前活跃请求。
        val requestId = nextRequestId.incrementAndGet()
        activeRequestId.set(requestId)
        return requestId
    }

    /**
     * 判断指定请求是否仍然处于活跃状态。
     * 用于回调到达时判断"我是否还有资格把结果写回状态"。
     */
    fun isActive(requestId: Long): Boolean = activeRequestId.get() == requestId

    /**
     * 在请求结束时尝试清除活跃标记。
     * 使用 CAS 保证只有当前活跃请求能清掉，避免被错误的回调清掉别人的标记。
     * @return 是否成功清除（false 表示已被其他请求取代或失效）
     */
    fun finishRequest(requestId: Long): Boolean = activeRequestId.compareAndSet(requestId, 0)

    /**
     * 直接使当前活跃请求失效。
     * 用于"用户切换主题"等需要丢弃所有未完成结果的场景。
     */
    fun invalidate() {
        activeRequestId.set(0)
    }
}
