package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

class ArchitectureGraphQueryService(
    private val index: ArchitectureGraphIndex,
) {
    fun lookupClass(qualifiedName: String): JvmClassSymbol? =
        index.findClass(qualifiedName.trim())

    fun upstreamOneHop(nodeId: String): List<ArchitectureNode> =
        index.upstreamOneHop(nodeId)

    fun downstreamOneHop(nodeId: String): List<ArchitectureNode> =
        index.downstreamOneHop(nodeId)

    fun classesInScope(scopeNodeId: String): List<JvmClassSymbol> =
        index.classesInScope(scopeNodeId)

    fun findSymbol(query: String): List<JvmSymbol> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        index.findSymbol(normalized)?.let { return listOf(it) }
        index.findClass(normalized)?.let { return listOf(it) }
        index.findMethod(normalized)?.let { return listOf(it) }
        index.findField(normalized)?.let { return listOf(it) }
        return index.symbolIndex.symbolsById.values
            .filter { symbol ->
                symbol.qualifiedName.contains(normalized, ignoreCase = true) ||
                    symbol.simpleName.contains(normalized, ignoreCase = true)
            }
            .sortedBy { symbol -> symbol.qualifiedName }
            .take(50)
    }

    fun relationsForSymbol(
        symbolIdOrName: String,
        kind: JvmRelationKind? = null,
        direction: RelationDirection = RelationDirection.BOTH,
    ): List<JvmRelation> {
        val symbolIds = findSymbol(symbolIdOrName).mapTo(linkedSetOf()) { symbol -> symbol.id }
        if (symbolIds.isEmpty()) {
            return emptyList()
        }
        return symbolIds.flatMap { symbolId ->
            when (direction) {
                RelationDirection.OUTGOING -> index.relationIndex.outgoing(symbolId)
                RelationDirection.INCOMING -> index.relationIndex.incoming(symbolId)
                RelationDirection.BOTH -> index.relationIndex.outgoing(symbolId) + index.relationIndex.incoming(symbolId)
            }
        }
            .filter { relation -> kind == null || relation.kind == kind }
            .distinctBy(JvmRelation::id)
            .sortedWith(compareBy({ it.kind.name }, { it.fromSymbolId }, { it.toSymbolId }))
    }

    fun serviceProviders(interfaceName: String): List<JvmRelation> {
        val interfaceSymbol = index.findClass(interfaceName.trim()) ?: return emptyList()
        return index.relationIndex.incoming(interfaceSymbol.id)
            .filter { relation -> relation.kind == JvmRelationKind.SPI_PROVIDES }
            .sortedBy(JvmRelation::fromSymbolId)
    }

    fun reflectionTargets(symbolIdOrName: String): List<JvmRelation> =
        relationsForSymbol(symbolIdOrName, JvmRelationKind.REFLECTS_TO, RelationDirection.OUTGOING)

    fun upstream(symbolIdOrName: String, depth: Int = 1): List<JvmSymbol> =
        traverse(symbolIdOrName, depth.coerceIn(1, 5), incoming = true)

    fun downstream(symbolIdOrName: String, depth: Int = 1): List<JvmSymbol> =
        traverse(symbolIdOrName, depth.coerceIn(1, 5), incoming = false)

    fun summary(): ArchitectureIndexSummary =
        ArchitectureIndexSummary(
            moduleCount = index.symbolIndex.modulesByName.size,
            packageCount = index.symbolIndex.packagesByName.size,
            classCount = index.symbolIndex.classesByQualifiedName.size,
            methodCount = index.symbolIndex.methodsBySignature.size,
            fieldCount = index.symbolIndex.fieldsByQualifiedName.size,
            resourceCount = index.symbolIndex.resourcesByPath.size,
            relationCount = index.relationIndex.relations.size,
            spiProviderCount = index.relationIndex.byKind(JvmRelationKind.SPI_PROVIDES).size,
            reflectionRelationCount = index.relationIndex.byKind(JvmRelationKind.REFLECTS_TO).size,
            serviceLoaderRelationCount = index.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).size,
            proxyRelationCount = index.relationIndex.byKind(JvmRelationKind.USES_PROXY).size,
            testRelationCount = index.relationIndex.byKind(JvmRelationKind.TESTS).size,
            externalClassCount = index.symbolIndex.classesByQualifiedName.values.count { symbol -> symbol.external },
        )

    private fun traverse(
        symbolIdOrName: String,
        depth: Int,
        incoming: Boolean,
    ): List<JvmSymbol> {
        val roots = findSymbol(symbolIdOrName).mapTo(linkedSetOf()) { symbol -> symbol.id }
        val visited = roots.toMutableSet()
        val result = linkedSetOf<String>()
        var frontier = roots
        repeat(depth) {
            val next = linkedSetOf<String>()
            frontier.forEach { symbolId ->
                val relations = if (incoming) {
                    index.relationIndex.incoming(symbolId)
                } else {
                    index.relationIndex.outgoing(symbolId)
                }
                relations.forEach { relation ->
                    val candidate = if (incoming) relation.fromSymbolId else relation.toSymbolId
                    if (visited.add(candidate)) {
                        result += candidate
                        next += candidate
                    }
                }
            }
            frontier = next
            if (frontier.isEmpty()) {
                return@repeat
            }
        }
        return result.mapNotNull(index::findSymbol)
    }
}

enum class RelationDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

data class ArchitectureIndexSummary(
    val moduleCount: Int,
    val packageCount: Int,
    val classCount: Int,
    val methodCount: Int,
    val fieldCount: Int = 0,
    val resourceCount: Int,
    val relationCount: Int,
    val spiProviderCount: Int,
    val reflectionRelationCount: Int,
    val serviceLoaderRelationCount: Int = 0,
    val proxyRelationCount: Int = 0,
    val testRelationCount: Int = 0,
    val externalClassCount: Int,
)
