package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.JvmEvidenceRef
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget

/**
 * 类图快速索引工厂：仅基于结构关系（模块/包/类包含关系、类继承与字段类型引用等）
 * 构建一个轻量架构图索引，避免昂贵的全量关系解析。
 */
object ClassDiagramFastIndex {
    /** 表示关系完整度的标签：仅包含结构信息。 */
    const val RELATION_COMPLETENESS_PARTIAL = "STRUCTURE_ONLY"

    /** 基于符号索引构造结构关系索引与架构图索引。 */
    fun fromSymbols(
        symbolIndex: JvmSymbolIndex,
        budget: JvmResolutionBudget? = null,
    ): ArchitectureGraphIndex =
        ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(
                relations = structureRelations(symbolIndex),
                maxRelations = budget?.maxRelations ?: Int.MAX_VALUE,
            ),
            budget = budget,
        )

    /** 提取结构关系：模块→包→类包含关系、类继承/实现、字段类型引用等。 */
    internal fun structureRelations(symbolIndex: JvmSymbolIndex): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        symbolIndex.modulesByName.values.forEach { module ->
            symbolIndex.packagesByName.values
                .filter { pkg -> pkg.moduleName == module.qualifiedName }
                .forEach { pkg ->
                    relations += JvmRelation(
                        id = relationId(JvmRelationKind.MODULE_CONTAINS_PACKAGE, module.id, pkg.id),
                        kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                        fromSymbolId = module.id,
                        toSymbolId = pkg.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PROJECT_MODEL,
                        samples = listOf(evidence(pkg.source?.displayPath, "module contains package")),
                    )
                }
        }
        symbolIndex.classesByQualifiedName.values.forEach { classSymbol ->
            symbolIndex.packagesByName[classSymbol.packageName]?.let { pkg ->
                relations += JvmRelation(
                    id = relationId(JvmRelationKind.PACKAGE_CONTAINS_CLASS, pkg.id, classSymbol.id),
                    kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                    fromSymbolId = pkg.id,
                    toSymbolId = classSymbol.id,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PROJECT_MODEL,
                    samples = listOf(evidence(classSymbol.source?.displayPath, "package contains class")),
                )
            }
        }
        relations += ClassDiagramRelationExtractor.extractStructureRelations(
            symbolIndex = symbolIndex,
            extraMetadata = mapOf("classDiagram.relationCompleteness" to RELATION_COMPLETENESS_PARTIAL),
        )
        return relations
    }

    private fun relationId(
        kind: JvmRelationKind,
        fromSymbolId: String,
        toSymbolId: String,
    ): String =
        "${kind.name.lowercase()}:$fromSymbolId->$toSymbolId"
            .lowercase()
            .replace(Regex("[^a-z0-9:_>\\-]+"), "-")
            .trim('-')

    private fun evidence(
        filePath: String?,
        claim: String,
    ): JvmEvidenceRef =
        JvmEvidenceRef(
            filePath = filePath,
            virtualFileUrl = null,
            startLine = null,
            endLine = null,
            claim = claim,
        )
}

/**
 * 架构总览快速索引工厂：在结构关系基础上叠加架构维度（继承/实现/使用类型、SPI 提供方等），
 * 用于呈现"系统组成"层面的总览图，而不深入字段级关联等细节。
 */
object ArchitectureOverviewFastIndex {
    /** 表示关系完整度的标签：架构总览维度，仅包含宏观关系，不涉及细粒度字段引用。 */
    const val RELATION_COMPLETENESS_PARTIAL = "ARCHITECTURE_OVERVIEW"

