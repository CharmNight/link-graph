package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphBrowserTransportStateTest {
    @Test
    fun replaysOnlyTheLatestPendingSnapshotAfterFrontendReady() {
        val transport = GraphBrowserTransportState(sessionId = "session-1")

        transport.onMainFrameLoadStarted()
        transport.onSnapshotAvailable(revision = 1, script = "revision-1").also { availability ->
            assertEquals(true, availability.accepted)
            assertNull(availability.transport)
        }
        transport.onSnapshotAvailable(revision = 2, script = "revision-2").also { availability ->
            assertEquals(true, availability.accepted)
            assertNull(availability.transport)
        }
        assertNull(transport.onMainFrameLoadEnded())

        assertEquals("revision-2", transport.onFrontendReady(lastAppliedRevision = null)?.script)
    }

    @Test
    fun doesNotReplaySnapshotAlreadyAppliedByTheFrontend() {
        val transport = GraphBrowserTransportState(sessionId = "session-1")

        transport.onMainFrameLoadStarted()
        transport.onSnapshotAvailable(revision = 5, script = "revision-5").also { availability ->
            assertEquals(true, availability.accepted)
            assertNull(availability.transport)
        }
        assertNull(transport.onMainFrameLoadEnded())

        assertNull(transport.onFrontendReady(lastAppliedRevision = 5))
        transport.onSnapshotAvailable(revision = 5, script = "revision-5-again").also { availability ->
            assertEquals(false, availability.accepted)
            assertNull(availability.transport)
        }
        transport.onSnapshotAvailable(revision = 6, script = "revision-6").also { availability ->
            assertEquals(true, availability.accepted)
            assertEquals("revision-6", availability.transport?.script)
        }
        assertNull(transport.onSnapshotAcknowledged(6))
        transport.onSnapshotAvailable(revision = 6, script = "revision-6-repeat").also { availability ->
            assertEquals(false, availability.accepted)
            assertNull(availability.transport)
        }
    }

    @Test
    fun doesNotReplayAlreadyDispatchedPendingSnapshotAfterFrameReload() {
        val transport = GraphBrowserTransportState(sessionId = "session-1")

        transport.onMainFrameLoadStarted()
        assertNull(transport.onSnapshotAvailable(revision = 7, script = "revision-7").transport)
        assertNull(transport.onMainFrameLoadEnded())
        assertEquals("revision-7", transport.onFrontendReady(lastAppliedRevision = null)?.script)

        transport.onMainFrameLoadStarted()
        assertNull(transport.onMainFrameLoadEnded())
        assertNull(transport.onFrontendReady(lastAppliedRevision = null))
    }
}
