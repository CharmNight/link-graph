package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

enum class ArchitectureNodeKind {
    MODULE,
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
    OBJECT,
    SERVICE,
    RESOURCE,
    LAYER,
    LIBRARY,
    JDK,
    COMPONENT,
}

enum class ArchitectureAggregationLevel {
    OVERVIEW,
    PACKAGE,
}

data class ArchitectureNode(
    val id: String,
    val kind: ArchitectureNodeKind,
    val qualifiedName: String,
    val title: String,
    val moduleName: String? = null,
    val packageName: String? = null,
    val classKind: JvmClassKind? = null,
    val stereotype: JvmStereotype? = null,
    val resourceKind: JvmResourceKind? = null,
    val memberClassIds: Set<String> = emptySet(),
    val memberResourceIds: Set<String> = emptySet(),
    val source: JvmSourceRef? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ArchitectureEdge(
    val id: String,
    val kind: JvmRelationKind,
    val fromNodeId: String,
    val toNodeId: String,
    val confidence: JvmRelationConfidence,
    val count: Int = 1,
    val sourceRelationIds: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
)

data class ArchitectureGraph(
    val nodes: List<ArchitectureNode> = emptyList(),
    val edges: List<ArchitectureEdge> = emptyList(),
    val rootNodeIds: List<String> = emptyList(),
    val indexVersion: String = "architecture-graph-v1",
    val truncated: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

data class ArchitectureGraphSnapshot(
    val symbolIndex: com.charmnight.linkgraph.jvm.index.JvmSymbolIndex,
    val relationIndex: com.charmnight.linkgraph.jvm.relation.JvmRelationIndex,
    val graph: ArchitectureGraph,
)
