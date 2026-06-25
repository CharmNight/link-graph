package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureEdge
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind

/**
 * 类图范围解析器（P2-1 真正的架构分解）。
 *
 * 从 ClassDiagramProjector 抽出的独立 class，负责确定类图中应该包含哪些类：
 * - 解析默认锚点（关系最密集的类）
 * - 围绕锚点按关系优先级圈定邻居（限制 neighborhoodLimit）
 * - 显式 scope 模式下补充外部一跳类
 * - 统计候选类型数（用于判断截断）
 *
 * 与 ClassDiagramProjector 的区别：
 * - ScopeResolver 只负责"圈定哪些类"，不负责图文档构建、视口裁剪、展示元数据
 * - ClassDiagramProjector.project() 负责编排：scope → build → clip → assemble
 */
internal class ClassDiagramScopeResolver {
    private val classLikeKinds = setOf(
        ArchitectureNodeKind.CLASS,
        ArchitectureNodeKind.INTERFACE,
        ArchitectureNodeKind.ENUM,
        ArchitectureNodeKind.ANNOTATION,
        ArchitectureNodeKind.RECORD,
        ArchitectureNodeKind.OBJECT,
    )

    /**
     * 解析类图的默认锚点：关系最密集、角色最靠前的类。
     */
    fun defaultAnchorClassId(index: ArchitectureGraphIndex): String? {
        val relationScoreByNodeId = index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in classLikeKinds }
            .associate { node -> node.id to classRelationScore(index, node.id) }
        return index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in classLikeKinds }
            .sortedWith(
                compareBy(
                    { node -> relationScoreByNodeId.getValue(node.id) == 0 },
                    { node -> -relationScoreByNodeId.getValue(node.id) },
                    { node -> classAnchorPriority(node.title) },
                    { node -> node.qualifiedName },
                    { node -> node.id },
                ),
            )
            .firstOrNull()
            ?.id
    }

    /**
     * 围绕锚点类按关系优先级圈定邻居，超过 [neighborhoodLimit] 时按优先级截断。
     */
    fun classNeighborhoodIds(
        index: ArchitectureGraphIndex,
        anchorClassId: String?,
        neighborhoodLimit: Int,
    ): Set<String> {
        val anchorId = anchorClassId ?: return emptySet()
        val limit = neighborhoodLimit.coerceAtLeast(1)
        val selected = linkedSetOf(anchorId)
        val edgeComparator = compareBy<ArchitectureEdge>(
            { edge -> ClassDiagramRelationPolicy.priority(edge) },
            { edge -> edge.id },
        )
        (index.incoming(anchorId) + index.outgoing(anchorId))
            .asSequence()
            .filter { edge ->
                ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                    index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                    index.node(edge.toNodeId)?.kind in classLikeKinds
            }
            .sortedWith(edgeComparator)
            .forEach { edge ->
                for (candidateNodeId in listOf(edge.fromNodeId, edge.toNodeId)) {
                    if (selected.size >= limit) return@forEach
                    selected += candidateNodeId
                }
            }
        return selected
    }

    /**
     * 统计锚点邻居扩展如果不截断时可达的全部候选类型数。
     */
    fun classNeighborhoodCandidateTypeCount(
        index: ArchitectureGraphIndex,
        anchorClassId: String?,
    ): Int {
        val anchorId = anchorClassId ?: return 0
        val candidateIds = linkedSetOf(anchorId)
        (index.incoming(anchorId) + index.outgoing(anchorId))
            .asSequence()
            .filter { edge ->
                ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                    index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                    index.node(edge.toNodeId)?.kind in classLikeKinds
            }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .forEach(candidateIds::add)
        return candidateIds.size
    }

    /**
     * 显式 scope 模式下补充外部/JDK/库的一跳类，让类图能展示依赖的外部类型。
     */
    fun explicitScopeClassIdsWithExternalOneHop(
        index: ArchitectureGraphIndex,
        explicitClassIds: Set<String>,
    ): Set<String> {
        val selected = linkedSetOf<String>()
        selected += explicitClassIds
        explicitClassIds.forEach { classId ->
            (index.incoming(classId) + index.outgoing(classId))
                .filter { edge ->
                    ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                        index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                        index.node(edge.toNodeId)?.kind in classLikeKinds
                }
                .flatMap { edge -> listOf(edge.fromNodeId, edge.toNodeId) }
                .forEach(selected::add)
        }
        return selected
    }

    private fun classRelationScore(
        index: ArchitectureGraphIndex,
        classNodeId: String,
    ): Int =
        (index.incoming(classNodeId) + index.outgoing(classNodeId)).count { edge ->
            ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                index.node(edge.toNodeId)?.kind in classLikeKinds
        }
}
