package com.charmnight.linkgraph.architecture.memory

/** 项目文件变更条目；用相对路径描述，便于跨工作区复用。 */
data class ProjectFileChange(
    /** 相对于项目根的文件路径。 */
    val relativePath: String,
)

/**
 * 架构索引失效计划。
 *
 * 描述一次文件变更对索引的影响：是全量失效（结构变更）还是局部失效（仅部分 slice）。
 * 索引器据此决定重建范围，避免无差别的全量重建。
 */
data class ArchitectureIndexInvalidationPlan(
    /** 是否需要全量失效。 */
    val fullInvalidation: Boolean,
    /** 需要失效的 slice ID 集合；全量失效时通常包含全部 slice。 */
    val staleSliceIds: Set<String> = emptySet(),
    /** 失效原因代码，便于日志追踪。 */
    val dirtyReason: String = if (fullInvalidation) "FULL_INVALIDATION" else "SLICE_INVALIDATION",
)

/**
 * 架构索引失效计划器。
 *
 * 根据变更文件列表与当前 slice 清单，判断哪些 slice 需要重建，
 * 或者必须做全量重建（例如依赖配置变化）。
 */
class ArchitectureIndexInvalidationPlanner {
    /**
     * 规划失效范围。
     *
     * @param manifest 当前 slice 清单
     * @param changes 自上次索引以来的文件变更列表
     * @return 失效计划
     */
    fun plan(
        manifest: ProjectSliceManifest,
        changes: List<ProjectFileChange>,
    ): ArchitectureIndexInvalidationPlan {
        // 路径归一化，避免分隔符差异（/ vs \）影响判断
        val normalizedChanges = changes.map { change -> change.relativePath.normalizePath() }
        // 任何"硬变更"（如 jar、构建脚本、IDE 配置）都需要全量失效
        if (normalizedChanges.any(::requiresFullInvalidation)) {
            return ArchitectureIndexInvalidationPlan(fullInvalidation = true, staleSliceIds = manifest.slices.mapTo(linkedSetOf(), ProjectSlice::id))
        }
        // 否则只失效包含变更文件的 slice
        val stale = manifest.slices
            .filter { slice ->
                val files = slice.files.mapTo(hashSetOf(), ProjectFileFingerprint::relativePath)
                normalizedChanges.any { path -> path in files }
            }
            .mapTo(linkedSetOf(), ProjectSlice::id)
        return ArchitectureIndexInvalidationPlan(fullInvalidation = false, staleSliceIds = stale)
    }

    /**
     * 判断给定路径是否构成"硬变更"——必须全量重建索引。
     * 包括：jar（依赖变化）、构建脚本、IDE 配置目录。
     */
    private fun requiresFullInvalidation(path: String): Boolean =
        path.endsWith(".jar") ||
            path.endsWith("settings.gradle") ||
            path.endsWith("settings.gradle.kts") ||
            path.endsWith("pom.xml") ||
            path.startsWith(".idea/") ||
            path.contains("/.idea/")
}
