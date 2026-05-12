package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
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
        project: Project? = null,
    ): Path? {
        val candidate = ProjectPathNormalizer.resolvePath(filePath, projectBasePath) ?: return null
        if (!Files.exists(candidate)) {
            return null
        }
        val realCandidate = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        val projectRoot = realProjectRoot(projectBasePath)
        if (projectRoot != null && realCandidate.startsWith(projectRoot)) {
            return realCandidate
        }
        return realCandidate.takeIf { project != null && isProjectContent(project, candidate) }
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

    private fun isProjectContent(project: Project, path: Path): Boolean {
        if (project.isDisposed) {
            return false
        }
        val virtualFile = runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.normalize())
        }.getOrNull()
            ?: return false
        return ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(virtualFile)
        }
    }
}
