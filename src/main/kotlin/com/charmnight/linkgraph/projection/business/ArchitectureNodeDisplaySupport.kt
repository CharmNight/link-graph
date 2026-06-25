package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype

/** 常见组织前缀段，用于展示时剥除命名空间前缀。 */
private val ORGANIZATION_PREFIX_SEGMENTS = setOf("com", "org", "net", "io", "dev")

/** 辅助/测试性质的包名段，命中即视为非项目主体代码。 */
private val SUPPORT_PACKAGE_SEGMENTS = setOf(
    "benchmark", "benchmarks", "demo", "docker", "example", "examples",
    "fixture", "fixtures", "mock", "mocks", "sample", "samples",
    "test", "testing", "tests",
)

/** 辅助/测试性质的源码路径段，命中即视为非项目主体代码。 */
private val SUPPORT_SOURCE_PATH_SEGMENTS = SUPPORT_PACKAGE_SEGMENTS + setOf(
    "src/test", "src/integrationtest", "src/integration-test",
)

/** ArchitectureGraphProjector 的节点展示名称 / 结构排序 / 辅助节点判定 helper（P2-1 深度拆分）。 */

/** 返回结构视图下的节点可读名称：RESOURCE 用 title，其他取 title 或全限定名最后一段。 */
internal fun ArchitectureNode.readableStructureName(): String {
    if (kind == ArchitectureNodeKind.RESOURCE) {
        return title
    }
    return title.ifBlank { qualifiedName.substringAfterLast('.') }
}

/** 构造节点在结构视图中的子标题，区分资源、服务、组件、外部依赖与 JDK。 */
internal fun ArchitectureNode.structureSubtitle(
    displayName: String,
    displayLayer: ArchitectureDisplayLayer,
): String = when (kind) {
    ArchitectureNodeKind.RESOURCE -> "资源 · ${memberResourceIds.size.coerceAtLeast(1)} 项"
    ArchitectureNodeKind.SERVICE -> "${displayLayer.label} · 服务边界"
    ArchitectureNodeKind.COMPONENT -> "${displayLayer.label} · 组件"
    ArchitectureNodeKind.LIBRARY -> "外部依赖 · ${memberClassIds.size} 类型"
    ArchitectureNodeKind.JDK -> "JDK · ${memberClassIds.size} 类型"
    else -> displayLayer.label
}

/**
 * 计算节点在结构视图中的排序权重。数值越小越靠前。
 *
 * 综合考虑：聚合是否过宽（90_000）、辅助节点惩罚（5_000）、孤立节点惩罚（2_000）、
 * 分层权重 × 100、成员角色权重 × 10、名称权重、成员数加分。
 */
internal fun ArchitectureNode.structureRank(
    index: ArchitectureGraphIndex,
    displayLayer: ArchitectureDisplayLayer,
    tooBroad: Boolean,
    supportNode: Boolean,
    relationBackedNode: Boolean,
): Int {
    if (tooBroad) {
        return 90_000
    }
    val name = readableStructureName().lowercase()
    val nameRank = when (name) {
        "api", "controller", "web" -> 0
        "service", "application", "app" -> 1
        "domain", "model" -> 2
        "repository", "dao", "mapper", "data" -> 3
        "config", "infra", "infrastructure" -> 4
        "resource", "resources" -> 5
        else -> 40
    }
    val supportPenalty = if (supportNode) 5_000 else 0
    val orphanPenalty = if (relationBackedNode) 0 else 2_000
    val roleRank = memberRoleRank(index)
    val sizeBoost = (100 - memberClassIds.size.coerceAtMost(100)).coerceAtLeast(0)
    return supportPenalty + orphanPenalty + displayLayer.order * 100 + roleRank * 10 + nameRank + sizeBoost
}

/**
 * 根据成员类中最高优先级的 Stereotype 推断角色权重。
 *
 * Controller(0) > Service(1) > Repository(2) > Configuration(3) > 其他(Int.MAX_VALUE)。
 */
internal fun ArchitectureNode.memberRoleRank(index: ArchitectureGraphIndex): Int {
    val memberClasses = memberClassIds.mapNotNull { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
    if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.CONTROLLER }) return 0
    if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.SERVICE }) return 1
    if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.REPOSITORY }) return 2
    if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.CONFIGURATION }) return 3
    return Int.MAX_VALUE
}

/** 计算节点的可读基名：剥除公共前缀 + 组织前缀 + 模块段后保留的唯一片段。 */
internal fun ArchitectureNode.readableStructureBaseName(allNodes: List<ArchitectureNode>): String {
    if (kind == ArchitectureNodeKind.RESOURCE) {
        return title
    }
    val parts = qualifiedName.split('.').filter(String::isNotBlank)
    if (parts.isEmpty()) {
        return title.ifBlank { qualifiedName }
    }
    val projectNames = allNodes
        .asSequence()
        .map { node -> node.qualifiedName.split('.').filter(String::isNotBlank) }
        .filter(List<String>::isNotEmpty)
        .toList()
    val rootSize = commonRootSize(projectNames)
    val rootTrimmedParts = parts.drop(rootSize).takeIf(List<String>::isNotEmpty) ?: parts
    return projectNamespaceTrimmedParts(rootTrimmedParts)
        .joinToString(".")
        .ifBlank { title.ifBlank { qualifiedName } }
}

