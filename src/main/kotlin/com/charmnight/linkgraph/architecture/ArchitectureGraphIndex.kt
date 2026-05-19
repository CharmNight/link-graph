package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget

class ArchitectureGraphIndex(
    val symbolIndex: JvmSymbolIndex,
    val relationIndex: JvmRelationIndex,
    val graph: ArchitectureGraph,
) {
    private val architectureNodesById = graph.nodes.associateBy(ArchitectureNode::id)
    private val architectureEdgesById = graph.edges.associateBy(ArchitectureEdge::id)
    private val architectureOutgoingByNodeId = graph.edges.groupBy(ArchitectureEdge::fromNodeId)
    private val architectureIncomingByNodeId = graph.edges.groupBy(ArchitectureEdge::toNodeId)

    fun node(nodeId: String): ArchitectureNode? = architectureNodesById[nodeId]

    fun edge(edgeId: String): ArchitectureEdge? = architectureEdgesById[edgeId]

    fun outgoing(nodeId: String): List<ArchitectureEdge> = architectureOutgoingByNodeId[nodeId].orEmpty()

    fun incoming(nodeId: String): List<ArchitectureEdge> = architectureIncomingByNodeId[nodeId].orEmpty()

    fun findClass(qualifiedName: String): JvmClassSymbol? = symbolIndex.findClass(qualifiedName)

    fun findMethod(signature: String): JvmMethodSymbol? = symbolIndex.findMethod(signature)

    fun findField(qualifiedName: String) = symbolIndex.findField(qualifiedName)

    fun findSymbol(symbolId: String): JvmSymbol? = symbolIndex.findSymbol(symbolId)

    fun classRelations(classSymbolId: String): List<JvmRelation> =
        relationIndex.outgoing(classSymbolId) + relationIndex.incoming(classSymbolId)

    fun upstreamOneHop(nodeId: String): List<ArchitectureNode> =
        incoming(nodeId).mapNotNull { edge -> node(edge.fromNodeId) }.distinctBy(ArchitectureNode::id)

    fun downstreamOneHop(nodeId: String): List<ArchitectureNode> =
        outgoing(nodeId).mapNotNull { edge -> node(edge.toNodeId) }.distinctBy(ArchitectureNode::id)

    fun classesInScope(scopeNodeId: String): List<JvmClassSymbol> {
        val node = node(scopeNodeId) ?: return emptyList()
        val memberIds = node.memberClassIds.takeIf(Set<String>::isNotEmpty)
            ?: if (node.kind in setOf(
                    ArchitectureNodeKind.CLASS,
                    ArchitectureNodeKind.INTERFACE,
                    ArchitectureNodeKind.ENUM,
                    ArchitectureNodeKind.ANNOTATION,
                    ArchitectureNodeKind.RECORD,
                    ArchitectureNodeKind.OBJECT,
                )
            ) {
                setOf(node.id)
            } else {
                emptySet()
            }
        return memberIds.mapNotNull { symbolId -> symbolIndex.findSymbol(symbolId) as? JvmClassSymbol }
            .sortedBy(JvmClassSymbol::qualifiedName)
    }

    companion object {
        fun from(
            symbolIndex: JvmSymbolIndex,
            relationIndex: JvmRelationIndex,
            builder: ArchitectureGraphBuilder = ArchitectureGraphBuilder(),
            budget: JvmResolutionBudget? = null,
        ): ArchitectureGraphIndex {
            return ArchitectureGraphIndex(
                symbolIndex = symbolIndex,
                relationIndex = relationIndex,
                graph = builder.build(symbolIndex, relationIndex, budget),
            )
        }
    }
}
