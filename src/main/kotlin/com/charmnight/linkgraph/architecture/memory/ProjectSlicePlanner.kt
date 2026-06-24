package com.charmnight.linkgraph.architecture.memory

/**
 * 项目切片规划器：根据文件元数据把项目输入文件划分为切片，
 * 生成项目切片清单用于持久化缓存键与失效判断。
 */
class ProjectSlicePlanner(
    /** 项目位置哈希，用于清单标识。 */
    private val projectLocationHash: String,
    /** 清单 schema 版本。 */
    private val schemaVersion: Int = ProjectSliceManifest.CURRENT_SCHEMA_VERSION,
) {
    /** 按切片键分组并生成最终清单。 */
    fun plan(files: List<ProjectSliceInputFile>): ProjectSliceManifest {
        val slices = files
            .filter { file -> file.relativePath.isNotBlank() }
            .groupBy(::sliceKey)
            .map { (key, groupedFiles) ->
                ProjectSlice(
                    id = stableSliceId(key),
                    moduleName = key.moduleName,
                    contentRoot = key.contentRoot,
                    sourceSet = key.sourceSet,
                    packagePrefix = key.packagePrefix,
                    kind = key.kind.name,
                    files = groupedFiles
                        .sortedBy(ProjectSliceInputFile::relativePath)
                        .map { file ->
                            ProjectFileFingerprint(
                                relativePath = file.relativePath.normalizePath(),
                                size = file.size,
                                modifiedAtMillis = file.modifiedAtMillis,
                                contentSha256 = file.attachedJarFingerprint ?: file.contentSha256,
                            )
                        },
                )
            }
            .sortedBy(ProjectSlice::id)
        return ProjectSliceManifest(
            projectLocationHash = projectLocationHash,
            schemaVersion = schemaVersion,
            slices = slices,
        )
    }

    /** 根据文件路径与附加指纹推断其所属切片键：附加 jar 走 attached-jar 分支，否则按 JVM 源码或资源分类。 */
    private fun sliceKey(file: ProjectSliceInputFile): SliceKey {
        val path = file.relativePath.normalizePath()
        file.attachedJarFingerprint?.takeIf(String::isNotBlank)?.let { fingerprint ->
            return SliceKey(
                moduleName = file.moduleName,
                contentRoot = file.contentRoot,
                sourceSet = "attached-jar",
                packagePrefix = fingerprint,
                kind = ProjectSliceKind.ATTACHED_JAR,
            )
        }
        return if (path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".kts")) {
            SliceKey(
                moduleName = file.moduleName,
                contentRoot = file.contentRoot,
                sourceSet = sourceSet(path),
                packagePrefix = packagePrefix(path),
                kind = ProjectSliceKind.JVM_SOURCE,
            )
        } else {
            SliceKey(
                moduleName = file.moduleName,
                contentRoot = file.contentRoot,
                sourceSet = sourceSet(path),
                packagePrefix = resourceKind(path),
                kind = ProjectSliceKind.RESOURCE,
            )
        }
    }

    /** 按路径中的 main/test 目录标识推断源集归属，无法识别时返回 unknown。 */
    private fun sourceSet(path: String): String =
        when {
            path.contains("/src/test/") || path.startsWith("src/test/") -> "test"
            path.contains("/src/main/") || path.startsWith("src/main/") -> "main"
            else -> "unknown"
        }

    /** 从 JVM 源码路径中提取包前缀，找不到 java/kotlin 标识目录时返回 null。 */
    private fun packagePrefix(path: String): String? {
        val marker = when {
            path.contains("/java/") -> "/java/"
            path.contains("/kotlin/") -> "/kotlin/"
            else -> return null
        }
        return path.substringAfter(marker)
            .substringBeforeLast('.', missingDelimiterValue = "")
            .substringBeforeLast('/', missingDelimiterValue = "")
            .replace('/', '.')
            .takeIf(String::isNotBlank)
    }

    /** 根据资源文件后缀或目录特征，给出资源分类标签（spi/xml/yaml/properties/sql/markdown/resource）。 */
    private fun resourceKind(path: String): String =
        when {
            path.contains("/META-INF/services/") -> "spi"
            path.endsWith(".xml") -> "xml"
            path.endsWith(".yml") || path.endsWith(".yaml") -> "yaml"
            path.endsWith(".properties") -> "properties"
            path.endsWith(".sql") -> "sql"
            path.endsWith(".md") -> "markdown"
            else -> "resource"
        }

    /** 用切片键的各部分拼接成稳定可复现的切片 ID，便于持久化缓存命中。 */
    private fun stableSliceId(key: SliceKey): String =
        listOf(
            key.kind.name.lowercase(),
            key.moduleName.orEmpty(),
            key.sourceSet,
            key.contentRoot,
            key.packagePrefix.orEmpty(),
        ).joinToString(":") { part -> part.sanitizeIdPart() }

    /** 切片分组用的内部键，聚合模块、内容根、源集、包前缀与切片类型。 */
    private data class SliceKey(
        val moduleName: String?,
        val contentRoot: String,
        val sourceSet: String,
        val packagePrefix: String?,
        val kind: ProjectSliceKind,
    )
}

/** 把路径中的反斜杠统一成正斜杠并去首尾空白，便于跨平台比较。 */
internal fun String.normalizePath(): String = replace('\\', '/').trim()

/** 把任意字符串转成可作为切片 ID 片段的安全标识，去除非法字符并在空白时回退为 none。 */
private fun String.sanitizeIdPart(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "none" }
