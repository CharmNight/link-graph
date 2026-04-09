package com.charmnight.linkgraph.services

import java.util.concurrent.atomic.AtomicLong

/**
 * 统一追踪“当前方法链路”相关请求的最新轮次。
 * 无论是当前编辑器提图、按签名调试提图，还是继续展开摘要节点，
 * 只允许最新一轮请求落地到画布，避免旧结果在稍后把新图覆盖掉。
 */
internal class CurrentMethodGraphRequestTracker {
    /** 记录最近一次分配出去的请求编号。 */
    private val latestRequestId = AtomicLong(0)

    /**
     * 开始一轮新的当前方法提图请求，并返回其编号。
     */
    fun beginRequest(): Long = latestRequestId.incrementAndGet()

    /**
     * 判断指定请求是否仍然是最新的一轮。
     */
    fun isLatest(requestId: Long): Boolean = requestId == latestRequestId.get()
}
