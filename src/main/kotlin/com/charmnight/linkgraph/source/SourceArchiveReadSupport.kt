package com.charmnight.linkgraph.source

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile

/** 归档/反编译源码读取的资源上限，避免外部 jar 或工具输出触发无界内存占用。 */
internal object SourceArchiveReadLimits {
    const val MAX_CLASS_ENTRY_BYTES: Int = 2 * 1024 * 1024
    const val MAX_TEXT_ENTRY_BYTES: Int = 2 * 1024 * 1024
    const val MAX_SERVICE_ENTRY_BYTES: Int = 64 * 1024
    const val MAX_FERNFLOWER_OUTPUT_BYTES: Int = 2 * 1024 * 1024
    const val MAX_FERNFLOWER_PROCESS_OUTPUT_BYTES: Int = 256 * 1024
    const val MAX_FERNFLOWER_INPUT_ENTRIES: Int = 256
    const val MAX_FERNFLOWER_INPUT_BYTES: Int = 8 * 1024 * 1024
}

/** 判断 jar 条目是否在读取上限内；条目大小未知时用受限流读取做兜底校验。 */
internal fun JarFile.isEntryWithinLimit(entry: JarEntry, maxBytes: Int): Boolean {
    if (entry.size > maxBytes) {
        return false
    }
    if (entry.size >= 0) {
        return true
    }
    return readEntryBytesBounded(entry, maxBytes) != null
}

/** 读取 jar 条目字节；超过上限时返回 null。 */
internal fun JarFile.readEntryBytesBounded(entry: JarEntry, maxBytes: Int): ByteArray? {
    if (entry.size > maxBytes) {
        return null
    }
    return getInputStream(entry).use { input ->
        input.readBytesBounded(maxBytes)
    }
}

/** 读取 jar 条目文本；超过上限时返回 null。 */
internal fun JarFile.readEntryTextBounded(
    entry: JarEntry,
    maxBytes: Int,
    charset: Charset = Charsets.UTF_8,
): String? =
    readEntryBytesBounded(entry, maxBytes)?.toString(charset)

/** 读取普通文件文本；超过上限时返回 null。 */
internal fun readFileTextBounded(
    path: Path,
    maxBytes: Int,
    charset: Charset = Charsets.UTF_8,
): String? {
    if (!Files.isRegularFile(path) || Files.size(path) > maxBytes) {
        return null
    }
    return Files.newInputStream(path).use { input ->
        input.readBytesBounded(maxBytes)?.toString(charset)
    }
}

/** 读取输入流字节；超过上限时停止累积并返回 null。 */
internal fun InputStream.readBytesBounded(maxBytes: Int): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream(maxBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (true) {
        val readLimit = if (remaining < buffer.size) remaining + 1 else buffer.size
        val read = read(buffer, 0, readLimit)
        if (read < 0) {
            break
        }
        if (read > remaining) {
            return null
        }
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}
