package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.jar.JarFile

class AttachedJarContentResolver(
    private val index: AttachedJarIndex,
    private val allowDecompile: Boolean = true,
) : SourceContentResolver {
    @Volatile
    var lastUnavailableReason: String? = null
        private set

    constructor(
        entries: List<AttachedJarEntry>,
        allowDecompile: Boolean = true,
    ) : this(AttachedJarIndex.build(entries), allowDecompile)

    override fun readByVirtualFileUrl(url: String): SourceContent? {
        lastUnavailableReason = null
        if (!url.startsWith("jar://")) {
            return null
        }
        val archivePath = url.removePrefix("jar://")
        val bang = archivePath.indexOf("!/")
        if (bang <= 0) {
            return null
        }
        val jarPath = archivePath.substring(0, bang)
        val entryName = archivePath.substring(bang + 2)
        return readJarEntry(jarPath, entryName)
    }

    override fun readByPath(path: String): SourceContent? {
        lastUnavailableReason = null
        val normalized = path.trim()
        if (normalized.contains("!/")) {
            val parts = normalized.removePrefix("jar://").split("!/", limit = 2)
            if (parts.size == 2) {
                return readJarEntry(parts[0], parts[1])
            }
        }
        val qualifiedName = normalized
            .removeSuffix(".java")
            .removeSuffix(".kt")
            .replace('/', '.')
            .replace('\\', '.')
        return readClassByQualifiedName(qualifiedName)
    }

    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent? {
        lastUnavailableReason = null
        val full = readByPath(path) ?: return null
        return full.toLineRange(startLine, endLine)
    }

    override fun readClassByQualifiedName(qualifiedName: String): SourceContent? {
        lastUnavailableReason = null
        val entry = index.findClass(qualifiedName) ?: return null
        if (entry.sourceEntryName != null && entry.sourceJarPath != null) {
            return readJarEntry(entry.sourceJarPath, entry.sourceEntryName)
                ?.copy(origin = SourceOrigin.USER_ATTACHED_SOURCE_JAR, decompiled = false)
        }
        if (!allowDecompile || entry.classEntryName == null) {
            if (!allowDecompile && entry.classEntryName != null) {
                lastUnavailableReason = "CLASS_JAR_DECOMPILE_DISABLED"
            }
            return null
        }
        decompileClassEntry(entry.classJarPath, entry.classEntryName, entry.qualifiedName)?.let { result ->
            val text = result.text ?: return null
            return SourceContent(
                text = text,
                displayPath = entry.displayPath,
                virtualFileUrl = "jar://${entry.classJarPath}!/${entry.classEntryName}",
                origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
                language = "JAVA",
                startLine = 1,
                endLine = text.lineSequence().count().coerceAtLeast(1),
                decompiled = true,
                diagnostic = result.diagnostic,
            )
        }
        return null
    }

    override fun readResourceByPath(resourcePath: String): SourceContent? {
        lastUnavailableReason = null
        val normalized = resourcePath.trim().removePrefix("/")
        index.serviceFilesByInterfaceName.values.flatten()
            .firstOrNull { serviceFile ->
                serviceFile.resourceEntryName == normalized ||
                    serviceFile.resourceEntryName.endsWith("/$normalized") ||
                    normalized.endsWith(serviceFile.resourceEntryName)
            }
            ?.let { serviceFile ->
                return readJarEntry(serviceFile.resourceJarPath, serviceFile.resourceEntryName)
                    ?.copy(origin = serviceFile.origin)
            }
        return null
    }

    private fun readJarEntry(jarPath: String, entryName: String): SourceContent? {
        val path = runCatching { Path.of(jarPath).normalize() }.getOrNull()
            ?.takeIf { candidate -> Files.isRegularFile(candidate) }
            ?: return null
        if (!index.allowsEntry(path.toString(), entryName)) {
            return null
        }
        return runCatching {
            JarFile(path.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: return null
                if (entry.isDirectory) {
                    return null
                }
                val bytes = jar.getInputStream(entry).readBytes()
                val text = if (entry.name.endsWith(".class")) {
                    if (!allowDecompile) {
                        lastUnavailableReason = "CLASS_JAR_DECOMPILE_DISABLED"
                        return null
                    }
                    decompileClassEntry(path.toString(), entry.name, entry.name.removeSuffix(".class").replace('/', '.'))?.text
                        ?: return null
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
                val decompiled = entry.name.endsWith(".class")
                SourceContent(
                    text = text,
                    displayPath = "$path!/${entry.name}",
                    virtualFileUrl = "jar://$path!/${entry.name}",
                    origin = if (decompiled) SourceOrigin.USER_ATTACHED_CLASS_JAR else SourceOrigin.USER_ATTACHED_SOURCE_JAR,
                    language = languageOf(entry.name, decompiled),
                    startLine = 1,
                    endLine = text.lineSequence().count().coerceAtLeast(1),
                    decompiled = decompiled,
                )
            }
        }.getOrNull()
    }

    private fun SourceContent.toLineRange(startLine: Int?, endLine: Int?): SourceContent? {
        if (startLine == null || endLine == null) {
            return this
        }
        val lines = text.lines()
        val fromIndex = (startLine - 1).coerceAtLeast(0)
        val toIndex = endLine.coerceAtMost(lines.size)
        if (fromIndex >= toIndex) {
            return null
        }
        return copy(
            text = lines.subList(fromIndex, toIndex).joinToString("\n"),
            startLine = startLine,
            endLine = endLine,
        )
    }

    private fun languageOf(entryName: String, decompiled: Boolean): String? =
        when {
            decompiled -> "JAVA"
            entryName.endsWith(".java") -> "JAVA"
            entryName.endsWith(".kt") -> "KOTLIN"
            entryName.endsWith(".xml") -> "XML"
            entryName.endsWith(".yml") || entryName.endsWith(".yaml") -> "YAML"
            entryName.endsWith(".properties") -> "PROPERTIES"
            else -> null
        }

    private fun decompileClassEntry(
        jarPath: String,
        entryName: String,
        qualifiedName: String,
    ): DecompiledSource? {
        if (!allowDecompile) {
            lastUnavailableReason = "CLASS_JAR_DECOMPILE_DISABLED"
            return null
        }
        val inputJar = runCatching { Path.of(jarPath).normalize().takeIf(Files::isRegularFile) }.getOrNull() ?: return null
        val cacheKey = decompileCacheKey(inputJar, entryName)
        decompiledClassCache[cacheKey]?.let { cached ->
            if (cached.text == null) {
                lastUnavailableReason = cached.diagnostic
                return null
            }
            return cached.takeIf { result ->
                result.text?.contains(qualifiedName.substringAfterLast('.').substringBefore('$')) == true
            }
        }
        if (!decompileSemaphore.tryAcquire()) {
            lastUnavailableReason = "CLASS_JAR_DECOMPILE_BUSY"
            return null
        }
        val result = try {
            decompiledClassCache.computeIfAbsent(cacheKey) {
                decompileClassEntryUncached(inputJar, entryName)
            }
        } finally {
            decompileSemaphore.release()
        }
        if (result.text == null) {
            lastUnavailableReason = result.diagnostic
            return null
        }
        return result.takeIf { value ->
            value.text?.contains(qualifiedName.substringAfterLast('.').substringBefore('$')) == true
        } ?: run {
            lastUnavailableReason = "CLASS_JAR_DECOMPILE_TARGET_MISMATCH"
            null
        }
    }

    private fun decompileClassEntryUncached(
        inputJar: Path,
        entryName: String,
    ): DecompiledSource {
        return runCatching {
            val result = ClassFileSourceDecompiler.decompileJarEntryWithDiagnostic(inputJar, entryName)
            DecompiledSource(result.text, result.diagnostic)
        }.getOrElse { error ->
            DecompiledSource(null, error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        private val decompileSemaphore = Semaphore(1)
        private val decompiledClassCache = ConcurrentHashMap<String, DecompiledSource>()

        private fun decompileCacheKey(
            jarPath: Path,
            entryName: String,
        ): String {
            val lastModified = runCatching { Files.getLastModifiedTime(jarPath).toMillis() }.getOrDefault(0L)
            val size = runCatching { Files.size(jarPath) }.getOrDefault(0L)
            return "${jarPath.normalize()}|$lastModified|$size|$entryName"
        }
    }
}

private data class DecompiledSource(
    val text: String?,
    val diagnostic: String?,
)

private fun AttachedJarIndex.allowsEntry(jarPath: String, entryName: String): Boolean {
    val normalizedJar = runCatching { Path.of(jarPath).normalize().toString() }.getOrDefault(jarPath)
    val normalizedEntry = entryName.trim().removePrefix("/")
    return classesByQualifiedName.values.any { entry ->
        (entry.classJarPath == normalizedJar && entry.classEntryName == normalizedEntry) ||
            (entry.sourceJarPath == normalizedJar && entry.sourceEntryName == normalizedEntry)
    } || serviceFilesByInterfaceName.values.flatten().any { serviceFile ->
        serviceFile.resourceJarPath == normalizedJar && serviceFile.resourceEntryName == normalizedEntry
    }
}
