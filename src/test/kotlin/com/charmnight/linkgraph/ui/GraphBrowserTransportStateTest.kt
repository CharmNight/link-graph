package com.charmnight.linkgraph.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphBrowserTransportStateTest {
    @Test
    fun replaysOnlyTheLatestPendingSnapshotAfterFrontendReady() {
        val transport = GraphBrowserTransportState(sessionId = "session-1")

        transport.onMainFrameLoadStarted()
        assertNull(transport.onSnapshotAvailable(revision = 1, script = "revision-1"))
        assertNull(transport.onSnapshotAvailable(revision = 2, script = "revision-2"))
        assertNull(transport.onMainFrameLoadEnded())

        assertEquals("revision-2", transport.onFrontendReady(lastAppliedRevision = null)?.script)
    }

    @Test
    fun doesNotReplaySnapshotAlreadyAppliedByTheFrontend() {
        val transport = GraphBrowserTransportState(sessionId = "session-1")

        transport.onMainFrameLoadStarted()
        assertNull(transport.onSnapshotAvailable(revision = 5, script = "revision-5"))
        assertNull(transport.onMainFrameLoadEnded())

        assertNull(transport.onFrontendReady(lastAppliedRevision = 5))
        assertNull(transport.onSnapshotAvailable(revision = 5, script = "revision-5-again"))
        assertEquals("revision-6", transport.onSnapshotAvailable(revision = 6, script = "revision-6")?.script)
        assertNull(transport.onSnapshotAcknowledged(6))
        assertNull(transport.onSnapshotAvailable(revision = 6, script = "revision-6-repeat"))
    }
}
