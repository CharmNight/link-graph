package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness

data class ArchitectureIndexFreshnessSnapshot(
    val state: String = "FRESH",
    val dirtyReason: String? = null,
    val pendingFileCount: Int = 0,
    val pendingFileSamples: List<String> = emptyList(),
    val lastIndexedAtEpochMillis: Long? = null,
    val staleSinceEpochMillis: Long? = null,
)

class ArchitectureIndexFreshnessTracker(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var state: String = "FRESH"
    private var dirtyReason: String? = null
    private var pendingFiles: Set<String> = emptySet()
    private var lastIndexedAtEpochMillis: Long? = null
    private var staleSinceEpochMillis: Long? = null
    private var dirtyGeneration: Long = 0

    fun markDirty(dirtyReason: String, paths: List<String>) {
        synchronized(lock) {
            if (state != "BUILDING") {
                state = "STALE"
            }
            this.dirtyReason = dirtyReason
            pendingFiles = (pendingFiles + paths.filter(String::isNotBlank)).toSortedSet()
            if (staleSinceEpochMillis == null) {
                staleSinceEpochMillis = clockMillis()
            }
            dirtyGeneration += 1
        }
    }

    fun markBuilding(): Long =
        synchronized(lock) {
            state = "BUILDING"
            dirtyGeneration
        }

    fun markIndexed(buildToken: Long? = null) {
        synchronized(lock) {
            lastIndexedAtEpochMillis = clockMillis()
            if (buildToken != null && buildToken != dirtyGeneration) {
                state = "STALE"
                return
            }
            state = "FRESH"
            dirtyReason = null
            pendingFiles = emptySet()
            staleSinceEpochMillis = null
        }
    }

    fun markBuildFailed(buildToken: Long? = null) {
        synchronized(lock) {
            state = "STALE"
            if (dirtyReason == null) {
                dirtyReason = "INDEX_BUILD_FAILED"
            }
            if (staleSinceEpochMillis == null) {
                staleSinceEpochMillis = clockMillis()
            }
        }
    }

    fun snapshot(): ArchitectureIndexFreshnessSnapshot =
        synchronized(lock) {
            ArchitectureIndexFreshnessSnapshot(
                state = state,
                dirtyReason = dirtyReason,
                pendingFileCount = pendingFiles.size,
                pendingFileSamples = pendingFiles.take(MAX_PENDING_FILE_SAMPLES),
                lastIndexedAtEpochMillis = lastIndexedAtEpochMillis,
                staleSinceEpochMillis = staleSinceEpochMillis,
            )
        }

    private companion object {
        const val MAX_PENDING_FILE_SAMPLES = 5
    }
}

fun ArchitectureIndexFreshnessSnapshot.toIndexedFreshness(): IndexedGraphFreshness =
    IndexedGraphFreshness(
        state = state,
        dirtyReason = dirtyReason,
        pendingFileCount = pendingFileCount,
        pendingFileSamples = pendingFileSamples,
        lastIndexedAtEpochMillis = lastIndexedAtEpochMillis,
        staleSinceEpochMillis = staleSinceEpochMillis,
    )
