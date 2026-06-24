package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationRole
import com.charmnight.linkgraph.model.GraphEdge

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
