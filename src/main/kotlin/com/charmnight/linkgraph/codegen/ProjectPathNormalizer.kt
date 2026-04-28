package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 统一把项目内文件路径收敛为相对项目根目录的稳定表示。
 * 项目外路径保持原样，避免把外部资源错误折叠进当前项目。
 */
object ProjectPathNormalizer {
    fun normalizePlan(
        plan: GenerationPlan,
        projectBasePath: String?,
    ): GenerationPlan {
        return plan.copy(
            items = plan.items.map { item -> normalizePlanItem(item, projectBasePath) },
        )
    }

    fun normalizeDraftResult(
        result: CodeGenerationResult,
        projectBasePath: String?,
    ): CodeGenerationResult {
        return result.copy(
            drafts = result.drafts.map { draft -> normalizeDraft(draft, projectBasePath) },
        )
    }

    fun normalizeDraft(
        draft: GeneratedCodeDraft,
        projectBasePath: String?,
    ): GeneratedCodeDraft {
        return draft.copy(
            targetPath = normalizePath(draft.targetPath, projectBasePath),
            editOperations = draft.editOperations.map { operation ->
                operation.copy(filePath = normalizePath(operation.filePath, projectBasePath))
            },
            editScopes = draft.editScopes.map { scope -> normalizeEditScope(scope, projectBasePath) },
        )
    }

    fun normalizePath(
        rawPath: String,
        projectBasePath: String?,
    ): String {
        val basePath = safePath(projectBasePath)?.takeIf(Path::isAbsolute)?.normalize()
        val candidate = safePath(rawPath)?.normalize()
        if (candidate == null) {
            return rawPath.trim().replace('\\', '/')
        }
        if (basePath != null) {
            if (candidate.isAbsolute) {
                if (candidate.startsWith(basePath)) {
                    return basePath.relativize(candidate).toPortablePath()
                }
                return candidate.toPortablePath()
            }
            val resolved = basePath.resolve(candidate).normalize()
            if (resolved.startsWith(basePath)) {
                return basePath.relativize(resolved).toPortablePath()
            }
        }
        return candidate.toPortablePath()
    }

    fun resolvePath(
        rawPath: String,
        projectBasePath: String?,
    ): Path? {
        val candidate = safePath(rawPath)?.normalize() ?: return null
        if (candidate.isAbsolute) {
            return candidate
        }
        val basePath = safePath(projectBasePath)?.takeIf(Path::isAbsolute)?.normalize() ?: return candidate
        return basePath.resolve(candidate).normalize()
    }

    private fun normalizePlanItem(
        item: GenerationPlanItem,
        projectBasePath: String?,
    ): GenerationPlanItem {
        return item.copy(
            targetPath = item.targetPath?.let { path -> normalizePath(path, projectBasePath) },
        )
    }

    private fun normalizeEditScope(
        scope: EditScope,
        projectBasePath: String?,
    ): EditScope {
        return scope.copy(filePath = normalizePath(scope.filePath, projectBasePath))
    }

    private fun safePath(rawPath: String?): Path? {
        val candidate = rawPath?.trim()?.takeIf(String::isNotBlank) ?: return null
        return try {
            Path.of(candidate)
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun Path.toPortablePath(): String = toString().replace('\\', '/')
}
