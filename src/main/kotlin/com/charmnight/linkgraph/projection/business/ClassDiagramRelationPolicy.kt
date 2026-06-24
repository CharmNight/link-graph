package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureEdge
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationRole
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.effectiveTypeReferences
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge

/**
 * UML 类图中可呈现的关系种类。
 *
 * 每一项携带可读英文标签 [label] 与排序权重 [priority]（数字越小优先级越高），
 * 用于把 JVM 关系规范化到 UML 标准：泛化、实现、组合、聚合、关联、依赖。
 */
internal enum class UmlClassRelationKind(
    val label: String,
    val priority: Int,
) {
    /** 泛化关系（继承父类）。 */
    GENERALIZATION("extends", 0),

    /** 实现关系（实现接口）。 */
    REALIZATION("implements", 1),

    /** 组合关系（强生命周期依赖）。 */
    COMPOSITION("composition", 2),

    /** 聚合关系（弱包含）。 */
    AGGREGATION("aggregation", 3),

    /** 关联关系（持有引用）。 */
    ASSOCIATION("association", 4),

    /** 依赖关系（临时使用）。 */
    DEPENDENCY("dependency", 5),
}

/**
 * 类图关系分类策略。
 *
 * 决定一条关系是否应当出现在类图中、按何种 UML 关系呈现、
 * 以及彼此之间的优先级。同时把 UML 关系映射到通用边类型。
 */
internal object ClassDiagramRelationPolicy {
    /** 类图默认支持的 JVM 关系种类集合。 */
    private val supportedJvmKinds = setOf(
        JvmRelationKind.EXTENDS,
        JvmRelationKind.IMPLEMENTS,
        JvmRelationKind.USES_TYPE,
        JvmRelationKind.INJECTS,
    )

    /**
     * 判定架构索引中的边是否应当参与类图。
     *
     * 当边携带类图专用角色或关系种类在支持范围内时，均视为参与。
     */
    fun participatesInClassDiagram(edge: ArchitectureEdge): Boolean =
        role(edge.metadata) != null || participatesInClassDiagram(edge.kind)

    /**
     * 判定给定 JVM 关系种类是否在类图支持范围内。
     */
    fun participatesInClassDiagram(kind: JvmRelationKind): Boolean =
        kind in supportedJvmKinds

    /**
     * 判定已投影的图边是否应当参与类图。
     *
     * 通过元数据中的角色或关系种类进行判定，缺失关系种类信息时直接返回 false。
     */
    fun participatesInClassDiagram(edge: GraphEdge): Boolean {
        if (role(edge.metadata) != null) {
            return true
        }
        val relationKind = edge.metadata["jvm.relation.kind"]?.let(::jvmRelationKind) ?: return false
        return participatesInClassDiagram(relationKind)
    }

    /**
     * 根据关系种类返回其在类图中的优先级，数字越小越优先。
     *
     * 不支持的关系种类返回 [Int.MAX_VALUE]，视为最低优先级。
     */
    fun priority(kind: JvmRelationKind): Int =
        when (kind) {
            JvmRelationKind.EXTENDS -> UmlClassRelationKind.GENERALIZATION.priority
            JvmRelationKind.IMPLEMENTS -> UmlClassRelationKind.REALIZATION.priority
            JvmRelationKind.USES_TYPE -> UmlClassRelationKind.ASSOCIATION.priority
            JvmRelationKind.INJECTS -> UmlClassRelationKind.DEPENDENCY.priority
            else -> Int.MAX_VALUE
        }

    /**
     * 计算架构边的优先级。
     *
     * 当边携带显式权重元数据时使用权重的负值（权重越大优先级越高），
     * 否则按关系种类回退到默认优先级。
     */
    fun priority(edge: ArchitectureEdge): Int =
        edge.metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
            ?.toIntOrNull()
            ?.let { weight -> -weight }
            ?: priority(edge.kind)

    /**
     * 把图边归类为 UML 关系种类。
     *
     * 优先使用元数据中的角色信息进行精细分类；
     * 当角色缺失时按 JVM 关系种类推断，并在"使用类型/注入"场景下
     * 通过字段关联判定区分关联关系与依赖关系。
     *
     * @param edge 待归类的图边
     * @param index 架构索引，用于字段关联判定
     * @return 对应的 UML 关系种类；无法判定时返回空
     */
    fun classify(
        edge: GraphEdge,
        index: ArchitectureGraphIndex,
    ): UmlClassRelationKind? {
        role(edge.metadata)?.let { role ->
            return classify(role, edge.metadata)
        }
        val relationKind = edge.metadata["jvm.relation.kind"]?.let(::jvmRelationKind) ?: return null
        return when (relationKind) {
            JvmRelationKind.EXTENDS -> UmlClassRelationKind.GENERALIZATION
            JvmRelationKind.IMPLEMENTS -> UmlClassRelationKind.REALIZATION
            JvmRelationKind.USES_TYPE,
            JvmRelationKind.INJECTS,
            -> if (hasFieldAssociation(index, edge.fromNodeId, edge.toNodeId)) {
                UmlClassRelationKind.ASSOCIATION
            } else {
                UmlClassRelationKind.DEPENDENCY
            }
            else -> null
        }
    }

