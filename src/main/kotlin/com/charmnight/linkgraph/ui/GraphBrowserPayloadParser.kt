package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedClassDiagramOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphPreset
import com.charmnight.linkgraph.application.indexed.IndexedGraphPresetRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequestFactory
import com.charmnight.linkgraph.application.indexed.IndexedGraphViewportOptions
import com.charmnight.linkgraph.application.indexed.IndexedReviewGraphOptions
import com.charmnight.linkgraph.llm.LlmJsonSupport
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType

internal object GraphBrowserPayloadParser {
    fun validatePayloadSize(
        payload: String,
        kind: GraphBrowserPayloadKind,
    ) {
        require(payload.length <= kind.maxChars) {
            "${kind.label} payload 过大：${payload.length} chars，最大允许 ${kind.maxChars} chars。"
        }
    }

    fun parseGraphEditScript(payload: String): GraphEditScript {
        validatePayloadSize(payload, GraphBrowserPayloadKind.GRAPH_EDIT_SCRIPT)
        val root = LlmJsonSupport.parseObject(payload)
        val sceneId = (root["sceneId"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.let(GraphSceneId::valueOf)
            ?: error("graph edit script sceneId is required")
        val baseWorkspaceRevision = (root["baseWorkspaceRevision"] as? Number)?.toLong()
            ?: error("graph edit script baseWorkspaceRevision is required")
        val operations = (root["operations"] as? List<*>).orEmpty().mapIndexed { index, raw ->
            parseGraphEditOperation(raw as? Map<*, *>, index)
        }
        return GraphEditScript(
            sceneId = sceneId,
            baseWorkspaceRevision = baseWorkspaceRevision,
            operations = operations,
        )
    }

    fun parseIndexedGraphRequest(payload: String): IndexedGraphRequest {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val root = LlmJsonSupport.parseObject(payload)
        val preset = root.enumValue<IndexedGraphPreset>("preset")
            ?: error("indexed graph request preset is required")
        return IndexedGraphRequestFactory.fromPreset(
            IndexedGraphPresetRequest(
                preset = preset,
                packageName = root["packageName"] as? String,
                scopeNodeId = root["scopeNodeId"] as? String,
                selectedDiffItemIds = root.stringList("selectedDiffItemIds"),
                includeExternalLibraries = root.booleanOrNull("includeExternalLibraries"),
                includeJdk = root.booleanOrNull("includeJdk"),
                viewport = parseIndexedViewport(root["viewport"] as? Map<*, *>),
                classDiagram = (root["classDiagram"] as? Map<*, *>)?.let(::parseIndexedClassDiagramOptions),
                review = (root["review"] as? Map<*, *>)?.let(::parseIndexedReviewOptions),
            ),
        )
    }

    private fun parseIndexedViewport(raw: Map<*, *>?): IndexedGraphViewportOptions =
        IndexedGraphViewportOptions(
            maxVisibleNodes = raw?.intOrNull("maxVisibleNodes"),
            maxVisibleEdges = raw?.intOrNull("maxVisibleEdges"),
        )

    private fun parseIndexedClassDiagramOptions(raw: Map<*, *>?): IndexedClassDiagramOptions =
        IndexedClassDiagramOptions(
            neighborhoodLimit = raw?.intOrNull("neighborhoodLimit") ?: 24,
            memberLimit = raw?.intOrNull("memberLimit") ?: 5,
        )

    private fun parseIndexedReviewOptions(raw: Map<*, *>?): IndexedReviewGraphOptions =
        IndexedReviewGraphOptions(
            maxChangedNodes = raw?.intOrNull("maxChangedNodes") ?: 120,
            maxRelatedTestNodes = raw?.intOrNull("maxRelatedTestNodes") ?: 40,
            maxUpstreamNodes = raw?.intOrNull("maxUpstreamNodes") ?: 40,
            maxDownstreamNodes = raw?.intOrNull("maxDownstreamNodes") ?: 40,
        )


    private fun parseGraphEditOperation(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEditOperation {
        raw ?: error("graph edit script operation[$index] must be an object")
        val type = (raw["type"] as? String)?.trim().orEmpty()
        return when (type.uppercase()) {
            "UPSERT_NODE",
            "UPSERTNODE",
            -> GraphEditOperation.UpsertNode(
                node = parseGraphNode(raw["node"] as? Map<*, *>, index),
            )

            "REMOVE_NODE",
            "REMOVENODE",
            -> GraphEditOperation.RemoveNode(
                nodeId = raw.requiredString("nodeId", "graph edit script operation[$index].nodeId"),
            )

            "UPSERT_EDGE",
            "UPSERTEDGE",
            -> GraphEditOperation.UpsertEdge(
                edge = parseGraphEdge(raw["edge"] as? Map<*, *>, index),
            )

            "REMOVE_EDGE",
            "REMOVEEDGE",
            -> GraphEditOperation.RemoveEdge(
                edgeId = raw.requiredString("edgeId", "graph edit script operation[$index].edgeId"),
            )

            else -> error("unsupported graph edit script operation[$index].type: $type")
        }
    }

    private fun parseGraphNode(
        raw: Map<*, *>?,
        index: Int,
    ): GraphNode {
        raw ?: error("graph edit script operation[$index].node is required")
        return GraphNode(
            id = raw.requiredString("id", "graph edit script operation[$index].node.id"),
            type = NodeType.valueOf(raw.requiredString("type", "graph edit script operation[$index].node.type")),
            title = (raw["title"] as? String)?.takeIf(String::isNotBlank)
                ?: (raw["label"] as? String)?.takeIf(String::isNotBlank)
                ?: raw.requiredString("id", "graph edit script operation[$index].node.id"),
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
        raw ?: error("graph edit script operation[$index].edge is required")
        return GraphEdge(
            id = raw.requiredString("id", "graph edit script operation[$index].edge.id"),
            type = EdgeType.valueOf(raw.requiredString("type", "graph edit script operation[$index].edge.type")),
            fromNodeId = raw.requiredString("fromNodeId", "graph edit script operation[$index].edge.fromNodeId"),
            toNodeId = raw.requiredString("toNodeId", "graph edit script operation[$index].edge.toNodeId"),
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

    private inline fun <reified T : Enum<T>> Map<*, *>.enumValue(key: String): T? {
        val raw = this[key] as? String ?: return null
        return enumValues<T>().firstOrNull { it.name == raw }
    }

    private fun Map<*, *>.booleanOrDefault(
        key: String,
        defaultValue: Boolean,
    ): Boolean = (this[key] as? Boolean) ?: defaultValue

    private fun Map<*, *>.booleanOrNull(key: String): Boolean? = this[key] as? Boolean

    private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(
        key: String,
        defaultValue: T,
    ): T {
        val raw = this[key] as? String ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
    }
}

internal enum class GraphBrowserPayloadKind(
    val maxChars: Int,
    val label: String,
) {
    MERMAID(GraphBrowserPayloadLimits.MERMAID_PAYLOAD_MAX_CHARS, "Mermaid 导入"),
    GRAPH_EDIT_SCRIPT(GraphBrowserPayloadLimits.GRAPH_EDIT_SCRIPT_MAX_CHARS, "图编辑脚本"),
    STRUCTURED(GraphBrowserPayloadLimits.STRUCTURED_PAYLOAD_MAX_CHARS, "结构化 bridge"),
    IDENTIFIER(GraphBrowserPayloadLimits.IDENTIFIER_PAYLOAD_MAX_CHARS, "标识符 bridge"),
    DEBUG_TRACE(GraphBrowserPayloadLimits.DEBUG_TRACE_PAYLOAD_MAX_CHARS, "前端 trace"),
}

internal object GraphBrowserPayloadLimits {
    const val GRAPH_EDIT_SCRIPT_MAX_CHARS: Int = 512 * 1024
    const val MERMAID_PAYLOAD_MAX_CHARS: Int = 1024 * 1024
    const val STRUCTURED_PAYLOAD_MAX_CHARS: Int = 64 * 1024
    const val IDENTIFIER_PAYLOAD_MAX_CHARS: Int = 8 * 1024
    const val DEBUG_TRACE_PAYLOAD_MAX_CHARS: Int = 64 * 1024
}
