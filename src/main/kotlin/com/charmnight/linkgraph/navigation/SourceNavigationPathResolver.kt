package com.charmnight.linkgraph.navigation

import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * 统一把链路节点里的“展示路径”转换成 IDEA 可识别的虚拟文件系统路径/URL。
 * 重点补齐普通文件、jar 条目和 JDK jrt 模块三类位置。
 */
internal object SourceNavigationPathResolver {
    /** 匹配 jar/zip 包内条目路径。 */
    private val archivePattern = Regex("""^(.*?\.(?:jar|zip))(?:!/?|/)(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * 规范化压缩包条目路径为 IDEA 可识别的 `archive!/entry` 形式。
     */
    fun normalizeArchiveEntryPath(rawPath: String): String? {
        // 兼容 `jar://` 和纯路径两种输入格式。
        val candidate = rawPath
            .removePrefix("${StandardFileSystems.JAR_PROTOCOL_PREFIX}")
            .removePrefix("jar://")
        // 未命中压缩包路径格式时直接返回空。
        val match = archivePattern.matchEntire(candidate) ?: return null
        val archivePath = match.groupValues[1]
        val entryPath = match.groupValues[2].removePrefix("/")
        return "$archivePath!/$entryPath"
    }

    /**
     * 构造 jrt 模块路径的完整虚拟文件 URL。
     */
    fun buildJrtUrl(rawPath: String): String? {
        // 去掉已存在的协议前缀，统一后续处理逻辑。
        val candidate = rawPath
            .removePrefix("${StandardFileSystems.JRT_PROTOCOL_PREFIX}")
            .removePrefix("jrt://")

        val bangIndex = candidate.indexOf("!/")
        if (bangIndex >= 0) {
            // 已经是 `home!/module/path` 形式时只做最小校验和重建。
            val homePath = candidate.substring(0, bangIndex)
            val entryPath = candidate.substring(bangIndex + 2)
            if (entryPath.count { it == '/' } < 1) {
                return null
            }
            return VirtualFileManager.constructUrl(StandardFileSystems.JRT_PROTOCOL, "$homePath!/$entryPath")
        }

        // 兼容展开后的 jrt 物理路径形式。
        val exploded = parseExplodedJrtPath(candidate) ?: return null
        if (exploded.homePath.isBlank() || exploded.moduleName.isBlank() || exploded.classPath.isBlank()) {
            return null
        }
        return VirtualFileManager.constructUrl(
            StandardFileSystems.JRT_PROTOCOL,
            "${exploded.homePath}!/${exploded.moduleName}/${exploded.classPath}",
        )
    }

    /**
     * 从展开后的 jrt 路径中拆出 home、module 和 classPath。
     */
    private fun parseExplodedJrtPath(candidate: String): ExplodedJrtPath? {
        // 只处理 class 文件路径，避免把普通目录误判成 jrt 入口。
        val trimmedCandidate = candidate.removeSuffix("/")
        if (!trimmedCandidate.endsWith(".class")) {
            return null
        }

        // 记录是否原本带前导斜杠，重建 homePath 时需要保留。
        val hasLeadingSlash = trimmedCandidate.startsWith("/")
        val segments = trimmedCandidate
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() }
        if (segments.size < 3) {
            return null
        }

        // 从后往前寻找看起来像模块名的段，再拆出其后的类路径。
        for (moduleIndex in segments.lastIndex - 2 downTo 1) {
            val moduleName = segments[moduleIndex]
            if (!moduleName.contains('.')) {
                continue
            }
            val classPathSegments = segments.drop(moduleIndex + 1)
            if (classPathSegments.size < 2) {
                continue
            }
            // 还原 jrt home 路径，保留原始是否带根路径的信息。
            val homePath = buildString {
                if (hasLeadingSlash) {
                    append('/')
                }
                append(segments.take(moduleIndex).joinToString("/"))
            }
            if (homePath.isBlank() || homePath == "/") {
                continue
            }
            return ExplodedJrtPath(
                homePath = homePath,
                moduleName = moduleName,
                classPath = classPathSegments.joinToString("/"),
            )
        }
        return null
    }

    /**
     * 表示拆解后的 jrt 路径结构。
     */
    private data class ExplodedJrtPath(
        /** 保存 JDK home 路径。 */
        val homePath: String,
        /** 保存模块名。 */
        val moduleName: String,
        /** 保存模块内类文件路径。 */
        val classPath: String,
    )
}
