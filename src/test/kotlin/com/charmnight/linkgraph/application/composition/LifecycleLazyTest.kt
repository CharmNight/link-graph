package com.charmnight.linkgraph.application.composition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LifecycleLazyTest {
    @Test
    fun concurrentFirstAccessInitializesExactlyOnce() {
        val initializations = AtomicInteger()
        val lazy = LifecycleLazy(
            initializer = {
                initializations.incrementAndGet()
                Any()
            },
            disposer = {},
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = List(16) {
                executor.submit<Any> {
                    start.await(5, TimeUnit.SECONDS)
                    lazy.value
                }
            }

            start.countDown()

            val values = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, initializations.get())
            values.forEach { value -> assertSame(values.first(), value) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun failedInitializationCanBeRetried() {
        var attempts = 0
        val lazy = LifecycleLazy(
            initializer = {
                attempts += 1
                if (attempts == 1) error("first attempt failed")
                "value"
            },
            disposer = {},
        )

        assertFailsWith<IllegalStateException> { lazy.value }

        assertEquals("value", lazy.value)
        assertEquals(2, attempts)
    }

    @Test
    fun disposeDoesNotInitializeUnusedValue() {
        var initializations = 0
        val lazy = LifecycleLazy<String>(
            initializer = {
                initializations += 1
                "value"
            },
            disposer = {},
        )

        lazy.dispose()

        assertEquals(0, initializations)
        assertFalse(lazy.isInitialized())
        assertFailsWith<IllegalStateException> { lazy.value }
    }

    @Test
    fun disposeWaitsForConcurrentInitializationAndDisposesPublishedValue() {
        val initializerStarted = CountDownLatch(1)
        val releaseInitializer = CountDownLatch(1)
        val disposedValues = mutableListOf<String>()
        val lazy = LifecycleLazy<String>(
            initializer = {
                initializerStarted.countDown()
                releaseInitializer.await(5, TimeUnit.SECONDS)
                "value"
            },
            disposer = { value: String -> disposedValues.add(value) },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val valueFuture = executor.submit<String> { lazy.value }
            initializerStarted.await(5, TimeUnit.SECONDS)
            val disposeFuture = executor.submit { lazy.dispose() }

            releaseInitializer.countDown()

            assertEquals("value", valueFuture.get(5, TimeUnit.SECONDS))
            disposeFuture.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("value"), disposedValues)
            assertFailsWith<IllegalStateException> { lazy.value }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
