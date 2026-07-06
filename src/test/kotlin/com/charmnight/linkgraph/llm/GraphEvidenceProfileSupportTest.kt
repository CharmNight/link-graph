package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEvidenceProfileSupportTest {
    @Test
    fun buildGraphEvidenceProfileScansRelationEdgesOnceForAnchorCountsAndKinds() {
        val nodes = listOf(node(1), node(2), node(3))
        val edges = CountingList(
            listOf(
                edge(1, from = "node-2", to = "node-1", type = EdgeType.CALL),
                edge(2, from = "node-1", to = "node-3", type = EdgeType.USES_TYPE),
                edge(3, from = "node-2", to = "node-3", type = EdgeType.LINKS_DOC),
            ),
        )

        val profile = buildGraphEvidenceProfile(
            graph = GraphDocument(nodes = nodes),
            fullGraph = GraphDocument(nodes = nodes, edges = edges),
            anchorNodeId = "node-1",
            sourceContext = listOf(SourceSnippetContext(nodeId = "node-1", filePath = "src/Node1.kt")),
        )

        assertEquals(1, profile.incomingRelationCount)
        assertEquals(1, profile.outgoingRelationCount)
        assertTrue(profile.hasMethodCallEvidence)
        assertEquals(edges.size, edges.iterated, "relation evidence should be collected in one edge pass")
    }

    @Test
    fun recommendedDrilldownsStopsAfterEightUniqueCandidatesWithoutReadingFullGraphNodes() {
        val graphNodes = CountingList((1..20).map(::node))
        val fullGraphNodes = CountingList((1..20).map { index -> node(index + 100) })

        val profile = buildGraphEvidenceProfile(
            graph = GraphDocument(nodes = graphNodes),
            fullGraph = GraphDocument(nodes = fullGraphNodes),
            anchorNodeId = "node-1",
        )

        assertEquals((2..9).map { index -> "node-$index" }, profile.recommendedDrilldowns)
        assertTrue(graphNodes.iterated < graphNodes.size, "drilldown selection should stop after eight candidates")
        assertEquals(0, fullGraphNodes.iterated, "full graph nodes should not be scanned when graph nodes provide enough candidates")
    }

    private fun node(index: Int): GraphNode =
        GraphNode(
            id = "node-$index",
            type = NodeType.CLASS,
            title = "Node$index",
            location = "src/Node$index.kt",
        )

    private fun edge(
        index: Int,
        from: String,
        to: String,
        type: EdgeType,
    ): GraphEdge =
        GraphEdge(
            id = "edge-$index",
            type = type,
            fromNodeId = from,
            toNodeId = to,
            metadata = mapOf("jvm.relation.kind" to type.name),
        )

    private class CountingList<T>(
        private val values: List<T>,
    ) : AbstractList<T>() {
        var iterated: Int = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): T = values[index]

        override fun iterator(): Iterator<T> {
            val delegate = values.iterator()
            return object : Iterator<T> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): T {
                    iterated += 1
                    return delegate.next()
                }
            }
        }
    }
}
