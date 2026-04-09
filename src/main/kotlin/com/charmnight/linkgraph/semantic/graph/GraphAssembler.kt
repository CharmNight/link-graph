package com.charmnight.linkgraph.semantic.graph

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MergeUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import java.util.ArrayDeque

/**
 * 把语义分析结果装配为前端可消费的图结构。
 * 会根据展示模式选择不同的节点可见性规则和边映射规则。
 */
class GraphAssembler {
    /** 按展示模式把语义分析结果转换成图文档。 */
    fun assemble(
        analysisResult: SemanticAnalysisResult,
        displayMode: AnalysisDisplayMode,
    ): GraphDocument {
        /** 装配阶段共享的索引上下文。 */
        val context = GraphAssemblyContext(analysisResult)
        return when (displayMode) {
            AnalysisDisplayMode.FACT_GRAPH -> assembleFactGraph(context)
            AnalysisDisplayMode.FLOWCHART -> assembleFlowchartGraph(context)
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> assembleResourceRelationGraph(context)
        }
    }

    /** 构造事实图，保留方法、资源和流程单元之间的静态关系。 */
    private fun assembleFactGraph(context: GraphAssemblyContext): GraphDocument {
        /** 事实图中需要保留的单元 ID。 */
        val includedUnitIds = context.units
            .filter { unit -> unit !is TerminalUnit && unit !is MergeUnit }
            .mapTo(linkedSetOf()) { unit -> unit.id }
        return buildGraph(
            context = context,
            includedUnitIds = includedUnitIds,
            edgeSpecs = context.relations
                .filter { relation -> relation.fromUnitId in includedUnitIds && relation.toUnitId in includedUnitIds }
                .map(::EdgeSpec),
            displayMode = AnalysisDisplayMode.FACT_GRAPH,
            anchorUnitId = context.anchorUnitId,
        )
    }

    /** 构造流程图，只保留从锚点可达的控制流单元。 */
    private fun assembleFlowchartGraph(context: GraphAssemblyContext): GraphDocument {
        /** 当前视图的锚点单元 ID。 */
        val anchorUnitId = context.anchorUnitId
        /** 从锚点出发可到达的单元集合。 */
        val reachableUnitIds = linkedSetOf<String>()
        /** 控制流遍历使用的队列。 */
        val traversalQueue = ArrayDeque<String>()

        anchorUnitId?.let {
            reachableUnitIds += it
            traversalQueue += it
        }

        while (traversalQueue.isNotEmpty()) {
            /** 当前正在展开的单元 ID。 */
            val currentUnitId = traversalQueue.removeFirst()
            context.outgoingRelations[currentUnitId]
                .orEmpty()
                .filter { relation -> relation.kind == SemanticRelationKind.CONTROL_FLOW }
                .forEach { relation ->
                    /** 当前控制流边指向的单元。 */
                    val targetUnit = context.unitById[relation.toUnitId] ?: return@forEach
                    if (targetUnit is ResourceUnit) {
                        return@forEach
                    }
                    if (targetUnit is MethodLikeUnit && targetUnit.id != anchorUnitId) {
                        return@forEach
                    }
                    if (reachableUnitIds.add(targetUnit.id)) {
                        traversalQueue += targetUnit.id
                    }
                }
        }

        if (reachableUnitIds.isEmpty()) {
            anchorUnitId?.let { reachableUnitIds += it }
        }

        /** 经过可见性裁剪后实际展示的单元集合。 */
        val visibleUnitIds = reachableUnitIds
            .filterTo(linkedSetOf()) { unitId ->
                isVisibleInFlowchart(
                    unit = context.unitById[unitId],
                    anchorUnitId = anchorUnitId,
                )
            }
        return buildGraph(
            context = context,
            includedUnitIds = visibleUnitIds,
            edgeSpecs = context.relations
                .filter { relation ->
                    relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                        relation.fromUnitId in visibleUnitIds &&
                        relation.toUnitId in visibleUnitIds
                }
                .map(::EdgeSpec),
            displayMode = AnalysisDisplayMode.FLOWCHART,
            anchorUnitId = anchorUnitId,
        )
    }

