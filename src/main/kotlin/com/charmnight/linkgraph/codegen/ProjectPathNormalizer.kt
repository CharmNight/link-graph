package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanItem
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 统一把项目内文件路径收敛为相对项目根目录的稳定表示。
 * 项目外路径保持原样，避免把外部资源错误折叠进当前项目。
 */
object ProjectPathNormalizer {
    /**
     * 归一化生成计划：逐条把计划项中的目标路径统一为相对项目根的表示。
     *
     * @param plan 原始生成计划
     * @param projectBasePath 项目根路径，绝对路径才会触发相对化
     * @return 路径归一化后的生成计划
     */
    fun normalizePlan(
        plan: GenerationPlan,
        projectBasePath: String?,
    ): GenerationPlan {
        return plan.copy(
            items = plan.items.map { item -> normalizePlanItem(item, projectBasePath) },
        )
    }

    /**
     * 归一化代码生成结果：把所有草稿的路径批量处理为相对项目根的表示。
     *
     * @param result 原始代码生成结果
     * @param projectBasePath 项目根路径
     * @return 路径归一化后的生成结果
     */
    fun normalizeDraftResult(
        result: CodeGenerationResult,
        projectBasePath: String?,
    ): CodeGenerationResult {
        return result.copy(
            drafts = result.drafts.map { draft -> normalizeDraft(draft, projectBasePath) },
        )
    }

    /**
     * 归一化单个代码草稿：处理目标路径、编辑操作和编辑范围三类路径。
     *
     * @param draft 原始代码草稿
     * @param projectBasePath 项目根路径
     * @return 路径归一化后的草稿
     */
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

    /**
     * 把单条路径归一化：项目内的绝对路径会折叠为相对根目录的表示，
     * 项目外的绝对路径保持原样，外部库或非法路径尽量退化为可移植字符串。
     *
     * @param rawPath 原始路径字符串
     * @param projectBasePath 项目根路径，仅绝对路径生效
     * @return 归一化后的可移植路径字符串（统一使用正斜杠）
     */
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

    /**
     * 把原始路径解析为系统 [Path] 对象：绝对路径直接返回，
     * 相对路径会基于项目根拼接，得到用于实际文件读取的路径。
     *
     * @param rawPath 原始路径字符串
     * @param projectBasePath 项目根路径，仅绝对路径生效
     * @return 解析后的 [Path]，无法解析或非法时返回 null 或原相对路径
     */
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

    /**
     * 归一化单条生成计划项：仅处理可选的目标路径字段。
     */
    private fun normalizePlanItem(
        item: GenerationPlanItem,
        projectBasePath: String?,
    ): GenerationPlanItem {
        return item.copy(
            targetPath = item.targetPath?.let { path -> normalizePath(path, projectBasePath) },
        )
    }

    /**
     * 归一化编辑范围：把范围绑定的文件路径统一为相对项目根的表示。
     */
    private fun normalizeEditScope(
        scope: EditScope,
        projectBasePath: String?,
    ): EditScope {
        return scope.copy(filePath = normalizePath(scope.filePath, projectBasePath))
    }

    /**
     * 安全解析路径字符串：去除前后空白，空串或非法路径统一返回 null，
     * 避免上层因路径异常中断流程。
     */
    private fun safePath(rawPath: String?): Path? {
        val candidate = rawPath?.trim()?.takeIf(String::isNotBlank) ?: return null
        return try {
            Path.of(candidate)
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** 把路径转换为可移植表示：统一使用正斜杠，便于跨平台存储与对比。 */
    private fun Path.toPortablePath(): String = toString().replace('\\', '/')
}
