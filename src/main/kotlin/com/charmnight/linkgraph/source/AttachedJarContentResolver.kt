package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.jar.JarFile

/**
 * 附加 Jar 内容解析器：基于附加 jar 索引读取源码或反编译 class 字节码，
 * 对外实现统一的源码内容解析接口。
 */
class AttachedJarContentResolver(
    /** 附加 jar 索引。 */
    private val index: AttachedJarIndex,
    /** 是否允许反编译 class 字节码。 */
    private val allowDecompile: Boolean = true,
) : SourceContentResolver {
    /** 最近一次内容不可读时的原因代码（用于排查 UI 上"源码不可用"问题）。 */
    @Volatile
    var lastUnavailableReason: String? = null
        private set

    /**
     * 直接基于附加 jar 条目列表构造解析器（内部会构建索引）。
     */
    constructor(
        entries: List<AttachedJarEntry>,
        allowDecompile: Boolean = true,
    ) : this(AttachedJarIndex.build(entries), allowDecompile)

    /**
     * 按虚拟文件 URL（形如 jar://path!/entry）读取源码内容。
     * 非附加 jar 路径或路径格式异常时返回 null。
     */
    override fun readByVirtualFileUrl(url: String): BoundedSourceContent? {
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

    /**
     * 按路径读取内容，支持 jar entry（包含 !/）或全限定类名两种形式。
     */
    override fun readByPath(path: String): BoundedSourceContent? {
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

    /**
     * 按路径读取指定行范围的内容（用于代码片段展示）。
     */
    override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): BoundedSourceContent? {
        lastUnavailableReason = null
        val full = readByPath(path) ?: return null
        return full.toLineRange(startLine, endLine)
    }

    /**
     * 按全限定类名查找源码：优先返回附加源码 jar 中的源文件，
     * 否则尝试反编译附加 class jar（需 allowDecompile）。
     */
    override fun readClassByQualifiedName(qualifiedName: String): BoundedSourceContent? {
        lastUnavailableReason = null
        val candidates = index.findClassCandidates(qualifiedName)
        val entry = candidates.firstOrNull() ?: return null
        if (entry.sourceEntryName != null && entry.sourceJarPath != null) {
            return readJarEntry(entry.sourceJarPath, entry.sourceEntryName)
                ?.copy(origin = SourceOrigin.USER_ATTACHED_SOURCE_JAR, decompiled = false)
                ?.also { reportClassAmbiguity(candidates.size) }
        }
        if (!allowDecompile || entry.classEntryName == null) {
            if (!allowDecompile && entry.classEntryName != null) {
                lastUnavailableReason = "CLASS_JAR_DECOMPILE_DISABLED"
            }
            return null
        }
        decompileClassEntry(entry.classJarPath, entry.classEntryName, entry.qualifiedName)?.let { result ->
            val text = result.text ?: return null
            return BoundedSourceContent.create(
                text = text,
                displayPath = entry.displayPath,
                virtualFileUrl = "jar://${entry.classJarPath}!/${entry.classEntryName}",
                origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
                language = "JAVA",
                startLine = 1,
                endLine = text.lineSequence().count().coerceAtLeast(1),
                decompiled = true,
                diagnostic = result.diagnostic,
            ).also { reportClassAmbiguity(candidates.size) }
        }
        return null
    }

    private fun reportClassAmbiguity(candidateCount: Int) {
        if (candidateCount > 1) {
            lastUnavailableReason = "CLASS_JAR_AMBIGUOUS:$candidateCount"
        }
    }

    /**
     * 按资源路径（如 META-INF/services 接口文件）匹配并返回源码内容。
     */
    override fun readResourceByPath(resourcePath: String): BoundedSourceContent? {
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

    /**
     * 从指定 jar 中读取指定 entry 名的内容；class 文件按需反编译。
     */
    private fun readJarEntry(jarPath: String, entryName: String): BoundedSourceContent? {
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
                val decompiled = entry.name.endsWith(".class")
                val text = if (decompiled) {
                    if (!allowDecompile) {
                        lastUnavailableReason = "CLASS_JAR_DECOMPILE_DISABLED"
                        return null
                    }
                    if (!jar.isEntryWithinLimit(entry, SourceArchiveReadLimits.MAX_CLASS_ENTRY_BYTES)) {
                        lastUnavailableReason = "JAR_ENTRY_TOO_LARGE"
                        return null
                    }
                    decompileClassEntry(path.toString(), entry.name, entry.name.removeSuffix(".class").replace('/', '.'))?.text
                        ?: return null
                } else {
                    jar.readEntryTextBounded(entry, textEntryLimit(entry.name)) ?: run {
                        lastUnavailableReason = "JAR_ENTRY_TOO_LARGE"
                        return null
                    }
                }
                BoundedSourceContent.create(
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

    /**
     * 按起止行号裁剪源码内容，越界或范围非法返回 null。
     */
    private fun BoundedSourceContent.toLineRange(startLine: Int?, endLine: Int?): BoundedSourceContent? {
        if (startLine == null || endLine == null) {
            return this
        }
        val lines = text.lines()
        val fromIndex = (startLine - 1).coerceAtLeast(0)
        val toIndex = endLine.coerceAtMost(lines.size)
        if (fromIndex >= toIndex) {
            return null
        }
        return withText(
            text = lines.subList(fromIndex, toIndex).joinToString("\n"),
            startLine = startLine,
            endLine = endLine,
        )
    }

    /** 根据条目名/是否反编译推断高亮语言。 */
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

    /** 按条目类型选择读取上限，服务描述文件通常很小，单独收紧。 */
    private fun textEntryLimit(entryName: String): Int =
        if (entryName.startsWith("META-INF/services/")) {
            SourceArchiveReadLimits.MAX_SERVICE_ENTRY_BYTES
        } else {
            SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES
        }

    /**
     * 反编译 class 条目并校验：命中缓存则直接复用；并发时通过信号量限流，
     * 并检查反编译产物类名是否匹配（避免误命中同 jar 内同名外部类）。
     */
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
        classEntryLimitFailure(inputJar, entryName)?.let { diagnostic ->
            lastUnavailableReason = diagnostic
            return null
        }
        val cacheKey = decompileCacheKey(inputJar, entryName)
        decompiledClassCache.get(cacheKey)?.let { cached ->
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
            decompiledClassCache.get(cacheKey) ?: decompileClassEntryUncached(inputJar, entryName).also { value ->
                decompiledClassCache.put(cacheKey, value)
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

    /** 不查缓存直接反编译，失败时返回带诊断信息的空结果。 */
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

    /** 校验反编译目标 class 条目存在且不超过大小上限。 */
    private fun classEntryLimitFailure(inputJar: Path, entryName: String): String? =
        runCatching {
            JarFile(inputJar.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: return@use "CLASS_JAR_ENTRY_NOT_FOUND"
                if (entry.isDirectory) {
                    return@use "CLASS_JAR_ENTRY_NOT_FOUND"
                }
                if (jar.isEntryWithinLimit(entry, SourceArchiveReadLimits.MAX_CLASS_ENTRY_BYTES)) {
                    null
                } else {
                    "JAR_ENTRY_TOO_LARGE"
                }
            }
        }.getOrElse { error -> error.message ?: error.javaClass.simpleName }

    /** 反编译并发限流与结果缓存（避免重复反编译耗时）。 */
    private companion object {
        // 限制反编译同时只有一个进行，避免阻塞 EDT。
        private val decompileSemaphore = Semaphore(1)
        // 反编译结果缓存，受条目数、总字节和 TTL 三重限制。
        private val decompiledClassCache = ManagedDecompiledSourceCache<String, DecompiledSource>(
            maxEntries = 128,
            maxWeightBytes = 32L * 1024 * 1024,
            ttlMillis = 30L * 60 * 1000,
            weightBytes = { value ->
                value.text?.toByteArray(Charsets.UTF_8)?.size
                    ?: value.diagnostic?.toByteArray(Charsets.UTF_8)?.size
                    ?: 0
            },
        )

        /**
         * 生成反编译缓存键，结合 jar 路径、最后修改时间、文件大小与 entry 名，
         * 确保 jar 内容变化后缓存能自然失效。
         */
    }

    private fun decompileCacheKey(jarPath: Path, entryName: String): String {
        val normalizedPath = jarPath.normalize().toString()
        val fingerprint = index.fingerprints
            .firstOrNull { candidate ->
                runCatching { Path.of(candidate.path).normalize().toString() }.getOrNull() == normalizedPath
            }
            ?.classJarSha256
            ?: sha256(jarPath)
        return "$normalizedPath|$fingerprint|$entryName"
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** 反编译结果（文本可能为空，附带诊断信息）。 */
private data class DecompiledSource(
    /** 反编译出的源码文本，失败时为 null。 */
    val text: String?,
    /** 失败原因代码或诊断信息，成功时通常为 null。 */
    val diagnostic: String?,
)

/** 判断指定 jar 条目是否在索引中被引用，从而允许读取（避免越权读任意条目）。 */
private fun AttachedJarIndex.allowsEntry(jarPath: String, entryName: String): Boolean {
    val normalizedJar = runCatching { Path.of(jarPath).normalize().toString() }.getOrDefault(jarPath)
    val normalizedEntry = entryName.trim().removePrefix("/")
    return classEntries.any { entry ->
        (entry.classJarPath == normalizedJar && entry.classEntryName == normalizedEntry) ||
            (entry.sourceJarPath == normalizedJar && entry.sourceEntryName == normalizedEntry)
    } || serviceFilesByInterfaceName.values.flatten().any { serviceFile ->
        serviceFile.resourceJarPath == normalizedJar && serviceFile.resourceEntryName == normalizedEntry
    }
}
