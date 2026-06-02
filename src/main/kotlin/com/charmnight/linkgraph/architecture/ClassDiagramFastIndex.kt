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

object ClassDiagramFastIndex {
    const val RELATION_COMPLETENESS_PARTIAL = "STRUCTURE_ONLY"

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

object ArchitectureOverviewFastIndex {
    const val RELATION_COMPLETENESS_PARTIAL = "ARCHITECTURE_OVERVIEW"

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
