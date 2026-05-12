package com.charmnight.linkgraph.codegen

import java.nio.file.Files
import java.nio.file.Path

data class ProjectScopedPath(
    val path: Path,
    val existed: Boolean,
)

class ProjectScopedPathPolicy {
    fun resolveExistingFile(
        projectBasePath: String?,
        targetPath: String,
    ): ProjectScopedPath? {
        val baseRealPath = realProjectRoot(projectBasePath) ?: return null
        val candidate = ProjectPathNormalizer.resolvePath(targetPath, projectBasePath)?.normalize() ?: return null
        if (!Files.exists(candidate)) {
            return null
        }
        val candidateRealPath = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        return ProjectScopedPath(candidate, existed = true)
            .takeIf { candidateRealPath.startsWith(baseRealPath) }
    }

    fun resolveWritableDraftTarget(
        projectBasePath: String?,
        targetPath: String,
    ): ProjectScopedPath? {
        val baseRealPath = realProjectRoot(projectBasePath) ?: return null
        val candidate = ProjectPathNormalizer.resolvePath(targetPath, projectBasePath)?.normalize() ?: return null
        if (Files.exists(candidate)) {
            val candidateRealPath = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
            return ProjectScopedPath(candidate, existed = true)
                .takeIf { candidateRealPath.startsWith(baseRealPath) }
        }
        val existingAncestorRealPath = nearestExistingAncestor(candidate)
            ?.let { ancestor -> runCatching { ancestor.toRealPath() }.getOrNull() }
            ?: return null
        return ProjectScopedPath(candidate, existed = false)
            .takeIf { existingAncestorRealPath.startsWith(baseRealPath) }
    }

    private fun realProjectRoot(projectBasePath: String?): Path? {
        val basePath = runCatching { Path.of(projectBasePath ?: "") }.getOrNull()
            ?.takeIf(Path::isAbsolute)
            ?.normalize()
            ?: return null
        if (!Files.isDirectory(basePath)) {
            return null
        }
        return runCatching { basePath.toRealPath() }.getOrNull()
    }

    private fun nearestExistingAncestor(path: Path): Path? {
        var current: Path? = path.parent
        while (current != null) {
            if (Files.exists(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }
}
