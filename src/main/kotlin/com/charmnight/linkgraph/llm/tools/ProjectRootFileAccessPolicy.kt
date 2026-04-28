package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 统一约束源码读取只能发生在项目根目录内。
 * 这里使用真实路径比较，避免通过符号链接或相对穿越逃逸到项目外。
 */
class ProjectRootFileAccessPolicy {
    fun resolveReadablePath(
        filePath: String,
        projectBasePath: String?,
    ): Path? {
        val projectRoot = realProjectRoot(projectBasePath) ?: return null
        val candidate = ProjectPathNormalizer.resolvePath(filePath, projectBasePath) ?: return null
        if (!Files.exists(candidate)) {
            return null
        }
        val realCandidate = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        return realCandidate.takeIf { path -> path.startsWith(projectRoot) }
    }

    private fun realProjectRoot(projectBasePath: String?): Path? {
        val basePath = runCatching { Path.of(projectBasePath ?: "") }.getOrNull()
            ?.takeIf(Path::isAbsolute)
            ?.normalize()
            ?: return null
        if (!Files.exists(basePath)) {
            return null
        }
        return runCatching { basePath.toRealPath() }.getOrNull()
    }
}