    /** 判断某个语义单元在流程图模式下是否可见。 */
    private fun isVisibleInFlowchart(
        unit: SemanticUnit?,
        anchorUnitId: String?,
    ): Boolean {
        return when (unit) {
            null -> false
            is ResourceUnit -> false
            is MethodLikeUnit -> unit.id == anchorUnitId
            else -> true
        }
    }

    /** 构造资源关系视图，只保留方法与资源相关的关系。 */
    private fun assembleResourceRelationGraph(context: GraphAssemblyContext): GraphDocument {
        /** 经过资源视角归一化后的边规格列表。 */
        val normalizedRelationSpecs = context.relations
            .asSequence()
            .filter { relation ->
                relation.kind == SemanticRelationKind.DOCUMENTS ||
                    relation.kind == SemanticRelationKind.BINDS_TO ||
                    relation.kind == SemanticRelationKind.REFERENCES
            }
            .mapNotNull { relation -> normalizeResourceRelation(context, relation) }
            .toList()

        /** 资源关系图中最终纳入的单元 ID。 */
        val includedUnitIds = linkedSetOf<String>()
        normalizedRelationSpecs.forEach { spec ->
            includedUnitIds += spec.fromUnitId
            includedUnitIds += spec.toUnitId
        }

        if (includedUnitIds.isEmpty()) {
            context.anchorUnitId?.let { includedUnitIds += it }
            context.units.filterIsInstance<ResourceUnit>().forEach { unit -> includedUnitIds += unit.id }
        }

        return buildGraph(
            context = context,
            includedUnitIds = includedUnitIds,
            edgeSpecs = normalizedRelationSpecs,
            displayMode = AnalysisDisplayMode.RESOURCE_RELATION_VIEW,
            anchorUnitId = context.anchorUnitId,
        )
    }

    /** 把资源关系统一成“方法/资源”视角，避免边方向因源关系不同而分裂。 */
    private fun normalizeResourceRelation(
        context: GraphAssemblyContext,
        relation: SemanticRelation,
    ): EdgeSpec? {
        /** 关系起点对应的语义单元。 */
        val fromUnit = context.unitById[relation.fromUnitId] ?: return null
        /** 关系终点对应的语义单元。 */
        val toUnit = context.unitById[relation.toUnitId] ?: return null
        /** 关系中涉及的方法单元。 */
        val methodUnit = listOf(fromUnit, toUnit).filterIsInstance<MethodLikeUnit>().firstOrNull()
        /** 关系中涉及的资源单元。 */
        val resourceUnit = listOf(fromUnit, toUnit).filterIsInstance<ResourceUnit>().firstOrNull()
        return if (methodUnit != null && resourceUnit != null) {
            EdgeSpec(
                relation = relation,
                fromUnitId = methodUnit.id,
                toUnitId = resourceUnit.id,
            )
        } else {
            EdgeSpec(relation)
        }
    }

    /** 根据节点集合和边规格构造最终图文档。 */
    private fun buildGraph(
        context: GraphAssemblyContext,
        includedUnitIds: Set<String>,
        edgeSpecs: List<EdgeSpec>,
        displayMode: AnalysisDisplayMode,
        anchorUnitId: String?,
    ): GraphDocument {
        /** 最终输出的节点列表。 */
        val nodes = context.units
            .asSequence()
            .filter { unit -> unit.id in includedUnitIds }
            .map { unit ->
                toGraphNode(
                    context = context,
                    unit = unit,
                    sourceMapping = context.mappingByUnitId[unit.id],
                    displayMode = displayMode,
                    anchorUnitId = anchorUnitId,
                )
            }
            .let { sequence ->
                if (displayMode == AnalysisDisplayMode.FLOWCHART) {
                    sequence
                } else {
                    sequence.sortedBy { node -> node.id }
                }
            }
            .toList()
        /** 最终输出的边列表。 */
        val edges = edgeSpecs
            .asSequence()
            .filter { spec -> spec.fromUnitId in includedUnitIds && spec.toUnitId in includedUnitIds }
            .mapNotNull { spec ->
                toGraphEdge(
                    relation = spec.relation,
                    fromUnitId = spec.fromUnitId,
                    toUnitId = spec.toUnitId,
                    unitsById = context.unitById,
                    displayMode = displayMode,
                )
            }
            .let { sequence ->
                if (displayMode == AnalysisDisplayMode.FLOWCHART) {
                    sequence
                } else {
                    sequence.sortedBy { edge -> edge.id }
                }
            }
            .toList()
        return GraphDocument(nodes = nodes, edges = edges)
    }

