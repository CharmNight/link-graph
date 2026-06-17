package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType

object GraphEditRequestPayloadParser {
    fun parse(root: Map<*, *>): GraphEditRequest {
        val sceneId = root.enum<GraphSceneId>("sceneId")
        val baseWorkspaceRevision = (root["baseWorkspaceRevision"] as? Number)?.toLong()
            ?: error("graph edit request baseWorkspaceRevision is required")
        val source = root.enum<GraphEditRequestSource>("source")
        val operations = (root["operations"] as? List<*>).orEmpty().mapIndexed { index, raw ->
            parseOperation(raw as? Map<*, *>, index)
        }
        return GraphEditRequest(
            sceneId = sceneId,
            baseWorkspaceRevision = baseWorkspaceRevision,
            operations = operations,
            source = source,
        )
    }

    private fun parseOperation(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEditOperation {
        raw ?: error("graph edit request operation[$index] must be an object")
        return when (val type = raw.requiredString("type", "graph edit request operation[$index].type")) {
            "UPSERT_NODE" -> GraphEditOperation.UpsertNode(parseGraphNode(raw["node"] as? Map<*, *>, index))
            "REMOVE_NODE" -> GraphEditOperation.RemoveNode(
                raw.requiredString("nodeId", "graph edit request operation[$index].nodeId"),
            )
            "UPSERT_EDGE" -> GraphEditOperation.UpsertEdge(parseGraphEdge(raw["edge"] as? Map<*, *>, index))
            "REMOVE_EDGE" -> GraphEditOperation.RemoveEdge(
                raw.requiredString("edgeId", "graph edit request operation[$index].edgeId"),
            )
            else -> error("unsupported graph edit request operation[$index].type: $type")
        }
    }

    private fun parseGraphNode(
        raw: Map<*, *>?,
        index: Int,
    ): GraphNode {
        raw ?: error("graph edit request operation[$index].node is required")
        return GraphNode(
            id = raw.requiredString("id", "graph edit request operation[$index].node.id"),
            type = raw.enum("type"),
            title = (raw["title"] as? String)?.takeIf(String::isNotBlank)
                ?: (raw["label"] as? String)?.takeIf(String::isNotBlank)
                ?: raw.requiredString("id", "graph edit request operation[$index].node.id"),
            location = raw["location"] as? String,
            signature = raw["signature"] as? String,
            inputs = raw.stringList("inputs"),
            outputs = raw.stringList("outputs"),
            doc = raw["doc"] as? String,
            sourceKind = raw["sourceKind"] as? String,
            status = raw["status"] as? String,
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            metadata = raw.stringMap("metadata"),
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
        )
    }

    private fun parseGraphEdge(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEdge {
        raw ?: error("graph edit request operation[$index].edge is required")
        return GraphEdge(
            id = raw.requiredString("id", "graph edit request operation[$index].edge.id"),
            type = raw.enum("type"),
            fromNodeId = raw.requiredString("fromNodeId", "graph edit request operation[$index].edge.fromNodeId"),
            toNodeId = raw.requiredString("toNodeId", "graph edit request operation[$index].edge.toNodeId"),
            label = raw["label"] as? String,
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            status = raw["status"] as? String,
            metadata = raw.stringMap("metadata"),
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
        )
    }

    private fun Map<*, *>.requiredString(
        key: String,
        description: String,
    ): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: error("$description is required")

    private fun Map<*, *>.stringList(key: String): List<String> {
        return (this[key] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }
    }

    private fun Map<*, *>.stringMap(key: String): Map<String, String> {
        return ((this[key] as? Map<*, *>).orEmpty()).mapNotNull { (rawKey, rawValue) ->
            val mapKey = rawKey as? String ?: return@mapNotNull null
            val mapValue = rawValue as? String ?: return@mapNotNull null
            mapKey to mapValue
        }.toMap()
    }

    private inline fun <reified T : Enum<T>> Map<*, *>.enum(key: String): T {
        val raw = requiredString(key, key)
        return enumValues<T>().firstOrNull { it.name == raw } ?: error("$key has unsupported value: $raw")
    }

    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(
        key: String,
        defaultValue: T,
    ): T {
        val raw = this[key] as? String ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == raw } ?: error("$key has unsupported value: $raw")
    }
}
