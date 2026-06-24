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
 *
 * 这里使用真实路径比较，避免通过符号链接或相对穿越逃逸到项目外。
 * 这种约束防止 LLM 工具读取系统文件等敏感位置，保证只会读到项目内容。
 */
class ProjectRootFileAccessPolicy {
    /**
     * 解析可读路径。
     *
     * @param filePath 待读取的文件路径（相对或绝对）
     * @param projectBasePath 项目根路径
     * @param project 当前项目；用于回退到 ProjectFileIndex 校验
     * @return 可读的真实路径；不在允许范围内返回 null
     */
    fun resolveReadablePath(
        filePath: String,
        projectBasePath: String?,
        project: Project? = null,
    ): Path? {
        // 先归一化路径；非法路径直接返回 null
        val candidate = ProjectPathNormalizer.resolvePath(filePath, projectBasePath) ?: return null
        if (!Files.exists(candidate)) {
            return null
        }
        // toRealPath 会解析符号链接，避免通过链接逃逸
        val realCandidate = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        val projectRoot = realProjectRoot(projectBasePath)
        // 路径在项目根下：直接允许
        if (projectRoot != null && realCandidate.startsWith(projectRoot)) {
            return realCandidate
        }
        // 不在根下但属于项目内容（例如外部源码附件）：放行
        return realCandidate.takeIf { project != null && isProjectContent(project, candidate) }
    }

    /**
     * 取项目根的真实路径。
     * 失败（路径无效、不存在等）返回 null。
     */
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

    /**
     * 判断路径是否属于项目内容。
     * 通过 IntelliJ 的 ProjectFileIndex 判定，比纯路径比较更准确。
     */
    private fun isProjectContent(project: Project, path: Path): Boolean {
        // 项目已销毁时直接返回 false，避免访问已释放的服务
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