    /** 把单个语义单元转换成图节点。 */
    private fun toGraphNode(
        context: GraphAssemblyContext,
        unit: SemanticUnit,
        sourceMapping: SourceMapping?,
        displayMode: AnalysisDisplayMode,
        anchorUnitId: String?,
    ): GraphNode {
        /** 单元对应的源码定位字符串。 */
        val location = sourceMapping?.sourceRange?.startLine?.let { startLine ->
            "${sourceMapping.sourcePath}:$startLine"
        } ?: sourceMapping?.sourcePath
        /** 语义单元转换后的基础节点。 */
        val node = when (unit) {
            is MethodLikeUnit -> GraphNode(
                id = unit.id,
                type = NodeType.METHOD,
                title = unit.title,
                location = location,
                signature = unit.signature,
            )

            is FlowScopeUnit -> GraphNode(
                id = unit.id,
                type = NodeType.FLOW_SCOPE,
                title = unit.title,
                location = location,
                metadata = mapOf("flow.kind" to unit.scopeKind),
            )

            is FlowActionUnit -> GraphNode(
                id = unit.id,
                type = NodeType.FLOW_ACTION,
                title = unit.title,
                location = location,
                metadata = mapOf("flow.kind" to unit.actionKind),
            )

            is InvocationUnit -> GraphNode(
                id = unit.id,
                type = NodeType.FLOW_ACTION,
                title = unit.title,
                location = location,
                signature = unit.targetSignature,
                metadata = mapOf("flow.kind" to "INVOCATION"),
            )

            is ResourceUnit -> GraphNode(
                id = unit.id,
                type = unit.resourceKind.toNodeType(),
                title = unit.title,
                location = location,
                sourceKind = unit.resourceKind,
                metadata = unit.metadata,
            )

            is TerminalUnit -> GraphNode(
                id = unit.id,
                type = NodeType.TERMINAL,
                title = unit.title,
                location = location,
                metadata = mapOf("terminal.kind" to unit.terminalKind),
            )

            is MergeUnit -> GraphNode(
                id = unit.id,
                type = NodeType.MERGE,
                title = unit.title,
                location = location,
            )
        }
        return node.withProjectionMetadata(
            context = context,
            displayMode = displayMode,
            unit = unit,
            sourceMapping = sourceMapping,
            anchorUnitId = anchorUnitId,
        )
    }

