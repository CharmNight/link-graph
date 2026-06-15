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

    @Test
    fun trackerReportsBuildingThenReturnsToStaleWhenBuildFails() {
        var now = 1_000L
        val tracker = ArchitectureIndexFreshnessTracker(clockMillis = { now })
        tracker.markDirty(
            dirtyReason = "VFS_CHANGE",
            paths = listOf("/project/src/main/kotlin/com/example/OrderService.kt"),
        )

        now = 1_100L
        val buildToken = tracker.markBuilding()

        val building = tracker.snapshot()
        assertEquals("BUILDING", building.state)
        assertEquals("VFS_CHANGE", building.dirtyReason)
        assertEquals(1, building.pendingFileCount)
        assertEquals(1_000L, building.staleSinceEpochMillis)
        assertNull(building.lastIndexedAtEpochMillis)

        now = 1_200L
        tracker.markBuildFailed(buildToken)

        val stale = tracker.snapshot()
        assertEquals("STALE", stale.state)
        assertEquals("VFS_CHANGE", stale.dirtyReason)
        assertEquals(1, stale.pendingFileCount)
        assertEquals(1_000L, stale.staleSinceEpochMillis)
        assertNull(stale.lastIndexedAtEpochMillis)
    }

    @Test
    fun trackerDoesNotMarkFreshWhenDirtyFilesArriveDuringBuild() {
        var now = 2_000L
        val tracker = ArchitectureIndexFreshnessTracker(clockMillis = { now })

        val buildToken = tracker.markBuilding()
        val buildingFromFresh = tracker.snapshot()
        assertEquals("BUILDING", buildingFromFresh.state)
        assertNull(buildingFromFresh.dirtyReason)
        assertEquals(0, buildingFromFresh.pendingFileCount)

        now = 2_100L
        tracker.markDirty(
            dirtyReason = "VFS_CHANGE",
            paths = listOf("/project/src/main/kotlin/com/example/OrderController.kt"),
        )

        val dirtyDuringBuild = tracker.snapshot()
        assertEquals("BUILDING", dirtyDuringBuild.state)
        assertEquals("VFS_CHANGE", dirtyDuringBuild.dirtyReason)
        assertEquals(1, dirtyDuringBuild.pendingFileCount)
        assertEquals(2_100L, dirtyDuringBuild.staleSinceEpochMillis)

        now = 2_200L
        tracker.markIndexed(buildToken)

        val stale = tracker.snapshot()
        assertEquals("STALE", stale.state)
        assertEquals("VFS_CHANGE", stale.dirtyReason)
        assertEquals(1, stale.pendingFileCount)
        assertEquals(2_100L, stale.staleSinceEpochMillis)
        assertEquals(2_200L, stale.lastIndexedAtEpochMillis)
    }
}