    /**
     * 基于符号索引构造架构总览关系索引与架构图索引。
     *
     * @param symbolIndex 符号索引，提供模块、包、类与服务提供方等结构信息。
     * @param budget 解析预算，限制关系数量；为空表示不设上限。
     */
    fun fromSymbols(
        symbolIndex: JvmSymbolIndex,
        budget: JvmResolutionBudget? = null,
    ): ArchitectureGraphIndex =
        ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = JvmRelationIndex(
                relations = overviewRelations(symbolIndex),
                maxRelations = budget?.maxRelations ?: Int.MAX_VALUE,
            ),
            budget = budget,
        )

    /**
     * 提取架构总览关系：从结构关系中过滤出继承/实现/使用类型，并叠加服务提供方关系与资源绑定关系。
     * 通过 [linkedMapOf] 维护去重并保持稳定的插入顺序。
     */
    private fun overviewRelations(symbolIndex: JvmSymbolIndex): List<JvmRelation> {
        val relations = linkedMapOf<String, JvmRelation>()
        ClassDiagramFastIndex.structureRelations(symbolIndex)
            .filter { relation ->
                relation.kind in setOf(
                    JvmRelationKind.EXTENDS,
                    JvmRelationKind.IMPLEMENTS,
                    JvmRelationKind.USES_TYPE,
                )
            }
            .forEach { relation ->
                relations[relation.id] = relation.copy(
                    metadata = relation.metadata + mapOf(
                        "architecture.relationCompleteness" to RELATION_COMPLETENESS_PARTIAL,
                    ),
                )
            }
        serviceProviderRelations(symbolIndex).forEach { relation ->
            relations[relation.id] = relation
        }
        serviceProviderResourceBindings(symbolIndex).forEach { relation ->
            relations[relation.id] = relation
        }
        return relations.values.toList()
    }

    /**
     * 构造服务提供方关系：扫描 SPI 配置文件中声明的提供方类，把每个提供方与对应服务接口相连。
     * 当提供方类继承了服务接口时置信度为 PROVEN，否则降级为 AMBIGUOUS。
     */
    private fun serviceProviderRelations(symbolIndex: JvmSymbolIndex): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        symbolIndex.serviceProviderIndex.filesByInterfaceName.values.flatten().forEach { providerFile ->
            val serviceInterface = symbolIndex.findClass(providerFile.serviceInterfaceName) ?: return@forEach
            providerFile.providerClassNames.forEach { providerName ->
                val providerClass = symbolIndex.findClass(providerName) ?: return@forEach
                relations += JvmRelation(
                    id = relationId(
                        kind = JvmRelationKind.SPI_PROVIDES,
                        fromSymbolId = providerClass.id,
                        toSymbolId = serviceInterface.id,
                        qualifier = providerFile.resource.path,
                    ),
                    kind = JvmRelationKind.SPI_PROVIDES,
                    fromSymbolId = providerClass.id,
                    toSymbolId = serviceInterface.id,
                    confidence = if (providerClass.inheritsFrom(serviceInterface, symbolIndex)) {
                        JvmRelationConfidence.PROVEN
                    } else {
                        JvmRelationConfidence.AMBIGUOUS
                    },
                    source = JvmRelationSource.RESOURCE_FILE,
                    samples = listOf(providerFile.resource.source.evidence("SPI provider $providerName for ${serviceInterface.qualifiedName}")),
                    metadata = mapOf(
                        "resource.path" to providerFile.resource.path,
                        "service.interface" to serviceInterface.qualifiedName,
                        "architecture.relationCompleteness" to RELATION_COMPLETENESS_PARTIAL,
                    ),
                )
            }
        }
        return relations
    }

    /**
     * 判定当前类是否继承自目标类（包含父类、接口链上的任意层级）。
     * 采用广度优先遍历，避免回路导致死循环；命中即返回。
     */
    private fun JvmClassSymbol.inheritsFrom(
        target: JvmClassSymbol,
        symbolIndex: JvmSymbolIndex,
    ): Boolean {
        if (id == target.id) {
            return true
        }
        val visited = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<JvmClassSymbol>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.id)) {
                continue
            }
            sequenceOf(current.superClassName)
                .plus(current.interfaceNames.asSequence())
                .filterNotNull()
                .mapNotNull(symbolIndex::findClass)
                .forEach { next ->
                    if (next.id == target.id) {
                        return true
                    }
                    queue.add(next)
                }
        }
        return false
    }

    /**
     * 构造服务提供方资源绑定关系：把每个 SPI 配置文件（资源）直接关联到它声明的服务接口，
     * 用于在图谱中体现"资源文件声明了哪些服务"这一维度。
     */
    private fun serviceProviderResourceBindings(symbolIndex: JvmSymbolIndex): List<JvmRelation> {
        return symbolIndex.serviceProviderIndex.filesByInterfaceName.values
            .flatten()
            .mapNotNull { providerFile ->
                val serviceInterface = symbolIndex.findClass(providerFile.serviceInterfaceName) ?: return@mapNotNull null
                JvmRelation(
                    id = relationId(
                        kind = JvmRelationKind.RESOURCE_BINDS,
                        fromSymbolId = providerFile.resource.id,
                        toSymbolId = serviceInterface.id,
                        qualifier = providerFile.resource.path,
                    ),
                    kind = JvmRelationKind.RESOURCE_BINDS,
                    fromSymbolId = providerFile.resource.id,
                    toSymbolId = serviceInterface.id,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.RESOURCE_FILE,
                    samples = listOf(
                        providerFile.resource.source.evidence(
                            "service provider file declares ${serviceInterface.qualifiedName}",
                        ),
                    ),
                    metadata = mapOf(
                        "resource.path" to providerFile.resource.path,
                        "service.interface" to serviceInterface.qualifiedName,
                        "architecture.relationCompleteness" to RELATION_COMPLETENESS_PARTIAL,
                    ),
                )
            }
    }

    /**
     * 生成关系稳定 ID：组合关系种类、起止符号 ID 及可选 qualifier，
     * 再做小写化和非法字符替换，保证 ID 可作为键安全使用。
     */
    private fun relationId(
        kind: JvmRelationKind,
        fromSymbolId: String,
        toSymbolId: String,
        qualifier: String? = null,
    ): String {
        val raw = buildString {
            append(kind.name.lowercase())
            append(':')
            append(fromSymbolId)
            append("->")
            append(toSymbolId)
            qualifier?.takeIf(String::isNotBlank)?.let { value ->
                append(':')
                append(value)
            }
        }
        return raw.lowercase().replace(Regex("[^a-z0-9:_>\\-]+"), "-").trim('-')
    }

    /**
     * 把源码引用信息转换为证据对象，[claim] 描述该证据所支持的结论；
     * 接收方可空表示当前关系可能没有具体源码定位（如纯结构推断）。
     */
    private fun com.charmnight.linkgraph.jvm.index.JvmSourceRef?.evidence(claim: String): JvmEvidenceRef =
        JvmEvidenceRef(
            filePath = this?.displayPath,
            virtualFileUrl = this?.virtualFileUrl,
            startLine = this?.startLine,
            endLine = this?.endLine,
            claim = claim,
            decompiled = this?.decompiled ?: false,
        )
}