    /** 为图节点补充不同视图模式下的投影元数据。 */
    private fun GraphNode.withProjectionMetadata(
        context: GraphAssemblyContext,
        displayMode: AnalysisDisplayMode,
        unit: SemanticUnit,
        sourceMapping: SourceMapping?,
        anchorUnitId: String?,
    ): GraphNode {
        /** 当前节点新增的投影元数据。 */
        val projectionMetadata = mutableMapOf<String, String>()
        projectionMetadata["linkGraph.view.mode"] = displayMode.name
        sourceMapping?.let { mapping ->
            projectionMetadata["source.filePath"] = mapping.sourcePath
            projectionMetadata["source.startOffset"] = mapping.sourceRange.startOffset.toString()
            projectionMetadata["source.endOffset"] = mapping.sourceRange.endOffset.toString()
            projectionMetadata["source.startLine"] = mapping.sourceRange.startLine.toString()
            projectionMetadata["source.endLine"] = mapping.sourceRange.endLine.toString()
        }
        /** 当前单元所属的方法单元，流程视图会使用它标记 owner。 */
        val ownerMethod = context.ownerMethodByUnitId[unit.id] ?: (unit as? MethodLikeUnit)
        if (unit !is ResourceUnit) {
            ownerMethod?.signature?.let { signature ->
                projectionMetadata["flow.ownerMethod"] = signature
            }
            context.anchorMethodSignature?.let { signature ->
                projectionMetadata["flow.anchorMethod"] = signature
            }
        }
        when (displayMode) {
            AnalysisDisplayMode.FLOWCHART -> {
                projectionMetadata += when (unit) {
                    is MethodLikeUnit -> mapOf(
                        "flowchart.kind" to if (unit.id == anchorUnitId) "ENTRY" else "SUBROUTINE",
                    )
                    is FlowScopeUnit -> mapOf(
                        "flowchart.kind" to if (unit.scopeKind in setOf("IF", "SWITCH")) "DECISION" else "SCOPE",
                    )
                    is FlowActionUnit -> mapOf(
                        "flowchart.kind" to when {
                            unit.actionKind == "RETURN" -> "TERMINAL"
                            unit.actionKind == "THROW" -> "TERMINAL"
                            unit.title.trim().startsWith("return ", ignoreCase = true) -> "TERMINAL"
                            unit.title.trim().startsWith("throw ", ignoreCase = true) -> "TERMINAL"
                            else -> "PROCESS"
                        },
                    )
                    is InvocationUnit -> mapOf("flowchart.kind" to "SUBROUTINE")
                    is TerminalUnit -> mapOf("flowchart.kind" to "TERMINAL")
                    is MergeUnit -> mapOf("flowchart.kind" to "MERGE")
                    is ResourceUnit -> mapOf("flowchart.kind" to "RESOURCE")
                }
            }

            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> {
                projectionMetadata["resource.lane"] = resourceLaneFor(type)
            }

            AnalysisDisplayMode.FACT_GRAPH -> {
                projectionMetadata["fact.kind"] = type.name
            }
        }
        return copy(metadata = metadata + projectionMetadata)
    }

    /** 根据节点类型决定其在资源关系视图中的分组泳道。 */
    private fun resourceLaneFor(type: NodeType): String {
        return when (type) {
            NodeType.METHOD -> "CODE"
            NodeType.CLASS,
            NodeType.HTTP_ENDPOINT,
            NodeType.FEIGN_CLIENT,
            NodeType.DUBBO_SERVICE,
            NodeType.MQ_TOPIC,
            NodeType.MQ_CONSUMER -> "INTEGRATION"
            NodeType.SQL -> "DATA"
            NodeType.CONFIG_ITEM,
            NodeType.XML_RESOURCE -> "CONFIG"
            NodeType.DOC_PAGE -> "DOC"
            else -> "AUXILIARY"
        }
    }

    /** 把语义关系映射成图边。 */
    private fun toGraphEdge(
        relation: SemanticRelation,
        fromUnitId: String,
        toUnitId: String,
        unitsById: Map<String, SemanticUnit>,
        displayMode: AnalysisDisplayMode,
    ): GraphEdge? {
        /** 关系映射得到的图边类型。 */
        val edgeType = relation.toEdgeType(unitsById[fromUnitId], unitsById[toUnitId]) ?: return null
        return GraphEdge(
            id = GraphEdge.stableId(edgeType, fromUnitId, toUnitId, relation.label),
            type = edgeType,
            fromNodeId = fromUnitId,
            toNodeId = toUnitId,
            label = relation.label,
            metadata = mapOf("linkGraph.view.mode" to displayMode.name),
        )
    }

