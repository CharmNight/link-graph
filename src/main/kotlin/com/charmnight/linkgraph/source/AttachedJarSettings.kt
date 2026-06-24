package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path

/**
 * 附带 JAR 配置条目。
 *
 * 描述一个被用户主动挂载到项目里的 jar 文件（class jar），
 * 可选携带对应的 source jar 用于显示源码。
 */
data class AttachedJarEntry(
    /** class jar 文件路径。 */
    var path: String = "",
    /** source jar 文件路径；可空。 */
    var sourceJarPath: String? = null,
    /** 是否启用；false 时该条目被忽略。 */
    var enabled: Boolean = true,
) {
    /** 返回去空白后的副本；空 sourceJarPath 被规范化为 null。 */
    fun normalized(): AttachedJarEntry =
        copy(
            path = path.trim(),
            sourceJarPath = sourceJarPath?.trim()?.takeIf(String::isNotBlank),
        )
}

/** 校验结果。 */
data class AttachedJarValidationResult(
    /** 是否通过。 */
    val ok: Boolean,
    /** 失败原因；通过时为 null。 */
    val message: String? = null,
) {
    companion object {
        /** 通过结果的单例。 */
        val OK = AttachedJarValidationResult(ok = true)
    }
}

/**
 * 附带 JAR 配置校验器。
 *
 * 集中校验所有 jar 路径：必须存在、必须是 .jar 文件、不能为空。
 * 一旦发现非法条目立即返回，避免无意义继续校验。
 */
object AttachedJarSettingsValidator {
    /**
     * 校验一组配置条目。
     *
     * @param entries 待校验条目列表
     * @return 校验结果；OK 表示全部通过
     */
    fun validate(entries: List<AttachedJarEntry>): AttachedJarValidationResult {
        // 跳过明显无效条目（disabled 且无内容），避免误报
        entries.map(AttachedJarEntry::normalized)
            .filter { entry -> entry.enabled || entry.path.isNotBlank() || !entry.sourceJarPath.isNullOrBlank() }
            .forEach { entry ->
                // 先校验 class jar
                val classJar = validateJarPath(entry.path, "附加 JAR")
                if (!classJar.ok) {
                    return classJar
                }
                // 再校验 source jar（若有）
                entry.sourceJarPath?.let { sourceJarPath ->
                    val sourceJar = validateJarPath(sourceJarPath, "附加 source JAR")
                    if (!sourceJar.ok) {
                        return sourceJar
                    }
                }
            }
        return AttachedJarValidationResult.OK
    }

    /**
     * 校验单个 jar 路径。
     * 步骤：非空 → 路径合法 → 文件存在且是普通文件 → 扩展名为 .jar。
     */
    private fun validateJarPath(
        rawPath: String,
        label: String,
    ): AttachedJarValidationResult {
        if (rawPath.isBlank()) {
            return AttachedJarValidationResult(ok = false, message = "$label 路径不能为空。")
        }
        val path = runCatching { Path.of(rawPath).normalize() }.getOrNull()
            ?: return AttachedJarValidationResult(ok = false, message = "$label 路径无效：$rawPath")
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return AttachedJarValidationResult(ok = false, message = "$label 不存在：$rawPath")
        }
        if (!path.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            return AttachedJarValidationResult(ok = false, message = "$label 必须是 .jar 文件：$rawPath")
        }
        return AttachedJarValidationResult.OK
    }
}
