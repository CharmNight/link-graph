package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.toolwindow.UiThreadExecutor
import com.charmnight.linkgraph.toolwindow.UiThreadOwnedResource
import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiThreadOwnedResourceTest {
    @Test
    fun createsResourceOnlyOnceThroughUiThreadBoundary() {
        val executor = FakeUiThreadExecutor()
        var createCount = 0
        val resource = UiThreadOwnedResource(
            uiThreadExecutor = executor,
            factory = {
                createCount += 1
                Any()
            },
        )

        val first = resource.getOrCreate()
        val second = resource.getOrCreate()

        assertSame(first, second)
        assertEquals(1, createCount)
        assertEquals(2, executor.invokeAndWaitCount)
    }

    @Test
    fun schedulesExistingResourceAccessBackToUiThread() {
        val executor = FakeUiThreadExecutor()
        val resource = UiThreadOwnedResource(
            uiThreadExecutor = executor,
            factory = { "panel" },
        )
        resource.getOrCreate()

        var received: String? = null
        resource.withExisting { panel ->
            received = panel
            assertTrue(executor.isOnUiThread)
        }

        assertEquals(null, received)
        executor.drain()
        assertEquals("panel", received)
        assertEquals(1, executor.invokeLaterCount)
    }

    @Test
    fun checksIdentityAgainstCurrentUiOwnedInstance() {
        val executor = FakeUiThreadExecutor()
        val resource = UiThreadOwnedResource(
            uiThreadExecutor = executor,
            factory = { Any() },
        )
        val current = resource.getOrCreate()

        assertTrue(resource.isCurrent(current))
        assertFalse(resource.isCurrent(Any()))
    }

    @Test
    fun releaseDisposesCurrentResourceExactlyOnce() {
        val executor = FakeUiThreadExecutor()
        val disposed = mutableListOf<String>()
        var createCount = 0
        val resource = UiThreadOwnedResource(
            uiThreadExecutor = executor,
            factory = {
                createCount += 1
                "panel-$createCount"
            },
            disposer = { resource ->
                disposed += resource
            },
        )
        val first = resource.getOrCreate()

        resource.release()
        resource.release()

        val second = resource.getOrCreate()

        assertEquals(listOf("panel-1"), disposed)
        assertEquals(2, createCount)
        assertEquals("panel-2", second)
        assertTrue(resource.isCurrent(second))
        assertFalse(resource.isCurrent(first))
    }

    private class FakeUiThreadExecutor : UiThreadExecutor {
        var invokeAndWaitCount: Int = 0
            private set
        var invokeLaterCount: Int = 0
            private set
        var isOnUiThread: Boolean = false
            private set
        private val queuedActions = mutableListOf<() -> Unit>()

        override fun <T> invokeAndWait(action: () -> T): T {
            invokeAndWaitCount += 1
            val previous = isOnUiThread
            isOnUiThread = true
            return try {
                action()
            } finally {
                isOnUiThread = previous
            }
        }

        override fun invokeLater(action: () -> Unit) {
            invokeLaterCount += 1
            queuedActions += action
        }

        fun drain() {
            while (queuedActions.isNotEmpty()) {
                val next = queuedActions.removeAt(0)
                val previous = isOnUiThread
                isOnUiThread = true
                try {
                    next()
                } finally {
                    isOnUiThread = previous
                }
            }
        }
    }
}
