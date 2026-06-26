package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexMemorySnapshot
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArchitectureGraphQueryServiceTest {
    @Test
    fun exposesProjectGraphPathExplanationAffectedNodesAndDigest() {
        val index = testIndex()
        val service = ArchitectureGraphQueryService(
            index = index,
            memorySnapshot = ArchitectureIndexMemorySnapshot(
                staleSliceIds = listOf("jvm_source_main_com.example.orders"),
                persistentCacheHits = 3,
                persistentCacheMisses = 1,
            ),
        )

        assertEquals(2, service.queryProjectGraph("Order", budget = 4, mode = TraversalMode.NEIGHBORHOOD).symbols.size)
        assertEquals(listOf("class:OrderService", "class:OrderRepository"), service.shortestPath("OrderService", "OrderRepository")?.symbolIds)
        assertEquals("class:OrderService", assertNotNull(service.explainNode("OrderService")).symbol.id)
        assertEquals(listOf("class:OrderRepository"), service.affectedNodes("OrderService").downstream.map { it.id })
        val digest = service.communityOrPackageDigest("com.example.orders")
        assertEquals(2, digest.coreNodes.size)
        assertEquals(listOf("jvm_source_main_com.example.orders"), digest.staleSlices)
        assertEquals(0.75, digest.cacheHitRate)
    }

    private fun testIndex(): ArchitectureGraphIndex {
        val service = classSymbol("class:OrderService", "com.example.orders.OrderService")
        val repository = classSymbol("class:OrderRepository", "com.example.orders.OrderRepository")
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(classesByQualifiedName = listOf(service, repository).associateBy(JvmClassSymbol::qualifiedName)),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation(
                        id = "rel:service-repo",
                        kind = JvmRelationKind.USES_TYPE,
                        fromSymbolId = service.id,
                        toSymbolId = repository.id,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                    ),
                ),
            ),
        )
    }

    private fun classSymbol(id: String, qualifiedName: String): JvmClassSymbol =
        JvmClassSymbol(
            id = id,
            qualifiedName = qualifiedName,
            simpleName = qualifiedName.substringAfterLast('.'),
            packageName = qualifiedName.substringBeforeLast('.'),
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )
}
