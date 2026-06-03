package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path

data class AttachedJarEntry(
    var path: String = "",
    var sourceJarPath: String? = null,
    var enabled: Boolean = true,
) {
    fun normalized(): AttachedJarEntry =
        copy(
            path = path.trim(),
            sourceJarPath = sourceJarPath?.trim()?.takeIf(String::isNotBlank),
        )
}

data class AttachedJarValidationResult(
    val ok: Boolean,
    val message: String? = null,
) {
    companion object {
        val OK = AttachedJarValidationResult(ok = true)
    }
}

object AttachedJarSettingsValidator {
    fun validate(entries: List<AttachedJarEntry>): AttachedJarValidationResult {
        entries.map(AttachedJarEntry::normalized)
            .filter { entry -> entry.enabled || entry.path.isNotBlank() || !entry.sourceJarPath.isNullOrBlank() }
            .forEach { entry ->
                val classJar = validateJarPath(entry.path, "附加 JAR")
                if (!classJar.ok) {
                    return classJar
                }
                entry.sourceJarPath?.let { sourceJarPath ->
                    val sourceJar = validateJarPath(sourceJarPath, "附加 source JAR")
                    if (!sourceJar.ok) {
                        return sourceJar
                    }
                }
            }
        return AttachedJarValidationResult.OK
    }

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
