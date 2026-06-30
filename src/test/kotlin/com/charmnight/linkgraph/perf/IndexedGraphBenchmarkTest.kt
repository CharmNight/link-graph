package com.charmnight.linkgraph.perf

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.llm.tools.ArchitectureIndexToolFacade
import com.charmnight.linkgraph.llm.tools.ExploreProjectContextTool
import com.charmnight.linkgraph.agent.tools.ToolExecutionContext
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndexedGraphBenchmarkTest : BasePlatformTestCase() {
    fun testBenchmarkRunnerMeasuresProjectorsExploreToolAndPersistentCacheStats() {
        val index = testIndex()
        val context = ToolExecutionContext(project, ToolGraphSnapshot(), InMemoryArtifactStore(), RunBudget())
        val report = IndexedGraphBenchmarkRunner(
            coldFullIndex = { testIndex() },
            agentExploreTool = {
                ExploreProjectContextTool(ArchitectureIndexToolFacade { index }).invoke(
                    input = mapOf("query" to "Order", "maxSymbols" to 2, "maxRelations" to 2, "maxSnippets" to 1),
                    context = context,
                )
            },
            persistentCacheStats = { IndexedGraphBenchmarkCacheStats(hits = 2, misses = 1) },
        ).run().toJson()
        val parsed = JsonCodec.parseObject(report, "indexed graph benchmark report")

        listOf(
            "coldFullIndexMillis",
            "architectureOverviewMillis",
            "classDiagramFirstCompleteMillis",
            "reviewGraphMillis",
            "architecturePayloadBytes",
            "classDiagramPayloadBytes",
            "reviewPayloadBytes",
            "agentExploreToolMillis",
            "agentExplorePayloadBytes",
            "persistentCacheHits",
            "persistentCacheMisses",
        ).forEach { key ->
            assertNotNull(parsed[key], "$key must be present")
            assertTrue((parsed[key] as Number).toLong() >= 0L, "$key must be non-negative")
        }
        assertTrue((parsed["architecturePayloadBytes"] as Number).toLong() > 0L)
        assertTrue((parsed["classDiagramPayloadBytes"] as Number).toLong() > 0L)
        assertTrue((parsed["reviewPayloadBytes"] as Number).toLong() > 0L)
        assertTrue((parsed["agentExplorePayloadBytes"] as Number).toLong() > 0L)
        assertTrue((parsed["persistentCacheHits"] as Number).toLong() == 2L)
        assertTrue((parsed["persistentCacheMisses"] as Number).toLong() == 1L)
    }

    private fun testIndex(): ArchitectureGraphIndex {
        val service = classSymbol("class:OrderService", "com.example.orders.OrderService")
        val repository = classSymbol("class:OrderRepository", "com.example.orders.OrderRepository")
        return ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(classesByQualifiedName = listOf(service, repository).associateBy(JvmClassSymbol::qualifiedName)),
            relationIndex = JvmRelationIndex(
                listOf(
                    JvmRelation("rel:service-repo", JvmRelationKind.USES_TYPE, service.id, repository.id, JvmRelationConfidence.PROVEN, JvmRelationSource.PSI),
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