    /**
     * 根据 UML 关系种类与边角色选择通用边类型。
     *
     * 对于携带特殊角色的边（继承、实现、方法调用）会优先使用角色对应类型，
     * 其余情况按 UML 关系种类回退。
     */
    fun edgeTypeFor(edge: GraphEdge, kind: UmlClassRelationKind): EdgeType {
        return when (role(edge.metadata)) {
            ClassDiagramRelationRole.EXTENDS -> EdgeType.EXTENDS
            ClassDiagramRelationRole.IMPLEMENTS -> EdgeType.IMPLEMENTS
            ClassDiagramRelationRole.METHOD_CALL -> EdgeType.CALL
            else -> edgeTypeFor(kind)
        }
    }

    /**
     * 把 UML 关系种类映射到通用边类型。
     *
     * 泛化/实现使用专用边类型；其余关系在通用模型中合并为使用类型。
     */
    fun edgeTypeFor(kind: UmlClassRelationKind): EdgeType =
        when (kind) {
            UmlClassRelationKind.GENERALIZATION -> EdgeType.EXTENDS
            UmlClassRelationKind.REALIZATION -> EdgeType.IMPLEMENTS
            UmlClassRelationKind.COMPOSITION,
            UmlClassRelationKind.AGGREGATION,
            UmlClassRelationKind.ASSOCIATION,
            UmlClassRelationKind.DEPENDENCY,
            -> EdgeType.USES_TYPE
        }

    /**
     * 把字符串解析为 JVM 关系种类枚举，无法匹配时返回空。
     */
    private fun jvmRelationKind(raw: String): JvmRelationKind? =
        JvmRelationKind.entries.firstOrNull { it.name == raw }

    /**
     * 从边元数据中读取类图专用角色。
     */
    private fun role(metadata: Map<String, String>): ClassDiagramRelationRole? =
        metadata[ClassDiagramRelationExtractor.ROLE_KEY]
            ?.let { raw -> ClassDiagramRelationRole.entries.firstOrNull { role -> role.name == raw } }

    /**
     * 根据精细角色与元数据把边归类为 UML 关系种类。
     *
     * 例如：字段角色下若该字段确实持有引用则视为关联，否则视为依赖；
     * 构造参数角色下若被赋值给字段则视为关联，否则视为依赖。
     */
    private fun classify(
        role: ClassDiagramRelationRole,
        metadata: Map<String, String>,
    ): UmlClassRelationKind =
        when (role) {
            ClassDiagramRelationRole.EXTENDS -> UmlClassRelationKind.GENERALIZATION
            ClassDiagramRelationRole.IMPLEMENTS -> UmlClassRelationKind.REALIZATION
            ClassDiagramRelationRole.FIELD -> if (metadata[ClassDiagramRelationExtractor.HELD_BY_FIELD_KEY] == "true") {
                UmlClassRelationKind.ASSOCIATION
            } else {
                UmlClassRelationKind.DEPENDENCY
            }
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER -> if (metadata[ClassDiagramRelationExtractor.FIELD_ASSIGNED_KEY] == "true") {
                UmlClassRelationKind.ASSOCIATION
            } else {
                UmlClassRelationKind.DEPENDENCY
            }
            ClassDiagramRelationRole.METHOD_CALL,
            ClassDiagramRelationRole.METHOD_PARAMETER,
            ClassDiagramRelationRole.METHOD_RETURN,
            ClassDiagramRelationRole.THROWS,
            ClassDiagramRelationRole.LOCAL_TYPE,
            -> UmlClassRelationKind.DEPENDENCY
        }

    /**
     * 判定起点类是否以字段形式关联了目标类。
     *
     * 仅当起点与目标都是类符号，且起点存在有效字段引用目标类时返回 true，
     * 这是把"使用类型"区分为关联或依赖的关键依据。
     */
    private fun hasFieldAssociation(
        index: ArchitectureGraphIndex,
        fromNodeId: String,
        toNodeId: String,
    ): Boolean {
        val owner = index.findSymbol(fromNodeId) as? JvmClassSymbol ?: return false
        val target = index.findSymbol(toNodeId) as? JvmClassSymbol ?: return false
        return index.symbolIndex.fieldsByQualifiedName.values.any { field ->
            field.ownerClassName == owner.qualifiedName &&
                field.referencesClass(index, owner, target)
        }
    }

    /**
     * 判定字段是否引用了目标类。
     *
     * 通过字段的有效类型引用列表，逐个检查是否命中目标类，
     * 同时要求引用角色属于关联类字段角色集合。
     */
    private fun JvmFieldSymbol.referencesClass(
        index: ArchitectureGraphIndex,
        owner: JvmClassSymbol,
        target: JvmClassSymbol,
    ): Boolean {
        return effectiveTypeReferences().any { reference ->
            reference.role in associationFieldRoles &&
                index.symbolIndex.findClassByTypeName(reference.typeName, owner.packageName)?.id == target.id
        }
    }

    /** 视为"关联"的字段类型角色集合（直接赋值、集合元素、Map 值）。 */
    private val associationFieldRoles = setOf(
        JvmFieldTypeRole.DIRECT_VALUE,
        JvmFieldTypeRole.COLLECTION_ELEMENT,
        JvmFieldTypeRole.MAP_VALUE,
    )
}
