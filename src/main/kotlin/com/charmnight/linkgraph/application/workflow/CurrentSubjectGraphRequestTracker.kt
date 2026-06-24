package com.charmnight.linkgraph.application.workflow

import java.util.concurrent.atomic.AtomicLong

/**
 * 统一追踪"当前主体链路"相关请求的最新轮次。
 *
 * 无论是当前编辑器提图、按签名调试提图，还是继续展开摘要节点，
 * 只允许最新一轮请求落地到画布，避免旧结果在稍后把新图覆盖掉。
 * 通过 AtomicLong 维护单调递增的请求 ID，保证线程安全。
 */
internal class CurrentSubjectGraphRequestTracker {
    /** 记录最近一次分配出去的请求编号。 */
    private val latestRequestId = AtomicLong(0)

    /**
     * 开始一轮新的当前主体提图请求，并返回其编号。
     * @return 新分配的、单调递增的请求 ID
     */
    fun beginRequest(): Long = latestRequestId.incrementAndGet()

    /**
     * 判断指定请求是否仍然是最新的一轮。
     * @param requestId 待校验的请求 ID
     * @return true 表示该 ID 仍是最新，结果可以落地
     */
    fun isLatest(requestId: Long): Boolean = requestId == latestRequestId.get()
}
