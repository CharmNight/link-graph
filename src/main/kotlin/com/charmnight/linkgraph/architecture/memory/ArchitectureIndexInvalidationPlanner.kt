package com.charmnight.linkgraph.architecture.memory

data class ProjectFileChange(
    val relativePath: String,
)

data class ArchitectureIndexInvalidationPlan(
    val fullInvalidation: Boolean,
    val staleSliceIds: Set<String> = emptySet(),
    val dirtyReason: String = if (fullInvalidation) "FULL_INVALIDATION" else "SLICE_INVALIDATION",
)

class ArchitectureIndexInvalidationPlanner {
    fun plan(
        manifest: ProjectSliceManifest,
        changes: List<ProjectFileChange>,
    ): ArchitectureIndexInvalidationPlan {
        val normalizedChanges = changes.map { change -> change.relativePath.normalizePath() }
        if (normalizedChanges.any(::requiresFullInvalidation)) {
            return ArchitectureIndexInvalidationPlan(fullInvalidation = true, staleSliceIds = manifest.slices.mapTo(linkedSetOf(), ProjectSlice::id))
        }
        val stale = manifest.slices
            .filter { slice ->
                val files = slice.files.mapTo(hashSetOf(), ProjectFileFingerprint::relativePath)
                normalizedChanges.any { path -> path in files }
            }
            .mapTo(linkedSetOf(), ProjectSlice::id)
        return ArchitectureIndexInvalidationPlan(fullInvalidation = false, staleSliceIds = stale)
    }

    private fun requiresFullInvalidation(path: String): Boolean =
        path.endsWith(".jar") ||
            path.endsWith("settings.gradle") ||
            path.endsWith("settings.gradle.kts") ||
            path.endsWith("pom.xml") ||
            path.startsWith(".idea/") ||
            path.contains("/.idea/")
}
