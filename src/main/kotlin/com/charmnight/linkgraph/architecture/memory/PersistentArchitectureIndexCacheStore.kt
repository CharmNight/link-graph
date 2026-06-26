package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.json.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * 持久化架构索引缓存存储，把切片片段以 JSON 文件形式写入 IDE 缓存目录。
 * 写入采用临时文件 + 原子移动以保证一致性。
 */
class PersistentArchitectureIndexCacheStore(
    /** IDE 缓存根目录。 */
    ideCacheRoot: Path,
) {
    /** 实际存放缓存文件的根目录。 */
    private val root: Path = ideCacheRoot.resolve("link-graph").resolve("architecture-index")

    /** 把片段以 JSON 形式写入磁盘（原子替换）。 */
    fun write(key: ArchitectureIndexFragmentCacheKey, fragment: ArchitectureIndexSliceFragment) {
        Files.createDirectories(root)
        val target = pathForTesting(key)
        val temp = Files.createTempFile(root, target.fileName.toString(), ".tmp")
        Files.writeString(temp, JsonCodec.toJson(fragment.toDto()), StandardCharsets.UTF_8)
        // fsync 失败不要静默：缓存一致性比性能更重要，落盘失败要记日志便于排查。
        runCatching {
            FileSync.force(temp)
        }.onFailure { error ->
            logger.warn("无法将架构索引缓存 fsync 到磁盘：${target.fileName}", error)
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** 读取缓存片段，若不存在或解析失败返回 null。 */
    fun read(key: ArchitectureIndexFragmentCacheKey): ArchitectureIndexSliceFragment? {
        val path = pathForTesting(key)
        if (!path.exists()) {
            return null
        }
        return runCatching {
            JsonCodec.parseObject(path.readText(), "architecture index slice fragment").toSliceFragment()
        }.getOrNull()
    }

    /**
     * 删除指定缓存键对应的磁盘文件；文件不存在时静默返回（幂等）。
     *
     * 用于 stale slice 重建后内容为空（例如源文件全部被删除）时清理旧缓存，避免下次启动时
     * 仍读到幽灵 fragment。
     */
    fun delete(key: ArchitectureIndexFragmentCacheKey) {
        Files.deleteIfExists(pathForTesting(key))
    }

    /** 返回指定缓存键对应的磁盘文件路径（测试使用）。 */
    fun pathForTesting(key: ArchitectureIndexFragmentCacheKey): Path =
        root.resolve(key.stableFileName())

    /** 由缓存键派生稳定的文件名（保证同一键永远命中同一文件）。 */
    private fun ArchitectureIndexFragmentCacheKey.stableFileName(): String =
        listOf(
            schemaVersion.toString(),
            projectLocationHash.safeFilePart(16),
            sliceId.safeFilePart(48),
            stableSha256(
                listOf(
                    schemaVersion.toString(),
                    pluginVersion,
                    projectLocationHash,
                    budgetHash,
                    sliceId,
                    fileHash,
                    attachedJarFingerprint.orEmpty(),
                ).joinToString("|"),
            ),
        ).joinToString("__") + ".json"

    /** 把字符串裁剪为对文件系统友好的安全片段。 */
    private fun String.safeFilePart(maxLength: Int): String =
        replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "none" }
            .take(maxLength)

    /** 计算字符串的稳定 SHA-256 摘要（十六进制）。 */
    private fun stableSha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** 将切片片段序列化为 [SliceFragmentDto]（可写入 JSON）。 */
    private fun ArchitectureIndexSliceFragment.toDto(): SliceFragmentDto =
        SliceFragmentDto(
            sliceId = sliceId,
            symbols = symbols.map { symbol ->
                SymbolSliceDto(
                    id = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    simpleName = symbol.simpleName,
                    kind = symbol.kind,
                    sourcePath = symbol.sourcePath,
                    sourceVirtualFileUrl = symbol.sourceVirtualFileUrl,
                    sourceStartLine = symbol.sourceStartLine,
                    sourceEndLine = symbol.sourceEndLine,
                    sourceDecompiled = symbol.sourceDecompiled,
                    moduleName = symbol.moduleName,
                    packageName = symbol.packageName,
                    ownerClassName = symbol.ownerClassName,
                    signature = symbol.signature,
                    parameterTypes = symbol.parameterTypes,
                    returnType = symbol.returnType,
                    typeName = symbol.typeName,
                    typeReferences = symbol.typeReferences.map { reference ->
                        TypeReferenceSliceDto(
                            typeName = reference.typeName,
                            role = reference.role,
                        )
                    },
                    abstract = symbol.abstract,
                    classKind = symbol.classKind,
                    stereotype = symbol.stereotype,
                    external = symbol.external,
                    library = symbol.library,
                    jdk = symbol.jdk,
                    testSource = symbol.testSource,
                    superClassName = symbol.superClassName,
                    interfaceNames = symbol.interfaceNames,
                    docComment = symbol.docComment,
                    origin = symbol.origin,
                )
            },
            relations = relations.map { relation ->
                RelationSliceDto(
                    id = relation.id,
                    kind = relation.kind,
                    fromSymbolId = relation.fromSymbolId,
                    toSymbolId = relation.toSymbolId,
                    metadata = relation.metadata,
                    confidence = relation.confidence,
                    source = relation.source,
                    count = relation.count,
                )
            },
            resources = resources.map { resource ->
                ResourceSliceDto(
                    id = resource.id,
                    path = resource.path,
                    kind = resource.kind,
                    sourceVirtualFileUrl = resource.sourceVirtualFileUrl,
                    sourceStartLine = resource.sourceStartLine,
                    sourceEndLine = resource.sourceEndLine,
                    sourceDecompiled = resource.sourceDecompiled,
                    origin = resource.origin,
                )
            },
            serviceProviders = serviceProviders.map { provider ->
                ServiceProviderSliceDto(
                    serviceInterfaceName = provider.serviceInterfaceName,
                    providerClassNames = provider.providerClassNames,
                    resourceId = provider.resourceId,
                    resourcePath = provider.resourcePath,
                    resourceKind = provider.resourceKind,
                    origin = provider.origin,
                )
            },
        )

    /** 把 JSON 解析得到的映射结构还原为切片片段。 */
    private fun Map<*, *>.toSliceFragment(): ArchitectureIndexSliceFragment =
        ArchitectureIndexSliceFragment(
            sliceId = requiredString("sliceId"),
            symbols = listValue("symbols").map { item ->
                val map = item as? Map<*, *> ?: error("symbol fragment must be object")
                SymbolSliceFragment(
                    id = map.requiredString("id"),
                    qualifiedName = map.requiredString("qualifiedName"),
                    simpleName = map.requiredString("simpleName"),
                    kind = map.requiredString("kind"),
                    sourcePath = map.optionalString("sourcePath"),
                    sourceVirtualFileUrl = map.optionalString("sourceVirtualFileUrl"),
                    sourceStartLine = map.optionalInt("sourceStartLine"),
                    sourceEndLine = map.optionalInt("sourceEndLine"),
                    sourceDecompiled = map.optionalBoolean("sourceDecompiled") ?: false,
                    moduleName = map.optionalString("moduleName"),
                    packageName = map.optionalString("packageName"),
                    ownerClassName = map.optionalString("ownerClassName"),
                    signature = map.optionalString("signature"),
                    parameterTypes = map.listValue("parameterTypes").map { value -> value.toString() },
                    returnType = map.optionalString("returnType"),
                    typeName = map.optionalString("typeName"),
                    typeReferences = map.listValue("typeReferences").map { reference ->
                        val referenceMap = reference as? Map<*, *> ?: error("field type reference fragment must be object")
                        FieldTypeReferenceSliceFragment(
                            typeName = referenceMap.requiredString("typeName"),
                            role = referenceMap.requiredString("role"),
                        )
                    },
                    abstract = map.optionalBoolean("abstract") ?: false,
                    classKind = map.optionalString("classKind"),
                    stereotype = map.optionalString("stereotype"),
                    external = map.optionalBoolean("external") ?: false,
                    library = map.optionalBoolean("library") ?: false,
                    jdk = map.optionalBoolean("jdk") ?: false,
                    testSource = map.optionalBoolean("testSource") ?: false,
                    superClassName = map.optionalString("superClassName"),
                    interfaceNames = map.listValue("interfaceNames").map { value -> value.toString() },
                    docComment = map.optionalString("docComment"),
                    origin = map.optionalString("origin") ?: "PROJECT_SOURCE",
                )
            },
            relations = listValue("relations").map { item ->
                val map = item as? Map<*, *> ?: error("relation fragment must be object")
                RelationSliceFragment(
                    id = map.requiredString("id"),
                    kind = map.requiredString("kind"),
                    fromSymbolId = map.requiredString("fromSymbolId"),
                    toSymbolId = map.requiredString("toSymbolId"),
                    metadata = (map["metadata"] as? Map<*, *>)?.entries
                        ?.associate { entry -> entry.key.toString() to entry.value.toString() }
                        .orEmpty(),
                    confidence = map.optionalString("confidence") ?: "PROVEN",
                    source = map.optionalString("source") ?: "PSI",
                    count = (map["count"] as? Number)?.toInt() ?: 1,
                )
            },
            resources = listValue("resources").map { item ->
                val map = item as? Map<*, *> ?: error("resource fragment must be object")
                ResourceSliceFragment(
                    id = map.requiredString("id"),
                    path = map.requiredString("path"),
                    kind = map.requiredString("kind"),
                    sourceVirtualFileUrl = map.optionalString("sourceVirtualFileUrl"),
                    sourceStartLine = map.optionalInt("sourceStartLine"),
                    sourceEndLine = map.optionalInt("sourceEndLine"),
                    sourceDecompiled = map.optionalBoolean("sourceDecompiled") ?: false,
                    origin = map.optionalString("origin") ?: "PROJECT_SOURCE",
                )
            },
            serviceProviders = listValue("serviceProviders").map { item ->
                val map = item as? Map<*, *> ?: error("service provider fragment must be object")
                ServiceProviderSliceFragment(
                    serviceInterfaceName = map.requiredString("serviceInterfaceName"),
                    providerClassNames = map.listValue("providerClassNames").map { value -> value.toString() },
                    resourceId = map.requiredString("resourceId"),
                    resourcePath = map.requiredString("resourcePath"),
                    resourceKind = map.requiredString("resourceKind"),
                    origin = map.optionalString("origin") ?: "PROJECT_SOURCE",
                )
            },
        )

    /** 从映射中读取必填字符串字段，类型不符时直接抛错，避免静默写入脏数据。 */
    private fun Map<*, *>.requiredString(key: String): String =
        this[key] as? String ?: error("$key must be a string")

    /** 从映射中读取可选字符串字段，缺失或类型不符时返回 null。 */
    private fun Map<*, *>.optionalString(key: String): String? =
        this[key] as? String

    /** 从映射中读取可选布尔字段，缺失或类型不符时返回 null。 */
    private fun Map<*, *>.optionalBoolean(key: String): Boolean? =
        this[key] as? Boolean

    /** 从映射中读取可选整数字段，兼容任何 Number 子类，否则返回 null。 */
    private fun Map<*, *>.optionalInt(key: String): Int? =
        (this[key] as? Number)?.toInt()

    /** 从映射中读取列表字段，缺失或类型不符时返回空列表，避免空指针。 */
    private fun Map<*, *>.listValue(key: String): List<*> =
        this[key] as? List<*> ?: emptyList<Any>()
}

/** 对外暴露的文件强制刷盘入口，统一委托给底层 FileChannel 实现。 */
private object FileSync {
    /** 强制把指定文件的数据刷到磁盘，保证缓存内容真正落盘。 */
    fun force(path: Path) {
        FileChannelSupport.force(path)
    }
}

/** 使用 FileChannel 实现真正的磁盘同步，避免操作系统页缓存丢失数据。 */
private object FileChannelSupport {
    /**
     * 打开文件通道并强制刷盘（包含元数据），确保写入对后续读取可见。
     *
     * 用 WRITE 模式打开：READ 模式在某些平台（Windows）会产生 NonWritableChannelException。
     * 失败时上游 runCatching 会捕获并记日志。
     */
    fun force(path: Path) {
        java.nio.channels.FileChannel.open(
            path,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.force(true)
        }
    }
}

/** PersistentArchitectureIndexCacheStore 内部用的日志器。 */
private val logger: Logger = Logger.getInstance(PersistentArchitectureIndexCacheStore::class.java)