    /** 图装配过程中的只读索引上下文。 */
    private data class GraphAssemblyContext(
        /** 原始语义分析结果。 */
        val result: SemanticAnalysisResult,
    ) {
        /** 全部语义单元列表。 */
        val units: List<SemanticUnit> = result.semanticUnits
        /** 按 ID 索引的语义单元映射。 */
        val unitById: Map<String, SemanticUnit> = units.associateBy { unit -> unit.id }
        /** 全部语义关系列表。 */
        val relations: List<SemanticRelation> = result.relations
        /** 按起点分组的出边索引。 */
        val outgoingRelations: Map<String, List<SemanticRelation>> = relations.groupBy { relation -> relation.fromUnitId }
        /** 目标单元到源码映射的索引。 */
        val mappingByUnitId: Map<String, SourceMapping> =
            result.sourceMappings.associateBy { mapping -> mapping.targetUnitId }
        /** 所有方法类单元的 ID 索引。 */
        private val methodUnitById: Map<String, MethodLikeUnit> = units
            .filterIsInstance<MethodLikeUnit>()
            .associateBy { unit -> unit.id }
        /** 每个单元所属方法的索引。 */
        val ownerMethodByUnitId: Map<String, MethodLikeUnit> = relations
            .asSequence()
            .filter { relation -> relation.kind == SemanticRelationKind.CONTAINS }
            .mapNotNull { relation ->
                methodUnitById[relation.fromUnitId]?.let { ownerMethod ->
                    relation.toUnitId to ownerMethod
                }
            }
            .toMap()
        /** 当前视图的锚点单元 ID。 */
        val anchorUnitId: String? = result.anchors.firstOrNull()?.targetUnitId
            ?: units.filterIsInstance<MethodLikeUnit>().firstOrNull()?.id
            ?: units.firstOrNull()?.id
        /** 锚点所属方法签名，用于流程投影元数据。 */
        val anchorMethodSignature: String? = when (val anchorUnit = anchorUnitId?.let(unitById::get)) {
            is MethodLikeUnit -> anchorUnit.signature
            null -> null
            else -> ownerMethodByUnitId[anchorUnit.id]?.signature
        }
    }

    /** 图边构建前的关系规格。 */
    private data class EdgeSpec(
        /** 原始语义关系。 */
        val relation: SemanticRelation,
        /** 图边起点单元 ID。 */
        val fromUnitId: String = relation.fromUnitId,
        /** 图边终点单元 ID。 */
        val toUnitId: String = relation.toUnitId,
    )
}

/** 把资源单元类型名映射为图节点类型。 */
private fun String.toNodeType(): NodeType {
    return when (this) {
        "MYBATIS_STATEMENT",
        "SQL_FILE",
        "SQL" -> NodeType.SQL
        "CONFIG_ITEM" -> NodeType.CONFIG_ITEM
        "XML_RESOURCE" -> NodeType.XML_RESOURCE
        "MARKDOWN_PAGE",
        "DOC_PAGE" -> NodeType.DOC_PAGE
        "CLASS" -> NodeType.CLASS
        "HTTP_ENDPOINT" -> NodeType.HTTP_ENDPOINT
        "FEIGN_CLIENT" -> NodeType.FEIGN_CLIENT
        "DUBBO_SERVICE" -> NodeType.DUBBO_SERVICE
        "MQ_TOPIC" -> NodeType.MQ_TOPIC
        "MQ_CONSUMER" -> NodeType.MQ_CONSUMER
        else -> NodeType.UNCERTAIN_LINK
    }
}

/** 把语义关系映射为图边类型。 */
private fun SemanticRelation.toEdgeType(
    fromUnit: SemanticUnit?,
    toUnit: SemanticUnit?,
): EdgeType? {
    return when (kind) {
        SemanticRelationKind.CONTAINS -> EdgeType.CONTAINS_FLOW
        SemanticRelationKind.CONTROL_FLOW -> EdgeType.CONTROL_FLOW
        SemanticRelationKind.INVOKES -> EdgeType.CALL
        SemanticRelationKind.IMPLEMENTS -> EdgeType.IMPLEMENTS
        SemanticRelationKind.DOCUMENTS -> EdgeType.LINKS_DOC
        SemanticRelationKind.BINDS_TO -> when ((fromUnit as? ResourceUnit)?.resourceKind ?: label.orEmpty()) {
            "MYBATIS_STATEMENT" -> EdgeType.MAPS_TO_SQL
            "SQL_FILE" -> EdgeType.MAPS_TO_SQL
            else -> EdgeType.BINDS_CONFIG
        }

        SemanticRelationKind.REFERENCES -> when (label) {
            EdgeType.ROUTES_TO.name -> EdgeType.ROUTES_TO
            EdgeType.PUBLISHES_TO.name -> EdgeType.PUBLISHES_TO
            EdgeType.CONSUMES_FROM.name -> EdgeType.CONSUMES_FROM
            EdgeType.USES_PROXY.name -> EdgeType.USES_PROXY
            EdgeType.GENERATES.name -> EdgeType.GENERATES
            else -> EdgeType.REFLECTS_TO
        }
    }
}
