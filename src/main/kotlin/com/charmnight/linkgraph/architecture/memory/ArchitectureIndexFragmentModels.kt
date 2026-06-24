package com.charmnight.linkgraph.architecture.memory

/**
 * 当前插件版本标识，用作缓存键的一部分。
 *
 * - 生产环境：从 IntelliJ PluginManagerCore 读取 com.charmnight.linkgraph 插件对象的真实版本。
 * - 开发/测试环境（插件对象尚未初始化）：回落到 `dev-${build.timestamp or "unknown"}`，
 *   保证开发模式下每次代码变更（不同的 build.timestamp）都会让旧缓存键失效，避免错误命中。
 *
 * 旧实现默认 `"dev"`，导致开发模式下所有构建共享同一缓存键 —— 一次代码改动不会让缓存失效，
 * 反复读到旧 fragment，调试体验极差。
 */
val LINK_GRAPH_PLUGIN_VERSION: String by lazy {
    val core = runCatching {
        Class.forName("com.intellij.ide.plugins.PluginManagerCore")
            .getMethod("getPlugin")
            .let { it.isAccessible = true; it }
    }
    core.getOrNull()?.let { method ->
        runCatching {
            val pluginIdClass = Class.forName("com.intellij.ide.plugins.PluginId")
            val getId = pluginIdClass.getMethod("getId", String::class.java)
            val pluginId = getId.invoke(null, "com.charmnight.linkgraph")
            val plugin = method.invoke(null, pluginId)
            plugin?.javaClass?.getMethod("getVersion")?.invoke(plugin) as? String
        }.getOrNull()
    }?.takeIf { it.isNotBlank() } ?: "dev-${System.getProperty("build.timestamp") ?: "unknown"}"
}

/** 切片片段缓存键：由 schema 版本、插件版本、项目哈希、预算哈希、切片 ID、文件哈希等组成。 */
data class ArchitectureIndexFragmentCacheKey(
    val schemaVersion: Int = ProjectSliceManifest.CURRENT_SCHEMA_VERSION,
    val pluginVersion: String = LINK_GRAPH_PLUGIN_VERSION,
    val projectLocationHash: String,
    val budgetHash: String,
    val sliceId: String,
    val fileHash: String,
    val attachedJarFingerprint: String? = null,
)

/** 切片清单快照，包含清单本身与创建时间。 */
data class ProjectSliceManifestSnapshot(
    val manifest: ProjectSliceManifest,
    val createdAtEpochMillis: Long,
)

/** 索引内存快照，反映切片清单、陈旧切片、缓存命中统计与索引来源等。 */
data class ArchitectureIndexMemorySnapshot(
    val manifest: ProjectSliceManifest? = null,
    val staleSliceIds: List<String> = emptyList(),
    val persistentCacheHits: Int = 0,
    val persistentCacheMisses: Int = 0,
    val lastUpdatedAtEpochMillis: Long? = null,
    val indexSource: String = "UNKNOWN",
) {
    /** 持久化缓存命中率，无样本时返回 null。 */
    val cacheHitRate: Double?
        get() {
            val total = persistentCacheHits + persistentCacheMisses
            return if (total == 0) null else persistentCacheHits.toDouble() / total.toDouble()
        }
}

/** 单个切片的索引片段，包含符号、关系、资源、SPI 服务提供者等条目。 */
data class ArchitectureIndexSliceFragment(
    val sliceId: String,
    val symbols: List<SymbolSliceFragment> = emptyList(),
    val relations: List<RelationSliceFragment> = emptyList(),
    val resources: List<ResourceSliceFragment> = emptyList(),
    val serviceProviders: List<ServiceProviderSliceFragment> = emptyList(),
)

/** 符号片段（类/方法/字段/资源等）的可序列化表示。 */
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

/** 字段类型引用片段，记录字段类型名和它在字段中的角色。 */
data class FieldTypeReferenceSliceFragment(
    val typeName: String,
    val role: String,
)

/** 关系片段：节点间关系的可序列化表示。 */
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

/** SPI 服务提供者片段，描述 META-INF/services 文件中的接口与实现。 */
data class ServiceProviderSliceFragment(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resourceId: String,
    val resourcePath: String,
    val resourceKind: String,
    val origin: String = "PROJECT_SOURCE",
)

/** 资源片段：资源文件的可序列化表示。 */
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
