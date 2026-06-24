package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.memory.FieldTypeReferenceSliceFragment
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import java.security.MessageDigest

/**
 * ArchitectureIndexRuntime 的纯展示 / 计算 helper 集合（P2-1 拆分）。
 *
 * 这些函数无状态、无副作用，与 ArchitectureIndexRuntime 的索引构建 / 缓存 / 查询调度
 * 主流程解耦后便于复用与单独测试。
 */

/** 把 JvmSourceRef 的 displayPath 归一化为项目相对路径；不可解析时返回空串。 */
internal fun persistedDisplayPath(
    displayPath: String?,
    projectBasePath: String?,
): String {
    val normalized = displayPath
        ?.replace('\\', '/')
        ?.trim()
        ?: return ""
    val basePath = projectBasePath?.replace('\\', '/') ?: return normalized
    return normalized.removePrefix("$basePath/")
}

/** 把 displayPath（可能含 jar 内 "!/" 分隔）归一化为项目相对路径。 */
internal fun toProjectRelativePath(
    displayPath: String?,
    projectBasePath: String?,
): String {
    val normalized = displayPath
        ?.substringBefore("!/")
        ?.replace('\\', '/')
        ?.trim()
        ?: return ""
    val basePath = projectBasePath?.replace('\\', '/') ?: return normalized
    return normalized.removePrefix("$basePath/")
}

/**
 * 判断给定 path 是否属于 slice files 集合。
 * 匹配规则：完全相等，或 path 是某个 slicePath 的后缀（"/Foo.java" 匹配 slicePath "pkg/Foo.java"），
 * 或反之。用于 project slice 文件过滤。
 */
internal fun pathMatchesSliceFiles(path: String, sliceFiles: Set<String>): Boolean =
    path in sliceFiles ||
        sliceFiles.any { slicePath ->
            slicePath.endsWith("/$path") || path.endsWith("/$slicePath")
        }

/** 把 JvmFieldSymbol 的 typeReferences 映射为 slice fragment 列表，供持久化使用。 */
internal fun fieldTypeReferenceFragments(symbol: JvmFieldSymbol): List<FieldTypeReferenceSliceFragment> =
    symbol.typeReferences.map { reference ->
        FieldTypeReferenceSliceFragment(
            typeName = reference.typeName,
            role = reference.role.name,
        )
    }

/** 稳定的 SHA-256 hex 摘要；用于把 budget / cache key 等组合值哈希成稳定字符串。 */
internal fun stableSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * 把 JvmSourceRef 的 displayPath 折叠为持久化用的相对路径；不可解析时返回 null。
 * 与 [persistedDisplayPath] 区别：本函数额外处理 source 为 null 的场景，返回 nullable。
 */
internal fun persistedSourcePath(source: JvmSourceRef?, projectBasePath: String?): String? =
    persistedDisplayPath(source?.displayPath, projectBasePath).takeIf(String::isNotBlank)
