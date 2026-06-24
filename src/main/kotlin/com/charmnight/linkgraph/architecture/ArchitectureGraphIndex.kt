package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget

/**
 * 架构图索引，封装符号索引、关系索引与架构图，并提供节点/边/符号的快速查询。
 */
class ArchitectureGraphIndex(
    /** 底层 JVM 符号索引。 */
    val symbolIndex: JvmSymbolIndex,
    /** 底层 JVM 关系索引。 */
    val relationIndex: JvmRelationIndex,
    /** 构建出的架构图。 */
    val graph: ArchitectureGraph,
) {
    private val architectureNodesById = graph.nodes.associateBy(ArchitectureNode::id)
    private val architectureEdgesById = graph.edges.associateBy(ArchitectureEdge::id)
    private val architectureOutgoingByNodeId = graph.edges.groupBy(ArchitectureEdge::fromNodeId)
    private val architectureIncomingByNodeId = graph.edges.groupBy(ArchitectureEdge::toNodeId)

    /** 按节点 ID 查询架构节点。 */
    fun node(nodeId: String): ArchitectureNode? = architectureNodesById[nodeId]

    /** 按边 ID 查询架构边。 */
    fun edge(edgeId: String): ArchitectureEdge? = architectureEdgesById[edgeId]

    /** 返回某节点的所有出边。 */
    fun outgoing(nodeId: String): List<ArchitectureEdge> = architectureOutgoingByNodeId[nodeId].orEmpty()

    /** 返回某节点的所有入边。 */
    fun incoming(nodeId: String): List<ArchitectureEdge> = architectureIncomingByNodeId[nodeId].orEmpty()

    /** 按限定名查找类符号。 */
    fun findClass(qualifiedName: String): JvmClassSymbol? = symbolIndex.findClass(qualifiedName)

    /** 按签名查找方法符号。 */
    fun findMethod(signature: String): JvmMethodSymbol? = symbolIndex.findMethod(signature)

    /** 按限定名查找字段符号。 */
    fun findField(qualifiedName: String) = symbolIndex.findField(qualifiedName)

    /** 按符号 ID 查找任意符号。 */
    fun findSymbol(symbolId: String): JvmSymbol? = symbolIndex.findSymbol(symbolId)

    /** 返回与类相关的关系（出边和入边合并）。 */
    fun classRelations(classSymbolId: String): List<JvmRelation> =
        relationIndex.outgoing(classSymbolId) + relationIndex.incoming(classSymbolId)

    /** 返回直接上游一跳的节点列表。 */
    fun upstreamOneHop(nodeId: String): List<ArchitectureNode> =
        incoming(nodeId).mapNotNull { edge -> node(edge.fromNodeId) }.distinctBy(ArchitectureNode::id)

    /** 返回直接下游一跳的节点列表。 */
    fun downstreamOneHop(nodeId: String): List<ArchitectureNode> =
        outgoing(nodeId).mapNotNull { edge -> node(edge.toNodeId) }.distinctBy(ArchitectureNode::id)

    /** 列出某个作用域节点下包含的所有类符号（成员类或自身）。 */
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
        /** 基于符号索引和关系索引构建架构图，并组装为索引对象。 */
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
