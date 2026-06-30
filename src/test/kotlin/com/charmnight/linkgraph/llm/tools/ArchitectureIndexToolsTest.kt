package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArchitectureIndexToolsTest : BasePlatformTestCase() {
    fun testProjectGraphToolsReturnDeterministicPayloads() {
        val facade = ArchitectureIndexToolFacade { testIndex() }
        val context = ToolExecutionContext(project, ToolGraphSnapshot(), InMemoryArtifactStore(), RunBudget())

        val query = QueryProjectGraphTool(facade).invoke(mapOf("question" to "Order", "budget" to 4), context)
        val path = FindProjectPathTool(facade).invoke(mapOf("from" to "OrderService", "to" to "OrderRepository"), context)
        val explanation = ExplainProjectNodeTool(facade).invoke(mapOf("symbol" to "OrderService"), context)
        val affected = AffectedProjectNodesTool(facade).invoke(mapOf("symbol" to "OrderService"), context)
        val digest = GetProjectIndexDigestTool(facade).invoke(mapOf("scope" to "com.example.orders"), context)

        assertEquals("query_project_graph", query.toolName)
        assertNotNull(query.payload["result"])
        assertEquals("find_project_path", path.toolName)
        assertNotNull(path.payload["path"])
        assertEquals("explain_project_node", explanation.toolName)
        assertNotNull(explanation.payload["explanation"])
        assertEquals("affected_project_nodes", affected.toolName)
        assertNotNull(affected.payload["affected"])
        assertEquals("get_project_index_digest", digest.toolName)
        assertNotNull(digest.payload["digest"])
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
