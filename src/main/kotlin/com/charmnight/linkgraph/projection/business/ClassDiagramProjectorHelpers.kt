package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationRole
import com.charmnight.linkgraph.model.GraphEdge

/**
 * 类图关系排序键：综合优先级、关系类型、标签、端点 ID 与边 ID 形成稳定排序。
 *
 * 抽到 top-level（原为 ClassDiagramProjector.companion 内 private data class），
 * 让 ClassDiagramProjectorHelpers.kt 中的 [classDiagramRelationSortKey] 也能引用。
 */
internal data class ClassDiagramRelationSortKey(
    val priority: Int,
    val kind: String,
    val label: String,
    val fromNodeId: String,
    val toNodeId: String,
    val id: String,
) : Comparable<ClassDiagramRelationSortKey> {
    override fun compareTo(other: ClassDiagramRelationSortKey): Int =
        compareValuesBy(
            this,
            other,
            ClassDiagramRelationSortKey::priority,
            ClassDiagramRelationSortKey::kind,
            ClassDiagramRelationSortKey::label,
            ClassDiagramRelationSortKey::fromNodeId,
            ClassDiagramRelationSortKey::toNodeId,
            ClassDiagramRelationSortKey::id,
        )
}

/**
 * ClassDiagramProjector 的纯 helper 函数（P2-1 拆分）。
 *
 * 这些 GraphEdge 扩展函数无状态、纯 metadata 读取 / 派生，与 ClassDiagramProjector
 * 的 anchor 选择 / neighborhood 收集 / presentation metadata 写入主流程解耦后便于复用。
 */

/** 返回类图关系类型字符串，依次回退到 role、uml kind、jvm kind、edge type。 */
internal fun GraphEdge.classDiagramRelationKind(): String =
    metadata[ClassDiagramRelationExtractor.ROLE_KEY]
        ?: metadata["uml.relation.kind"]
        ?: metadata["jvm.relation.kind"]
        ?: type.name

/** 解析边在 JVM 关系抽取阶段记录的角色枚举。 */
internal fun GraphEdge.classDiagramRelationRole(): ClassDiagramRelationRole? =
    metadata[ClassDiagramRelationExtractor.ROLE_KEY]
        ?.let { raw -> ClassDiagramRelationRole.entries.firstOrNull { role -> role.name == raw } }

/** 推导边对应的 UML 关系类型（泛化、实现、关联、依赖等），用于展示与聚合。 */
internal fun GraphEdge.classDiagramUmlRelationKind(): String =
    metadata["uml.relation.kind"]
        ?: when (classDiagramRelationRole()) {
            ClassDiagramRelationRole.EXTENDS -> UmlClassRelationKind.GENERALIZATION.name
            ClassDiagramRelationRole.IMPLEMENTS -> UmlClassRelationKind.REALIZATION.name
            ClassDiagramRelationRole.FIELD,
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER,
            -> UmlClassRelationKind.ASSOCIATION.name
            ClassDiagramRelationRole.METHOD_CALL,
            ClassDiagramRelationRole.METHOD_PARAMETER,
            ClassDiagramRelationRole.METHOD_RETURN,
            ClassDiagramRelationRole.THROWS,
            ClassDiagramRelationRole.LOCAL_TYPE,
            -> UmlClassRelationKind.DEPENDENCY.name
            null -> metadata["jvm.relation.kind"] ?: type.name
        }

/** 计算关系展示权重；优先用 metadata.weight，其次 role.baseWeight，最后按 kind 兜底。 */
internal fun GraphEdge.relationWeight(): Int =
    metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
        ?.toIntOrNull()
        ?: classDiagramRelationRole()?.baseWeight
        ?: when (classDiagramRelationKind()) {
            "GENERALIZATION",
            "EXTENDS",
            "REALIZATION",
            "IMPLEMENTS",
            -> 100
            "COMPOSITION",
            "AGGREGATION",
            "ASSOCIATION",
            "FIELD",
            "CONSTRUCTOR_PARAMETER",
            -> 90
            "METHOD_CALL" -> 72
            "METHOD_RETURN",
            "METHOD_PARAMETER",
            -> 58
            "DEPENDENCY",
            "USES_TYPE",
            "INJECTS",
            -> 48
            "THROWS",
            "LOCAL_TYPE",
            -> 20
            else -> 40
        }

