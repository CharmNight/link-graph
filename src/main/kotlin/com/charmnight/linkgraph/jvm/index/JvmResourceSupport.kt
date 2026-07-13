package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.source.SourceArchiveReadLimits
import com.charmnight.linkgraph.source.readFileTextBounded
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * JvmSymbolIndexBuilder 的资源 / SPI 文件纯解析 helper（P2-1 深度拆分）。
 *
 * 把"按路径推断资源种类"、"从 SPI 配置文本提取实现类全限定名"等无状态字符串解析逻辑
 * 收敛在一起，与 JvmSymbolIndexBuilder 的 PSI 遍历主流程解耦后便于复用与单独测试。
 */

/** 按资源路径推断 [JvmResourceKind]：mq: 前缀 / META-INF/services/ / 扩展名匹配。 */
internal fun resourceKind(path: String): JvmResourceKind = when {
    path.startsWith("mq:") -> JvmResourceKind.MQ_TOPIC
    path.contains("META-INF/services/") -> JvmResourceKind.SPI_SERVICE_FILE
    path.endsWith(".xml", ignoreCase = true) -> JvmResourceKind.XML
    path.endsWith(".yml", ignoreCase = true) || path.endsWith(".yaml", ignoreCase = true) -> JvmResourceKind.YAML
    path.endsWith(".properties", ignoreCase = true) -> JvmResourceKind.PROPERTIES
    path.endsWith(".sql", ignoreCase = true) -> JvmResourceKind.SQL
    path.endsWith(".md", ignoreCase = true) -> JvmResourceKind.MARKDOWN
    else -> JvmResourceKind.OTHER
}

/**
 * 从 SPI 配置文本提取实现类全限定名列表：
 * - 逐行剔除 `#` 行内注释
 * - 过滤空行
 * - 去重
 *
 * 供 jrt/jar 内的文件复用；外部 [VirtualFile] 版本应先读出文本再调本函数。
 */
internal fun spiProviderClassNames(text: String): List<String> =
    text
        .lineSequence()
        .map { line -> line.substringBefore('#').trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()

/** 读取 VirtualFile 文本；超过给定字节上限时返回 null。 */
internal fun readVirtualFileTextBounded(
    file: VirtualFile,
    maxBytes: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES,
    charset: Charset = file.charset,
): String? {
    if (file.length > maxBytes) {
        return null
    }
    return runCatching { String(file.contentsToByteArray(), charset) }.getOrNull()
}

/** 读取文件系统路径文本；超过给定字节上限时返回 null。 */
internal fun readPathTextBounded(
    path: Path,
    maxBytes: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES,
    charset: Charset = StandardCharsets.UTF_8,
): String? =
    runCatching { readFileTextBounded(path, maxBytes, charset) }.getOrNull()

/** 从受限大小的 VirtualFile SPI 配置中提取 provider 列表。 */
internal fun readSpiProviderClassNames(file: VirtualFile): List<String> =
    readVirtualFileTextBounded(
        file = file,
        maxBytes = SourceArchiveReadLimits.MAX_SERVICE_ENTRY_BYTES,
        charset = StandardCharsets.UTF_8,
    )?.let(::spiProviderClassNames).orEmpty()

/** 从受限大小的路径 SPI 配置中提取 provider 列表。 */
internal fun readSpiProviderClassNames(path: Path): List<String> =
    readPathTextBounded(
        path = path,
        maxBytes = SourceArchiveReadLimits.MAX_SERVICE_ENTRY_BYTES,
        charset = StandardCharsets.UTF_8,
    )?.let(::spiProviderClassNames).orEmpty()
