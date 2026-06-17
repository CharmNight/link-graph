package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.application.indexed.IndexedGraphLayerKind
import com.charmnight.linkgraph.application.indexed.IndexedGraphNodeRole
import com.charmnight.linkgraph.application.indexed.indexedLayerKind
import com.charmnight.linkgraph.application.indexed.indexedNodeRole
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.model.GraphNode

enum class ArchitectureDisplayLayer(
    val laneId: String,
    val label: String,
    val role: String,
    val order: Int,
) {
    ENTRY("entry", "入口层", "ENTRY", 10),
    APPLICATION("application", "应用层", "APPLICATION", 20),
    DOMAIN("domain", "领域层", "DOMAIN", 30),
    DATA("data", "基础设施", "DATA", 40),
    RESOURCE("resource", "资源", "RESOURCE", 50),
    EXTERNAL("external", "外部依赖", "EXTERNAL", 60),
}

class ArchitectureDisplayLayerResolver {
    fun resolve(
        node: ArchitectureNode,
        index: ArchitectureGraphIndex,
    ): ArchitectureDisplayLayer {
        if (node.kind == ArchitectureNodeKind.RESOURCE) {
            return ArchitectureDisplayLayer.RESOURCE
        }
        if (node.kind in setOf(ArchitectureNodeKind.LIBRARY, ArchitectureNodeKind.JDK)) {
            return ArchitectureDisplayLayer.EXTERNAL
        }
        if (node.kind == ArchitectureNodeKind.LAYER) {
            return layerFromText(node.qualifiedName) ?: ArchitectureDisplayLayer.APPLICATION
        }
        val directClass = index.findSymbol(node.id) as? JvmClassSymbol
        if (directClass != null) {
            return resolveClass(directClass)
        }
        val memberClassLayers = node.memberClassIds
            .mapNotNull { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
            .map(::resolveClass)
        return memberClassLayers.dominantLayer()
            ?: layerFromNodeMetadata(node)
            ?: when (node.kind) {
                ArchitectureNodeKind.SERVICE -> ArchitectureDisplayLayer.APPLICATION
                ArchitectureNodeKind.COMPONENT,
                ArchitectureNodeKind.PACKAGE,
                ArchitectureNodeKind.MODULE,
                -> ArchitectureDisplayLayer.APPLICATION
                else -> ArchitectureDisplayLayer.APPLICATION
            }
    }

    fun resolve(node: GraphNode): ArchitectureDisplayLayer =
        node.metadata["presentation.laneId"]?.let(::layerFromLaneId)
            ?: node.metadata["indexed.layerKind"]?.let(::layerFromIndexedLayer)
            ?: node.metadata["indexed.nodeRole"]?.let(::layerFromIndexedRole)
            ?: layerFromText(node.metadata["architecture.qualifiedName"].orEmpty())
            ?: ArchitectureDisplayLayer.APPLICATION

    private fun resolveClass(cls: JvmClassSymbol): ArchitectureDisplayLayer =
        when {
            cls.indexedLayerKind() == IndexedGraphLayerKind.JDK ||
                cls.indexedLayerKind() == IndexedGraphLayerKind.EXTERNAL_LIBRARY -> ArchitectureDisplayLayer.EXTERNAL
            cls.indexedNodeRole() == IndexedGraphNodeRole.API ||
                cls.indexedNodeRole() == IndexedGraphNodeRole.ENTRY -> ArchitectureDisplayLayer.ENTRY
            cls.indexedNodeRole() == IndexedGraphNodeRole.SERVICE -> ArchitectureDisplayLayer.APPLICATION
            cls.indexedNodeRole() == IndexedGraphNodeRole.DATA ||
                cls.indexedNodeRole() == IndexedGraphNodeRole.CONFIG -> ArchitectureDisplayLayer.DATA
            cls.indexedNodeRole() == IndexedGraphNodeRole.RESOURCE -> ArchitectureDisplayLayer.RESOURCE
            else -> layerFromText("${cls.packageName}.${cls.simpleName}") ?: ArchitectureDisplayLayer.APPLICATION
        }

    private fun layerFromNodeMetadata(node: ArchitectureNode): ArchitectureDisplayLayer? {
        node.stereotype?.let { stereotype -> layerFromStereotype(stereotype)?.let { return it } }
        node.metadata["class.stereotype"]
            ?.let { raw -> JvmStereotype.entries.firstOrNull { it.name == raw } }
            ?.let(::layerFromStereotype)
            ?.let { return it }
        node.metadata["indexed.nodeRole"]?.let(::layerFromIndexedRole)?.let { return it }
        node.metadata["indexed.layerKind"]?.let(::layerFromIndexedLayer)?.let { return it }
        return layerFromText("${node.packageName.orEmpty()}.${node.qualifiedName}.${node.title}")
    }

    private fun layerFromStereotype(stereotype: JvmStereotype): ArchitectureDisplayLayer? =
        when (stereotype) {
            JvmStereotype.CONTROLLER -> ArchitectureDisplayLayer.ENTRY
            JvmStereotype.SERVICE,
            JvmStereotype.COMPONENT,
            -> ArchitectureDisplayLayer.APPLICATION
            JvmStereotype.REPOSITORY,
            JvmStereotype.CONFIGURATION,
            -> ArchitectureDisplayLayer.DATA
            JvmStereotype.RESOURCE -> ArchitectureDisplayLayer.RESOURCE
            JvmStereotype.UNKNOWN -> null
        }

    private fun layerFromIndexedRole(raw: String): ArchitectureDisplayLayer? =
        when (raw) {
            IndexedGraphNodeRole.ENTRY.name,
            IndexedGraphNodeRole.API.name,
            -> ArchitectureDisplayLayer.ENTRY
            IndexedGraphNodeRole.SERVICE.name -> ArchitectureDisplayLayer.APPLICATION
            IndexedGraphNodeRole.DATA.name,
            IndexedGraphNodeRole.CONFIG.name,
            -> ArchitectureDisplayLayer.DATA
            IndexedGraphNodeRole.RESOURCE.name -> ArchitectureDisplayLayer.RESOURCE
            IndexedGraphNodeRole.EXTERNAL.name -> ArchitectureDisplayLayer.EXTERNAL
            else -> null
        }

    private fun layerFromIndexedLayer(raw: String): ArchitectureDisplayLayer? =
        when (raw) {
            IndexedGraphLayerKind.RESOURCE.name -> ArchitectureDisplayLayer.RESOURCE
            IndexedGraphLayerKind.EXTERNAL_LIBRARY.name,
            IndexedGraphLayerKind.JDK.name,
            -> ArchitectureDisplayLayer.EXTERNAL
            else -> null
        }

    private fun layerFromText(raw: String): ArchitectureDisplayLayer? {
        val normalized = raw.lowercase()
        return when {
            containsSegment(normalized, "controller") ||
                containsSegment(normalized, "web") ||
                containsSegment(normalized, "api") -> ArchitectureDisplayLayer.ENTRY
            containsSegment(normalized, "service") -> ArchitectureDisplayLayer.APPLICATION
            containsSegment(normalized, "domain") ||
                containsSegment(normalized, "model") -> ArchitectureDisplayLayer.DOMAIN
            containsSegment(normalized, "repository") ||
                containsSegment(normalized, "dao") ||
                containsSegment(normalized, "mapper") ||
                containsSegment(normalized, "config") ||
                containsSegment(normalized, "infra") ||
                containsSegment(normalized, "infrastructure") -> ArchitectureDisplayLayer.DATA
            else -> null
        }
    }

    private fun layerFromLaneId(laneId: String): ArchitectureDisplayLayer? =
        ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == laneId }

    private fun List<ArchitectureDisplayLayer>.dominantLayer(): ArchitectureDisplayLayer? =
        groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<ArchitectureDisplayLayer, Int>> { it.value }.thenBy { it.key.order })
            .firstOrNull()
            ?.key

    private fun containsSegment(
        text: String,
        segment: String,
    ): Boolean =
        text == segment ||
            text.startsWith("$segment.") ||
            text.endsWith(".$segment") ||
            ".$segment." in text ||
            text.endsWith(segment)
}
