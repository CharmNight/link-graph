package com.charmnight.linkgraph.application.request

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncRequestRegistryTest {
    @Test
    fun requestIdsIncreaseIndependentlyPerScene() {
        val registry = AsyncRequestRegistry()

        assertEquals(1L, registry.beginRequest(AsyncRequestScene.QA))
        assertEquals(2L, registry.beginRequest(AsyncRequestScene.QA))
        assertEquals(1L, registry.beginRequest(AsyncRequestScene.DIFF_REVIEW))
    }

    @Test
    fun newerRequestReplacesOnlyTheSameScene() {
        val registry = AsyncRequestRegistry()
        val oldQa = registry.beginRequest(AsyncRequestScene.QA)
        val diff = registry.beginRequest(AsyncRequestScene.DIFF_REVIEW)
        val newQa = registry.beginRequest(AsyncRequestScene.QA)

        assertFalse(registry.finishRequest(AsyncRequestScene.QA, oldQa))
        assertTrue(registry.finishRequest(AsyncRequestScene.DIFF_REVIEW, diff))
        assertTrue(registry.finishRequest(AsyncRequestScene.QA, newQa))
        assertFalse(registry.finishRequest(AsyncRequestScene.QA, newQa))
    }

    @Test
    fun invalidateAllEndsEverySceneInOneGeneration() {
        val registry = AsyncRequestRegistry()
        val active = AsyncRequestScene.entries.associateWith(registry::beginRequest)

        registry.invalidateAll()

        active.forEach { (scene, requestId) ->
            assertFalse(registry.finishRequest(scene, requestId), "旧 generation 不得完成: $scene")
        }

        val next = AsyncRequestScene.entries.associateWith(registry::beginRequest)
        next.forEach { (scene, requestId) ->
            assertTrue(registry.finishRequest(scene, requestId), "新 generation 应可完成: $scene")
        }
    }

    @Test
    fun activeCheckRejectsInvalidatedRequestsAndAcceptsNewGeneration() {
        val registry = AsyncRequestRegistry()
        val oldRequest = registry.beginRequest(AsyncRequestScene.QA)

        assertTrue(registry.isRequestActive(AsyncRequestScene.QA, oldRequest))
        registry.invalidateAll()
        assertFalse(registry.isRequestActive(AsyncRequestScene.QA, oldRequest))

        val newRequest = registry.beginRequest(AsyncRequestScene.QA)
        assertTrue(registry.isRequestActive(AsyncRequestScene.QA, newRequest))
    }

    @Test
    fun invalidateAllReportsExactlyTheRequestsRemovedAtTheBoundary() {
        val registry = AsyncRequestRegistry()
        val qa = registry.beginRequest(AsyncRequestScene.QA)
        val codeDraft = registry.beginRequest(AsyncRequestScene.CODE_DRAFT)

        val invalidated = registry.invalidateAll()

        assertEquals(qa, invalidated.qaRequestId)
        assertEquals(codeDraft, invalidated.codeDraftRequestId)
        assertEquals(null, invalidated.diffReviewRequestId)
    }

    @Test
    fun concurrentBeginAndInvalidateProduceOnlyLegalLinearizedOutcomes() {
        val registry = AsyncRequestRegistry()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(250) {
                val oldRequest = registry.beginRequest(AsyncRequestScene.QA)
                val barrier = CyclicBarrier(3)
                val invalidate = executor.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    registry.invalidateAll()
                }
                val begin = executor.submit<Long> {
                    barrier.await(5, TimeUnit.SECONDS)
                    registry.beginRequest(AsyncRequestScene.QA)
                }

                barrier.await(5, TimeUnit.SECONDS)
                invalidate.get(5, TimeUnit.SECONDS)
                val concurrentRequest = begin.get(5, TimeUnit.SECONDS)

                assertFalse(registry.finishRequest(AsyncRequestScene.QA, oldRequest))
                val concurrentRequestSurvived = registry.finishRequest(
                    AsyncRequestScene.QA,
                    concurrentRequest,
                )
                if (!concurrentRequestSurvived) {
                    val requestAfterBoundary = registry.beginRequest(AsyncRequestScene.QA)
                    assertTrue(registry.finishRequest(AsyncRequestScene.QA, requestAfterBoundary))
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
