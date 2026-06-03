package com.charmnight.linkgraph.architecture.view

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

internal enum class UmlClassRelationKind(
    val label: String,
    val priority: Int,
) {
    GENERALIZATION("extends", 0),
    REALIZATION("implements", 1),
    COMPOSITION("composition", 2),
    AGGREGATION("aggregation", 3),
    ASSOCIATION("association", 4),
    DEPENDENCY("dependency", 5),
}

internal object ClassDiagramRelationPolicy {
    private val supportedJvmKinds = setOf(
        JvmRelationKind.EXTENDS,
        JvmRelationKind.IMPLEMENTS,
        JvmRelationKind.USES_TYPE,
        JvmRelationKind.INJECTS,
    )

    fun participatesInClassDiagram(edge: ArchitectureEdge): Boolean =
        role(edge.metadata) != null || participatesInClassDiagram(edge.kind)

    fun participatesInClassDiagram(kind: JvmRelationKind): Boolean =
        kind in supportedJvmKinds

    fun participatesInClassDiagram(edge: GraphEdge): Boolean {
        if (role(edge.metadata) != null) {
            return true
        }
        val relationKind = edge.metadata["jvm.relation.kind"]?.let(::jvmRelationKind) ?: return false
        return participatesInClassDiagram(relationKind)
    }

    fun priority(kind: JvmRelationKind): Int =
        when (kind) {
            JvmRelationKind.EXTENDS -> UmlClassRelationKind.GENERALIZATION.priority
            JvmRelationKind.IMPLEMENTS -> UmlClassRelationKind.REALIZATION.priority
            JvmRelationKind.USES_TYPE -> UmlClassRelationKind.ASSOCIATION.priority
            JvmRelationKind.INJECTS -> UmlClassRelationKind.DEPENDENCY.priority
            else -> Int.MAX_VALUE
        }

    fun priority(edge: ArchitectureEdge): Int =
        edge.metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
            ?.toIntOrNull()
            ?.let { weight -> -weight }
            ?: priority(edge.kind)

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

    fun edgeTypeFor(edge: GraphEdge, kind: UmlClassRelationKind): EdgeType {
        return when (role(edge.metadata)) {
            ClassDiagramRelationRole.EXTENDS -> EdgeType.EXTENDS
            ClassDiagramRelationRole.IMPLEMENTS -> EdgeType.IMPLEMENTS
            ClassDiagramRelationRole.METHOD_CALL -> EdgeType.CALL
            else -> edgeTypeFor(kind)
        }
    }

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

    private fun jvmRelationKind(raw: String): JvmRelationKind? =
        JvmRelationKind.entries.firstOrNull { it.name == raw }

    private fun role(metadata: Map<String, String>): ClassDiagramRelationRole? =
        metadata[ClassDiagramRelationExtractor.ROLE_KEY]
            ?.let { raw -> ClassDiagramRelationRole.entries.firstOrNull { role -> role.name == raw } }

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

    private val associationFieldRoles = setOf(
        JvmFieldTypeRole.DIRECT_VALUE,
        JvmFieldTypeRole.COLLECTION_ELEMENT,
        JvmFieldTypeRole.MAP_VALUE,
    )
}
