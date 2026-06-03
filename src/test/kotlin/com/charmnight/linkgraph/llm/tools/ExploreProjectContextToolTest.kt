package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.query.ProjectSemanticSeedIndex
import com.charmnight.linkgraph.architecture.query.ProjectSemanticSeedRecord
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class ExploreProjectContextToolTest : BasePlatformTestCase() {
    fun testReturnsBoundedSymbolsRelationsSnippetsChangedSymbolsFreshnessAndWarnings() {
        val index = testIndex()
        val tool = ExploreProjectContextTool(ArchitectureIndexToolFacade { index })

        val result = tool.invoke(
            input = mapOf(
                "query" to "Order",
                "changedFiles" to listOf("src/main/java/com/example/orders/OrderService.java"),
                "depth" to 2,
                "maxSymbols" to 1,
                "maxRelations" to 1,
                "maxSnippets" to 1,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = ToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("explore_project_context", result.toolName)
        assertEquals("OK", result.payload["status"])
        assertEquals(1, (result.payload["symbols"] as List<*>).size)
        assertEquals(1, (result.payload["relations"] as List<*>).size)
        assertEquals(1, (result.payload["snippets"] as List<*>).size)
        assert((result.payload["freshness"] as Map<*, *>)["state"] in setOf("FRESH", "STALE", "BUILDING"))
        assertEquals(emptyList<String>(), result.payload["warnings"])
    }

    fun testSemanticSeedsAreRecallOnlyWhenExplicitlyEnabled() {
        val index = testIndex()
        val tool = ExploreProjectContextTool(
            ArchitectureIndexToolFacade(
                indexProvider = { index },
                semanticSeedIndexProvider = {
                    ProjectSemanticSeedIndex(
                        enabled = true,
                        records = listOf(
                            ProjectSemanticSeedRecord(
                                nodeId = "class:OrderService",
                                text = "checkout orchestration",
                                textHash = "semantic-hash",
                                embeddingModelId = "test-model",
                                sliceId = "slice:orders",
                            ),
                        ),
                    )
                },
            ),
        )

        val result = tool.invoke(
            input = mapOf("query" to "checkout", "maxSymbols" to 2),
            context = ToolExecutionContext(project, ToolGraphSnapshot(), InMemoryArtifactStore(), RunBudget()),
        )

        val symbols = result.payload["symbols"] as List<*>
        val semanticSymbol = symbols.filterIsInstance<Map<*, *>>().single { symbol -> symbol["id"] == "class:OrderService" }
        assertEquals("SEMANTIC_SEED", semanticSymbol["evidence.kind"])
        assertEquals("RECALL_ONLY", semanticSymbol["evidence.role"])
        assertEquals("semantic-hash", semanticSymbol["semantic.textHash"])
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
