package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.json.JsonCodec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText

class PersistentArchitectureIndexCacheStore(
    ideCacheRoot: Path,
) {
    private val root: Path = ideCacheRoot.resolve("link-graph").resolve("architecture-index")

    fun write(key: ArchitectureIndexFragmentCacheKey, fragment: ArchitectureIndexSliceFragment) {
        Files.createDirectories(root)
        val target = pathForTesting(key)
        val temp = Files.createTempFile(root, target.fileName.toString(), ".tmp")
        Files.writeString(temp, JsonCodec.toJson(fragment.toMap()), StandardCharsets.UTF_8)
        runCatching {
            FileSync.force(temp)
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(key: ArchitectureIndexFragmentCacheKey): ArchitectureIndexSliceFragment? {
        val path = pathForTesting(key)
        if (!path.exists()) {
            return null
        }
        return runCatching {
            JsonCodec.parseObject(path.readText(), "architecture index slice fragment").toSliceFragment()
        }.getOrNull()
    }

    fun pathForTesting(key: ArchitectureIndexFragmentCacheKey): Path =
        root.resolve(key.stableFileName())

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

    private fun String.safeFilePart(maxLength: Int): String =
        replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "none" }
            .take(maxLength)

    private fun stableSha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun ArchitectureIndexSliceFragment.toMap(): Map<String, Any?> =
        linkedMapOf(
            "sliceId" to sliceId,
            "symbols" to symbols.map { symbol ->
                linkedMapOf(
                    "id" to symbol.id,
                    "qualifiedName" to symbol.qualifiedName,
                    "simpleName" to symbol.simpleName,
                    "kind" to symbol.kind,
                    "sourcePath" to symbol.sourcePath,
                    "sourceVirtualFileUrl" to symbol.sourceVirtualFileUrl,
                    "sourceStartLine" to symbol.sourceStartLine,
                    "sourceEndLine" to symbol.sourceEndLine,
                    "sourceDecompiled" to symbol.sourceDecompiled,
                    "moduleName" to symbol.moduleName,
                    "packageName" to symbol.packageName,
                    "ownerClassName" to symbol.ownerClassName,
                    "signature" to symbol.signature,
                    "parameterTypes" to symbol.parameterTypes,
                    "returnType" to symbol.returnType,
                    "typeName" to symbol.typeName,
                    "typeReferences" to symbol.typeReferences.map { reference ->
                        linkedMapOf(
                            "typeName" to reference.typeName,
                            "role" to reference.role,
                        )
                    },
                    "abstract" to symbol.abstract,
                    "classKind" to symbol.classKind,
                    "stereotype" to symbol.stereotype,
                    "external" to symbol.external,
                    "library" to symbol.library,
                    "jdk" to symbol.jdk,
                    "testSource" to symbol.testSource,
                    "superClassName" to symbol.superClassName,
                    "interfaceNames" to symbol.interfaceNames,
                    "docComment" to symbol.docComment,
                    "origin" to symbol.origin,
                )
            },
            "relations" to relations.map { relation ->
                linkedMapOf(
                    "id" to relation.id,
                    "kind" to relation.kind,
                    "fromSymbolId" to relation.fromSymbolId,
                    "toSymbolId" to relation.toSymbolId,
                    "metadata" to relation.metadata,
                    "confidence" to relation.confidence,
                    "source" to relation.source,
                    "count" to relation.count,
                )
            },
            "resources" to resources.map { resource ->
                linkedMapOf(
                    "id" to resource.id,
                    "path" to resource.path,
                    "kind" to resource.kind,
                    "sourceVirtualFileUrl" to resource.sourceVirtualFileUrl,
                    "sourceStartLine" to resource.sourceStartLine,
                    "sourceEndLine" to resource.sourceEndLine,
                    "sourceDecompiled" to resource.sourceDecompiled,
                    "origin" to resource.origin,
                )
            },
            "serviceProviders" to serviceProviders.map { provider ->
                linkedMapOf(
                    "serviceInterfaceName" to provider.serviceInterfaceName,
                    "providerClassNames" to provider.providerClassNames,
                    "resourceId" to provider.resourceId,
                    "resourcePath" to provider.resourcePath,
                    "resourceKind" to provider.resourceKind,
                    "origin" to provider.origin,
                )
            },
        )

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

    private fun Map<*, *>.requiredString(key: String): String =
        this[key] as? String ?: error("$key must be a string")

    private fun Map<*, *>.optionalString(key: String): String? =
        this[key] as? String

    private fun Map<*, *>.optionalBoolean(key: String): Boolean? =
        this[key] as? Boolean

    private fun Map<*, *>.optionalInt(key: String): Int? =
        (this[key] as? Number)?.toInt()

    private fun Map<*, *>.listValue(key: String): List<*> =
        this[key] as? List<*> ?: emptyList<Any>()
}

private object FileSync {
    fun force(path: Path) {
        FileChannelSupport.force(path)
    }
}

private object FileChannelSupport {
    fun force(path: Path) {
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
