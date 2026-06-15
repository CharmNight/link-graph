package com.charmnight.linkgraph.architecture.memory

data class ProjectSliceManifest(
    val projectLocationHash: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val slices: List<ProjectSlice> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

data class ProjectSlice(
    val id: String,
    val moduleName: String?,
    val contentRoot: String,
    val sourceSet: String,
    val packagePrefix: String?,
    val kind: String,
    val files: List<ProjectFileFingerprint> = emptyList(),
)

data class ProjectFileFingerprint(
    val relativePath: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String? = null,
)

data class ProjectSliceInputFile(
    val moduleName: String?,
    val contentRoot: String,
    val relativePath: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String? = null,
    val attachedJarFingerprint: String? = null,
)

enum class ProjectSliceKind {
    JVM_SOURCE,
    RESOURCE,
    ATTACHED_JAR,
}
