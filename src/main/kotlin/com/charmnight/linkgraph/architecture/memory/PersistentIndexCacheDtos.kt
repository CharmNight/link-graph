package com.charmnight.linkgraph.architecture.memory

/**
 * PersistentArchitectureIndexCacheStore 序列化 DTO（P2-6 替代之前的 `Map<String, Any?>`）。
 *
 * 字段名与原 linkedMapOf 的 key 一一对应，Gson 反射序列化保证字段顺序一致。
 * 用于磁盘持久化格式，向后兼容（旧缓存文件能正常读取）。
 *
 * 反序列化（toSliceFragment）仍走 Map<*, *> 路径，因为输入是动态 JSON。
 */
internal data class TypeReferenceSliceDto(
    val typeName: String,
    val role: String,
)

internal data class SymbolSliceDto(
    val id: String,
    val qualifiedName: String,
    val simpleName: String,
    val kind: String,
    val sourcePath: String?,
    val sourceVirtualFileUrl: String?,
    val sourceStartLine: Int?,
    val sourceEndLine: Int?,
    val sourceDecompiled: Boolean,
    val moduleName: String?,
    val packageName: String?,
    val ownerClassName: String?,
    val signature: String?,
    val parameterTypes: List<String>,
    val returnType: String?,
    val typeName: String?,
    val typeReferences: List<TypeReferenceSliceDto>,
    val abstract: Boolean,
    val classKind: String?,
    val stereotype: String?,
    val external: Boolean,
    val library: Boolean,
    val jdk: Boolean,
    val testSource: Boolean,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val docComment: String?,
    val origin: String?,
)

internal data class RelationSliceDto(
    val id: String,
    val kind: String,
    val fromSymbolId: String,
    val toSymbolId: String,
    val metadata: Map<String, String>,
    val confidence: String?,
    val source: String?,
    val count: Int?,
)

internal data class ResourceSliceDto(
    val id: String,
    val path: String,
    val kind: String,
    val sourceVirtualFileUrl: String?,
    val sourceStartLine: Int?,
    val sourceEndLine: Int?,
    val sourceDecompiled: Boolean,
    val origin: String?,
)

internal data class ServiceProviderSliceDto(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resourceId: String?,
    val resourcePath: String?,
    val resourceKind: String?,
    val origin: String?,
)

internal data class SliceFragmentDto(
    val sliceId: String,
    val symbols: List<SymbolSliceDto>,
    val relations: List<RelationSliceDto>,
    val resources: List<ResourceSliceDto>,
    val serviceProviders: List<ServiceProviderSliceDto>,
)
