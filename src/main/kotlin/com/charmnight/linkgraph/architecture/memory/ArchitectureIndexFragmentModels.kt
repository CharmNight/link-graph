package com.charmnight.linkgraph.architecture.memory

data class ArchitectureIndexFragmentCacheKey(
    val schemaVersion: Int = ProjectSliceManifest.CURRENT_SCHEMA_VERSION,
    val pluginVersion: String = "dev",
    val projectLocationHash: String,
    val budgetHash: String,
    val sliceId: String,
    val fileHash: String,
    val attachedJarFingerprint: String? = null,
)

data class ProjectSliceManifestSnapshot(
    val manifest: ProjectSliceManifest,
    val createdAtEpochMillis: Long,
)

data class ArchitectureIndexMemorySnapshot(
    val manifest: ProjectSliceManifest? = null,
    val staleSliceIds: List<String> = emptyList(),
    val persistentCacheHits: Int = 0,
    val persistentCacheMisses: Int = 0,
    val lastUpdatedAtEpochMillis: Long? = null,
    val indexSource: String = "UNKNOWN",
) {
    val cacheHitRate: Double?
        get() {
            val total = persistentCacheHits + persistentCacheMisses
            return if (total == 0) null else persistentCacheHits.toDouble() / total.toDouble()
        }
}

data class ArchitectureIndexSliceFragment(
    val sliceId: String,
    val symbols: List<SymbolSliceFragment> = emptyList(),
    val relations: List<RelationSliceFragment> = emptyList(),
    val resources: List<ResourceSliceFragment> = emptyList(),
    val serviceProviders: List<ServiceProviderSliceFragment> = emptyList(),
)

data class SymbolSliceFragment(
    val id: String,
    val qualifiedName: String,
    val simpleName: String,
    val kind: String,
    val sourcePath: String? = null,
    val sourceVirtualFileUrl: String? = null,
    val sourceStartLine: Int? = null,
    val sourceEndLine: Int? = null,
    val sourceDecompiled: Boolean = false,
    val moduleName: String? = null,
    val packageName: String? = null,
    val ownerClassName: String? = null,
    val signature: String? = null,
    val parameterTypes: List<String> = emptyList(),
    val returnType: String? = null,
    val typeName: String? = null,
    val typeReferences: List<FieldTypeReferenceSliceFragment> = emptyList(),
    val abstract: Boolean = false,
    val classKind: String? = null,
    val stereotype: String? = null,
    val external: Boolean = false,
    val library: Boolean = false,
    val jdk: Boolean = false,
    val testSource: Boolean = false,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val docComment: String? = null,
    val origin: String = "PROJECT_SOURCE",
)

data class FieldTypeReferenceSliceFragment(
    val typeName: String,
    val role: String,
)

data class RelationSliceFragment(
    val id: String,
    val kind: String,
    val fromSymbolId: String,
    val toSymbolId: String,
    val metadata: Map<String, String> = emptyMap(),
    val confidence: String = "PROVEN",
    val source: String = "PSI",
    val count: Int = 1,
)

data class ServiceProviderSliceFragment(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resourceId: String,
    val resourcePath: String,
    val resourceKind: String,
    val origin: String = "PROJECT_SOURCE",
)

data class ResourceSliceFragment(
    val id: String,
    val path: String,
    val kind: String,
    val sourceVirtualFileUrl: String? = null,
    val sourceStartLine: Int? = null,
    val sourceEndLine: Int? = null,
    val sourceDecompiled: Boolean = false,
    val origin: String = "PROJECT_SOURCE",
)
