package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.memory.ProjectFileFingerprint
import com.charmnight.linkgraph.architecture.memory.ProjectSlice
import com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderFile
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderIndex
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * ArchitectureIndexRuntime 的索引合并 / slice 归属计算 helper（P2-1 深度拆分）。
 *
 * 这些函数把"两个 JvmSymbolIndex 合并"、"SPI 文件去重合并"、"按 slice 文件集合
 * 派生 symbol ID 集合"、"按关系两端计算 owner slice 集合"等无状态合并算法收敛
 * 在一起，与 ArchitectureIndexRuntime 的索引构建 / 缓存调度主流程解耦后便于复用
 * 与单独测试。
 */

/** 合并两个 [JvmSymbolIndex]：所有 map 取并集，serviceProviderIndex 走 [mergeServiceProviderFiles]。 */
internal fun mergeSymbolIndexes(
    cached: JvmSymbolIndex,
    rebuilt: JvmSymbolIndex,
): JvmSymbolIndex = JvmSymbolIndex(
    modulesByName = cached.modulesByName + rebuilt.modulesByName,
    packagesByName = cached.packagesByName + rebuilt.packagesByName,
    classesByQualifiedName = cached.classesByQualifiedName + rebuilt.classesByQualifiedName,
    methodsBySignature = cached.methodsBySignature + rebuilt.methodsBySignature,
    fieldsByQualifiedName = cached.fieldsByQualifiedName + rebuilt.fieldsByQualifiedName,
    resourcesByPath = cached.resourcesByPath + rebuilt.resourcesByPath,
    serviceProviderIndex = JvmServiceProviderIndex(
        mergeServiceProviderFiles(
            cached.serviceProviderIndex.filesByInterfaceName,
            rebuilt.serviceProviderIndex.filesByInterfaceName,
        ),
    ),
)

/** 合并 SPI 文件映射：按 (path, providerClassNames) 去重，避免重复重建时累积副本。 */
internal fun mergeServiceProviderFiles(
    cached: Map<String, List<JvmServiceProviderFile>>,
    rebuilt: Map<String, List<JvmServiceProviderFile>>,
): Map<String, List<JvmServiceProviderFile>> =
    (cached.keys + rebuilt.keys).associateWith { interfaceName ->
        (cached[interfaceName].orEmpty() + rebuilt[interfaceName].orEmpty())
            .distinctBy { file -> file.resource.path to file.providerClassNames }
    }

/**
 * 判断文件是否会影响索引：META-INF/services 配置 + java/kt/kts/clazz/groovy 等源码扩展。
 *
 * 用于增量重建时跳过非源码 / 非配置文件，避免不必要的重新解析。
 */
internal fun isIndexAffectingFile(file: VirtualFile, relativePath: String): Boolean {
    if (relativePath.contains("/META-INF/services/")) {
        return true
    }
    return file.extension?.lowercase() in INDEX_AFFECTING_EXTENSIONS
}

/** 索引相关文件扩展名集合（[isIndexAffectingFile] 用）。 */
private val INDEX_AFFECTING_EXTENSIONS = setOf(
    "java",
    "kt",
    "kts",
    "xml",
    "yml",
    "yaml",
    "properties",
    "sql",
    "md",
)

/**
 * 计算 slice 关联的 symbol ID 集合：扫描 index 中所有 symbol（非 resource），
 * 按 source.displayPath（项目相对）匹配 slice 文件集合；resource 按 path 同样匹配。
 */
internal fun sliceSymbolIds(
    slice: ProjectSlice,
    index: ArchitectureGraphIndex,
    projectBasePath: String?,
): Set<String> {
    val sliceFiles = slice.files.mapTo(hashSetOf(), ProjectFileFingerprint::relativePath)
    val symbolIds = index.symbolIndex.symbolsById.values
        .filterNot { symbol -> symbol is com.charmnight.linkgraph.jvm.index.JvmResourceSymbol }
        .mapNotNullTo(linkedSetOf()) { symbol ->
            val path = toProjectRelativePath(symbol.source?.displayPath, projectBasePath)
            symbol.id.takeIf { path.isNotBlank() && pathMatchesSliceFiles(path, sliceFiles) }
        }
    val resourceIds = index.symbolIndex.resourcesByPath.values
        .mapNotNullTo(linkedSetOf()) { resource ->
            val path = toProjectRelativePath(resource.source?.displayPath ?: resource.path, projectBasePath)
            resource.id.takeIf { path.isNotBlank() && pathMatchesSliceFiles(path, sliceFiles) }
        }
    return symbolIds + resourceIds
}

/**
 * 计算每条关系（relation.id）应被复制到哪些 slice。
 *
 * 跨 slice 关系：from/to 任一端在 slice 内，就把关系复制到该 slice 的 fragment 中。
 * 这样一端 slice 失效重建时，另一端的 fragment 仍持有完整关系副本，避免关系在视图里"消失"。
 * 旧实现只取一个 owner slice id，关系只写入一端，另一端 slice 失效后关系会丢失。
 */
internal fun relationOwnerSliceIds(
    manifest: ProjectSliceManifest,
    index: ArchitectureGraphIndex,
    projectBasePath: String?,
): Map<String, Set<String>> {
    val symbolToSliceId = linkedMapOf<String, String>()
    manifest.slices.forEach { slice ->
        sliceSymbolIds(slice, index, projectBasePath).forEach { symbolId ->
            symbolToSliceId.putIfAbsent(symbolId, slice.id)
        }
    }
    return index.relationIndex.relations.mapNotNull { relation ->
        val ownerSliceIds = linkedSetOf<String>()
        symbolToSliceId[relation.fromSymbolId]?.let(ownerSliceIds::add)
        symbolToSliceId[relation.toSymbolId]?.let(ownerSliceIds::add)
        if (ownerSliceIds.isEmpty()) null else relation.id to ownerSliceIds
    }.toMap()
}