/** 返回关系标签文本，依次回退到抽取标签、UML 标签、原始 label、关系类型名。 */
internal fun GraphEdge.classDiagramRelationLabel(): String =
    metadata[ClassDiagramRelationExtractor.LABEL_KEY]?.trim()?.takeIf(String::isNotBlank)
        ?: metadata["uml.relation.label"]?.trim()?.takeIf(String::isNotBlank)
        ?: label?.trim()?.takeIf(String::isNotBlank)
        ?: classDiagramRelationKind()

/** 返回类图边上对外展示的文本，优先使用聚合标签或抽取阶段标签。 */
internal fun GraphEdge.classDiagramDisplayLabel(): String {
    metadata["uml.relation.aggregate.label"]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    metadata[ClassDiagramRelationExtractor.LABEL_KEY]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    return classDiagramRelationLabel()
}

/** 根据源/终点节点 ID 生成稳定的聚合边 ID（"uml:relation:from->to"）。 */
internal fun aggregateRelationId(
    sourceNodeId: String,
    targetNodeId: String,
): String = "uml:relation:${sourceNodeId}->${targetNodeId}"

/** 汇总聚合组中所有边的展示标签并去重，按关系优先级排序。 */
internal fun aggregateRelationLabels(edges: List<GraphEdge>): List<String> {
    val labelSourceEdges = edges
        .filter { edge -> edge.metadata[ClassDiagramRelationExtractor.ROLE_KEY] != null }
        .takeIf(List<GraphEdge>::isNotEmpty)
        ?: edges
    return labelSourceEdges
        .sortedBy { edge -> edge.classDiagramRelationSortKey() }
        .map { edge -> edge.classDiagramDisplayLabel() }
        .filter(String::isNotBlank)
        .distinct()
}

/** 取聚合组中优先级最高的一条边的展示标签作为主标签。 */
internal fun aggregatePrimaryRelationLabel(edges: List<GraphEdge>): String =
    aggregateRelationLabels(edges).firstOrNull()
        ?: edges.firstOrNull()?.classDiagramDisplayLabel().orEmpty()

/** 取聚合组中除主标签外的次级标签列表。 */
internal fun aggregateSecondaryRelationLabels(edges: List<GraphEdge>): List<String> =
    aggregateRelationLabels(edges).drop(1)

/** 生成聚合后的展示标签：主标签 + 被隐藏的次级标签数量提示（"+N"）。 */
internal fun aggregateRelationLabel(edges: List<GraphEdge>): String {
    val primaryLabel = aggregatePrimaryRelationLabel(edges)
    val hiddenLabelCount = aggregateSecondaryRelationLabels(edges).size
    if (hiddenLabelCount <= 0) {
        return primaryLabel
    }
    return "$primaryLabel +$hiddenLabelCount"
}

/**
 * 计算类图边的展示优先级（数值越小越优先）。
 *
 * - 有 weight metadata：取负值（权重越大优先级越高）
 * - 否则按 UML 关系类型：GENERALIZATION(0) / REALIZATION(1) / COMPOSITION(2) /
 *   AGGREGATION(3) / ASSOCIATION(4) / DEPENDENCY(5) / 其他(6)
 */
internal fun classDiagramEdgePriority(edge: GraphEdge): Int =
    edge.metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
        ?.toIntOrNull()
        ?.let { weight -> -weight }
        ?: when (edge.metadata["uml.relation.kind"]) {
            UmlClassRelationKind.GENERALIZATION.name -> 0
            UmlClassRelationKind.REALIZATION.name -> 1
            UmlClassRelationKind.COMPOSITION.name -> 2
            UmlClassRelationKind.AGGREGATION.name -> 3
            UmlClassRelationKind.ASSOCIATION.name -> 4
            UmlClassRelationKind.DEPENDENCY.name -> 5
            else -> 6
        }

/** 生成边的排序键（priority + kind + label + from/to + id），统一类图中边的展示顺序。 */
internal fun GraphEdge.classDiagramRelationSortKey(): ClassDiagramRelationSortKey =
    ClassDiagramRelationSortKey(
        priority = classDiagramEdgePriority(this),
        kind = classDiagramRelationKind(),
        label = label.orEmpty(),
        fromNodeId = fromNodeId,
        toNodeId = toNodeId,
        id = id,
    )

/** 根据类名后缀给锚点候选类打分，业务入口类（Action/Controller/Service）优先。 */
internal fun classAnchorPriority(title: String): Int {
    val lower = title.lowercase()
    return when {
        lower.endsWith("action") -> 0
        lower.endsWith("controller") -> 1
        lower.endsWith("service") -> 2
        lower.endsWith("workflow") -> 3
        lower.endsWith("projector") -> 4
        else -> 5
    }
}
