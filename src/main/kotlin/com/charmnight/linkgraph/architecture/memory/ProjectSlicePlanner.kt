package com.charmnight.linkgraph.architecture.memory

class ProjectSlicePlanner(
    private val projectLocationHash: String,
    private val schemaVersion: Int = ProjectSliceManifest.CURRENT_SCHEMA_VERSION,
) {
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

    private fun sourceSet(path: String): String =
        when {
            path.contains("/src/test/") || path.startsWith("src/test/") -> "test"
            path.contains("/src/main/") || path.startsWith("src/main/") -> "main"
            else -> "unknown"
        }

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

    private fun stableSliceId(key: SliceKey): String =
        listOf(
            key.kind.name.lowercase(),
            key.moduleName.orEmpty(),
            key.sourceSet,
            key.contentRoot,
            key.packagePrefix.orEmpty(),
        ).joinToString(":") { part -> part.sanitizeIdPart() }

    private data class SliceKey(
        val moduleName: String?,
        val contentRoot: String,
        val sourceSet: String,
        val packagePrefix: String?,
        val kind: ProjectSliceKind,
    )
}

internal fun String.normalizePath(): String = replace('\\', '/').trim()

private fun String.sanitizeIdPart(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "none" }
