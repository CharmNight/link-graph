package com.charmnight.linkgraph.application.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncRequestTrackerTest {
    @Test
    fun beginRequestReturnsMonotonicallyIncreasingIds() {
        val tracker = AsyncRequestTracker()

        val first = tracker.beginRequest()
        val second = tracker.beginRequest()
        val third = tracker.beginRequest()

        assertTrue(second > first, "ID 必须单调递增；first=$first, second=$second")
        assertTrue(third > second, "ID 必须单调递增；second=$second, third=$third")
    }

    @Test
    fun finishRequestClearsActiveMarkerOnlyForTheMatchingRequest() {
        val tracker = AsyncRequestTracker()

        val first = tracker.beginRequest()
        // 启动第二个请求，覆盖 first 成为活跃
        val second = tracker.beginRequest()

        assertTrue(tracker.isActive(second))
        assertFalse(tracker.isActive(first), "被覆盖的旧请求不应仍处于活跃状态")

        // first 试图 finish 不应清除 second 的活跃标记
        assertFalse(tracker.finishRequest(first), "已过期的请求 finish 必须返回 false")
        assertTrue(tracker.isActive(second), "second 仍应是活跃请求")

        // second finish 才能清除
        assertTrue(tracker.finishRequest(second))
        assertFalse(tracker.isActive(second))
    }

    @Test
    fun invalidateClearsActiveMarkerWithoutAffectingFutureRequests() {
        val tracker = AsyncRequestTracker()

        val first = tracker.beginRequest()
        assertTrue(tracker.isActive(first))

        tracker.invalidate()
        assertFalse(tracker.isActive(first), "invalidate 后原请求必须不再活跃")

        // invalidate 后新请求仍能正常开始
        val second = tracker.beginRequest()
        assertTrue(tracker.isActive(second))
        assertTrue(second > first, "invalidate 不应影响 ID 单调递增")
    }

    @Test
    fun invalidateDoesNotClobberRequestStartedAfterInvalidate() {
        // P2-3 核心场景：A 启动 → invalidate → B 启动 → A finish 不应清除 B。
        // 旧实现用 activeRequestId.set(0) 会在 B 启动后误清；CAS 实现只清当前非零值。
        val tracker = AsyncRequestTracker()

        val a = tracker.beginRequest()
        tracker.invalidate()
        val b = tracker.beginRequest()
        assertTrue(tracker.isActive(b))

        // A 的迟到 finish 回调到达；不应清除 B
        tracker.finishRequest(a)
        assertTrue(
            tracker.isActive(b),
            "B 启动后的 invalidate 或过期请求 finish 不应清除 B 的活跃标记",
        )
    }

    @Test
    fun invalidateIsIdempotent() {
        val tracker = AsyncRequestTracker()
        // 无活跃请求时 invalidate 不抛
        tracker.invalidate()
        tracker.invalidate()

        val request = tracker.beginRequest()
        tracker.invalidate()
        // 二次 invalidate 应保持无活跃状态，不抛
        tracker.invalidate()
        assertFalse(tracker.isActive(request))

        // 后续 beginRequest 仍能正常工作
        val next = tracker.beginRequest()
        assertTrue(tracker.isActive(next))
    }
}
