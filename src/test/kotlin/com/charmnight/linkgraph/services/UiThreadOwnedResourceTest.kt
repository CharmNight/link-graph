package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.toolwindow.UiThreadExecutor
import com.charmnight.linkgraph.toolwindow.UiThreadOwnedResource
import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

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
        val constructor = UiThreadOwnedResource::class.constructors.firstOrNull { it.parameters.size == 3 }
            ?: fail("Expected UiThreadOwnedResource to accept a disposer callback")
        val resource = constructor.call(
            executor,
            {
                createCount += 1
                "panel-$createCount"
            },
            { resource: String ->
                disposed += resource
            },
        ) as UiThreadOwnedResource<String>
        val first = resource.getOrCreate()

        val release = resource::class.members.firstOrNull { it.name == "release" }
            ?: fail("Expected UiThreadOwnedResource to expose release()")
        release.call(resource)
        release.call(resource)

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
