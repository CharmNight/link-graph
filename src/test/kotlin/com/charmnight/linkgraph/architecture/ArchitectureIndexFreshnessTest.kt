package com.charmnight.linkgraph.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchitectureIndexFreshnessTest {
    @Test
    fun trackerStartsFreshThenRecordsDirtyFilesAndIndexSuccess() {
        val tracker = ArchitectureIndexFreshnessTracker()

        val initial = tracker.snapshot()
        assertEquals("FRESH", initial.state)
        assertNull(initial.dirtyReason)
        assertEquals(0, initial.pendingFileCount)
        assertNull(initial.lastIndexedAtEpochMillis)
        assertNull(initial.staleSinceEpochMillis)

        tracker.markDirty(
            dirtyReason = "VFS_CHANGE",
            paths = listOf(
                "/project/src/main/java/com/example/TaskRunner.java",
                "/project/src/main/resources/application.yml",
                "/project/src/main/kotlin/com/example/TaskController.kt",
                "/project/README.md",
                "/project/src/main/resources/schema.sql",
                "/project/src/main/java/com/example/Extra.java",
            ),
        )

        val stale = tracker.snapshot()
        assertEquals("STALE", stale.state)
        assertEquals("VFS_CHANGE", stale.dirtyReason)
        assertEquals(6, stale.pendingFileCount)
        assertEquals(
            listOf(
                "/project/README.md",
                "/project/src/main/java/com/example/Extra.java",
                "/project/src/main/java/com/example/TaskRunner.java",
                "/project/src/main/kotlin/com/example/TaskController.kt",
                "/project/src/main/resources/application.yml",
            ),
            stale.pendingFileSamples,
        )
        assertNotNull(stale.staleSinceEpochMillis)

        tracker.markIndexed()

        val fresh = tracker.snapshot()
        assertEquals("FRESH", fresh.state)
        assertNull(fresh.dirtyReason)
        assertEquals(0, fresh.pendingFileCount)
        assertEquals(emptyList(), fresh.pendingFileSamples)
        assertNotNull(fresh.lastIndexedAtEpochMillis)
        assertNull(fresh.staleSinceEpochMillis)
        assertTrue(fresh.lastIndexedAtEpochMillis!! >= stale.staleSinceEpochMillis!!)
    }
}
