package com.charmnight.linkgraph.codegen

import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目内路径解析结果。
 *
 * @property path 解析后的路径
 * @property existed 解析时该路径是否已存在
 */
data class ProjectScopedPath(
    val path: Path,
    val existed: Boolean,
)

/**
 * 项目内路径策略。
 *
 * 把"用户/工具给出的路径"约束在项目根下，避免代码生成越权写到项目外。
 * 同时处理符号链接：通过 toRealPath 解析真实位置后再做范围判断。
 */
class ProjectScopedPathPolicy {
    /**
     * 解析已存在的文件路径。
     *
     * @param projectBasePath 项目根路径
     * @param targetPath 待解析的目标路径
     * @return 项目内的已存在文件路径；不在范围内或不存在返回 null
     */
    fun resolveExistingFile(
        projectBasePath: String?,
        targetPath: String,
    ): ProjectScopedPath? {
        val baseRealPath = realProjectRoot(projectBasePath) ?: return null
        val candidate = ProjectPathNormalizer.resolvePath(targetPath, projectBasePath)?.normalize() ?: return null
        // 不存在直接返回 null
        if (!Files.exists(candidate)) {
            return null
        }
        val candidateRealPath = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        // 解析符号链接后必须仍在项目根下
        return ProjectScopedPath(candidate, existed = true)
            .takeIf { candidateRealPath.startsWith(baseRealPath) }
    }

    /**
     * 解析可写的草稿目标路径。
     *
     * 与 [resolveExistingFile] 区别：本方法允许路径不存在（用于新建文件），
     * 但路径的最近存在祖先必须在项目根下，避免在项目外创建新文件。
     */
    fun resolveWritableDraftTarget(
        projectBasePath: String?,
        targetPath: String,
    ): ProjectScopedPath? {
        val baseRealPath = realProjectRoot(projectBasePath) ?: return null
        val candidate = ProjectPathNormalizer.resolvePath(targetPath, projectBasePath)?.normalize() ?: return null
        // 已存在：走文件路径校验
        if (Files.exists(candidate)) {
            val candidateRealPath = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
            return ProjectScopedPath(candidate, existed = true)
                .takeIf { candidateRealPath.startsWith(baseRealPath) }
        }
        // 不存在：向上找最近的已存在祖先，校验祖先在项目内
        val existingAncestorRealPath = nearestExistingAncestor(candidate)
            ?.let { ancestor -> runCatching { ancestor.toRealPath() }.getOrNull() }
            ?: return null
        return ProjectScopedPath(candidate, existed = false)
            .takeIf { existingAncestorRealPath.startsWith(baseRealPath) }
    }

    /**
     * 取项目根的真实路径。
     * 必须是已存在的目录；否则返回 null。
     */
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

    /**
     * 向上查找最近的已存在祖先目录。
     * 用于新建文件场景下判断"目标路径是否在项目内"。
     */
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
