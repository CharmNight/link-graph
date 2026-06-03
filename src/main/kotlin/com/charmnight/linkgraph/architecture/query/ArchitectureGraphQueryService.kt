package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexMemorySnapshot
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import java.util.ArrayDeque

class ArchitectureGraphQueryService(
    private val index: ArchitectureGraphIndex,
    private val memorySnapshot: ArchitectureIndexMemorySnapshot = ArchitectureIndexMemorySnapshot(),
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
        return rankedFindSymbol(normalized).map(ArchitectureSymbolSearchResult::symbol)
    }

    fun rankedFindSymbol(query: String, limit: Int = 50): List<ArchitectureSymbolSearchResult> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        index.findSymbol(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 1000, "ID_EXACT")) }
        index.findClass(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        index.findMethod(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        index.findField(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        return ArchitectureSymbolSearch(index.symbolIndex).search(normalized, limit)
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

    fun queryProjectGraph(question: String, budget: Int, mode: TraversalMode): ProjectGraphQueryResult {
        val roots = rankedFindSymbol(question, budget.coerceIn(1, 50)).map(ArchitectureSymbolSearchResult::symbol)
        val relations = roots
            .flatMap { symbol ->
                when (mode) {
                    TraversalMode.UPSTREAM -> index.relationIndex.incoming(symbol.id)
                    TraversalMode.DOWNSTREAM -> index.relationIndex.outgoing(symbol.id)
                    TraversalMode.NEIGHBORHOOD -> index.relationIndex.incoming(symbol.id) + index.relationIndex.outgoing(symbol.id)
                }
            }
            .distinctBy(JvmRelation::id)
            .take(budget.coerceAtLeast(1))
        val relatedIds = relations.flatMap { relation -> listOf(relation.fromSymbolId, relation.toSymbolId) }.toSet()
        val symbols = (roots + relatedIds.mapNotNull(index::findSymbol))
            .distinctBy(JvmSymbol::id)
            .take(budget.coerceAtLeast(1))
            .map(::symbolPayload)
        return ProjectGraphQueryResult(symbols = symbols, relations = relations.map(::relationPayload))
    }

    fun shortestPath(from: String, to: String, maxDepth: Int = 6): ProjectGraphPath? {
        val fromId = findSymbol(from).firstOrNull()?.id ?: return null
        val toId = findSymbol(to).firstOrNull()?.id ?: return null
        if (fromId == toId) {
            return ProjectGraphPath(symbolIds = listOf(fromId), relationIds = emptyList())
        }
        val queue = ArrayDeque<ProjectGraphPath>()
        queue += ProjectGraphPath(symbolIds = listOf(fromId), relationIds = emptyList())
        val visited = linkedSetOf(fromId)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path.symbolIds.size > maxDepth.coerceAtLeast(1) + 1) {
                continue
            }
            val last = path.symbolIds.last()
            val edges = (index.relationIndex.outgoing(last) + index.relationIndex.incoming(last)).distinctBy(JvmRelation::id)
            edges.forEach { relation ->
                val next = if (relation.fromSymbolId == last) relation.toSymbolId else relation.fromSymbolId
                if (!visited.add(next)) {
                    return@forEach
                }
                val nextPath = ProjectGraphPath(path.symbolIds + next, path.relationIds + relation.id)
                if (next == toId) {
                    return nextPath
                }
                queue += nextPath
            }
        }
        return null
    }

    fun explainNode(symbolOrNodeId: String): ProjectNodeExplanation? {
        val symbol = findSymbol(symbolOrNodeId).firstOrNull() ?: return null
        val incoming = index.relationIndex.incoming(symbol.id).map(::relationPayload)
        val outgoing = index.relationIndex.outgoing(symbol.id).map(::relationPayload)
        return ProjectNodeExplanation(
            symbol = symbolPayload(symbol),
            incomingRelations = incoming,
            outgoingRelations = outgoing,
        )
    }

    fun affectedNodes(symbolOrFile: String, depth: Int = 2): ProjectAffectedNodes {
        val downstream = downstream(symbolOrFile, depth).map(::symbolPayload)
        val upstream = upstream(symbolOrFile, depth).map(::symbolPayload)
        return ProjectAffectedNodes(upstream = upstream, downstream = downstream)
    }

    fun communityOrPackageDigest(scope: String): ProjectIndexDigest {
        val normalized = scope.trim()
        val scopedSymbols = index.symbolIndex.symbolsById.values
            .filter { symbol -> normalized.isBlank() || symbol.qualifiedName.contains(normalized, ignoreCase = true) }
            .sortedBy(JvmSymbol::qualifiedName)
        val degreeById = index.relationIndex.relations
            .flatMap { relation -> listOf(relation.fromSymbolId, relation.toSymbolId) }
            .groupingBy { it }
            .eachCount()
        return ProjectIndexDigest(
            coreNodes = scopedSymbols.take(20).map(::symbolPayload),
            highDegreeNodes = scopedSymbols.sortedByDescending { symbol -> degreeById[symbol.id] ?: 0 }.take(10).map(::symbolPayload),
            staleSlices = memorySnapshot.staleSliceIds,
            cacheHitRate = memorySnapshot.cacheHitRate,
            suggestedQuestions = listOf("哪些符号依赖 $scope?", "$scope 的入口点是什么?"),
        )
    }

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

    private fun symbolPayload(symbol: JvmSymbol): Map<String, Any?> =
        mapOf(
            "id" to symbol.id,
            "qualifiedName" to symbol.qualifiedName,
            "simpleName" to symbol.simpleName,
            "filePath" to symbol.source?.displayPath,
            "origin" to symbol.origin.name,
        )

    private fun relationPayload(relation: JvmRelation): Map<String, Any?> =
        mapOf(
            "id" to relation.id,
            "kind" to relation.kind.name,
            "fromSymbolId" to relation.fromSymbolId,
            "toSymbolId" to relation.toSymbolId,
            "metadata" to relation.metadata,
        )
}

enum class RelationDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

enum class TraversalMode {
    NEIGHBORHOOD,
    UPSTREAM,
    DOWNSTREAM,
}

data class ProjectGraphQueryResult(
    val symbols: List<Map<String, Any?>>,
    val relations: List<Map<String, Any?>>,
)

data class ProjectGraphPath(
    val symbolIds: List<String>,
    val relationIds: List<String>,
)

data class ProjectNodeExplanation(
    val symbol: Map<String, Any?>,
    val incomingRelations: List<Map<String, Any?>>,
    val outgoingRelations: List<Map<String, Any?>>,
)

data class ProjectAffectedNodes(
    val upstream: List<Map<String, Any?>>,
    val downstream: List<Map<String, Any?>>,
)

data class ProjectIndexDigest(
    val coreNodes: List<Map<String, Any?>>,
    val highDegreeNodes: List<Map<String, Any?>>,
    val staleSlices: List<String>,
    val cacheHitRate: Double?,
    val suggestedQuestions: List<String>,
)

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