/** 在分段列表中再剥除模块名/组织前缀，得到更短的展示用命名片段。 */
internal fun ArchitectureNode.projectNamespaceTrimmedParts(parts: List<String>): List<String> {
    val moduleSegment = moduleName
        ?.substringAfterLast(':')
        ?.substringBeforeLast('.')
        ?.lowercase()
        ?.takeIf(String::isNotBlank)
    if (moduleSegment != null) {
        val moduleIndex = parts.indexOfFirst { part -> part.lowercase() == moduleSegment }
        if (moduleIndex >= 0 && moduleIndex < parts.lastIndex) {
            return parts.drop(moduleIndex + 1)
        }
    }
    val organizationTrimmedParts = parts.dropWhile { part -> part.lowercase() in ORGANIZATION_PREFIX_SEGMENTS }
    return organizationTrimmedParts.takeIf(List<String>::isNotEmpty) ?: parts
}

/** 计算节点在全节点集合中最短且唯一的名称后缀。从最短 2 段开始尝试。 */
internal fun ArchitectureNode.shortestUniqueStructureName(allNodes: List<ArchitectureNode>): String {
    if (kind == ArchitectureNodeKind.RESOURCE) {
        return title
    }
    val parts = qualifiedName.split('.').filter(String::isNotBlank)
    if (parts.size <= 2) {
        return qualifiedName.ifBlank { title }
    }
    for (suffixSize in 2..parts.size) {
        val suffix = parts.takeLast(suffixSize).joinToString(".")
        val collides = allNodes.any { other ->
            other.id != id &&
                other.kind != ArchitectureNodeKind.RESOURCE &&
                other.qualifiedName
                    .split('.')
                    .filter(String::isNotBlank)
                    .takeLast(suffixSize)
                    .joinToString(".") == suffix
        }
        if (!collides) {
            return suffix
        }
    }
    return qualifiedName.ifBlank { title }
}

/** 判定组件/服务聚合是否过于宽泛：命名层级过浅或成员类占比过高。 */
internal fun ArchitectureNode.isBroadProjectStructureAggregate(index: ArchitectureGraphIndex): Boolean {
    if (kind !in setOf(ArchitectureNodeKind.COMPONENT, ArchitectureNodeKind.SERVICE)) {
        return false
    }
    val parts = qualifiedName.split('.').filter(String::isNotBlank)
    if (parts.size <= 1) {
        return true
    }
    val memberSymbols = memberClassIds.mapNotNull { memberId -> index.findSymbol(memberId) }
    if (memberSymbols.isEmpty()) {
        return false
    }
    val memberPaths = memberSymbols.mapNotNull { symbol -> symbol.source?.displayPath }
    return memberPaths.size >= 20
}

/** 判定节点是否属于辅助/测试性质：包名或源码路径命中 support 段集合。 */
internal fun ArchitectureNode.isSupportProjectStructureNode(index: ArchitectureGraphIndex): Boolean {
    if (kind == ArchitectureNodeKind.RESOURCE) {
        return memberResourceIds.mapNotNull { memberId -> index.findSymbol(memberId) }
            .mapNotNull { symbol -> symbol.source?.displayPath }
            .any { path -> path.hasSupportSourcePath() }
    }
    val packageName = packageName ?: return false
    if (packageName.split('.').any { segment -> segment.lowercase() in SUPPORT_PACKAGE_SEGMENTS }) {
        return true
    }
    return memberClassIds.mapNotNull { memberId -> index.findSymbol(memberId) }
        .mapNotNull { symbol -> symbol.source?.displayPath }
        .any { path -> path.hasSupportSourcePath() }
}

/** 判定节点是否在项目结构视图中可见：非辅助节点且有成员类或资源。 */
internal fun ArchitectureNode.isVisibleProjectStructureNode(index: ArchitectureGraphIndex): Boolean {
    if (isSupportProjectStructureNode(index)) {
        return false
    }
    return memberClassIds.isNotEmpty() || memberResourceIds.isNotEmpty()
}

/** 返回节点所有成员资源的 display path 列表。 */
internal fun ArchitectureNode.resourcePaths(index: ArchitectureGraphIndex): List<String> =
    memberResourceIds.mapNotNull { memberId -> index.findSymbol(memberId) }
        .mapNotNull { symbol -> symbol.source?.displayPath }

/** 判定源码路径是否属于辅助/测试路径。 */
private fun String.hasSupportSourcePath(): Boolean {
    val segments = replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
        .map(String::lowercase)
    return segments.any { segment -> segment in SUPPORT_SOURCE_PATH_SEGMENTS }
}
