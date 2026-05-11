package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.LlmJsonSupport
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object GraphBrowserPayloadParser {
    private const val PAYLOAD_SEPARATOR: String = "\u001F"

    fun validatePayloadSize(
        payload: String,
        kind: GraphBrowserPayloadKind,
    ) {
        require(payload.length <= kind.maxChars) {
            "${kind.label} payload 过大：${payload.length} chars，最大允许 ${kind.maxChars} chars。"
        }
    }

    data class GenerationPlanDiscussionPayload(
        val question: String,
        val focusItemId: String?,
    )

    data class AuditRequestPayload(
        val question: String,
        val selectedNodeIds: List<String>,
        val sourceThreadId: String?,
        val mode: QaMode,
    )

    data class ResolveInvestigationThreadPayload(
        val threadId: String,
        val resolutionStatus: RiskResolutionStatus,
        val note: String,
    )

    data class BeautificationPayload(
        val goal: String,
        val preferredStyle: String?,
        val explanationFocus: String?,
        val followUp: GraphBeautificationFollowUpContext?,
        val granularity: StepGranularity,
    )

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

    fun parseQuestionWithIds(payload: String): Pair<String, List<String>> {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        val question = decodePayloadValue(parts.firstOrNull().orEmpty())
        val selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty())
        return question to selectedNodeIds
    }

    fun parseGenerationPlanDiscussionPayload(payload: String): GenerationPlanDiscussionPayload {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        return GenerationPlanDiscussionPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            focusItemId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
        )
    }

    fun parseAuditRequestPayload(payload: String): AuditRequestPayload {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 4)
        return AuditRequestPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty()),
            sourceThreadId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
            mode = parts.getOrNull(3)
                ?.takeIf { it.isNotBlank() }
                ?.let(::decodePayloadValue)
                ?.let { raw -> runCatching { QaMode.valueOf(raw) }.getOrDefault(QaMode.AUTO) }
                ?: QaMode.AUTO,
        )
    }

    fun parseResolveInvestigationThreadPayload(payload: String): ResolveInvestigationThreadPayload {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        val threadId = decodePayloadValue(parts.firstOrNull().orEmpty()).ifBlank {
            error("风险线程标识不能为空")
        }
        val resolutionStatus = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let(RiskResolutionStatus::valueOf)
            ?: error("风险决策状态不能为空")
        val note = parts.getOrNull(2)?.let(::decodePayloadValue).orEmpty()
        return ResolveInvestigationThreadPayload(
            threadId = threadId,
            resolutionStatus = resolutionStatus,
            note = note,
        )
    }

    fun parseBeautificationPayload(payload: String): BeautificationPayload {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 7)
        val goal = decodePayloadValue(parts.getOrNull(0).orEmpty())
        val preferredStyle = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val explanationFocus = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val granularity = parts.getOrNull(3)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let { raw -> runCatching { StepGranularity.valueOf(raw) }.getOrDefault(StepGranularity.BUSINESS) }
            ?: StepGranularity.BUSINESS
        val followUpStepId = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpStepTitle = parts.getOrNull(5)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpQuestion = parts.getOrNull(6)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUp = if (
            followUpStepId != null &&
            followUpStepTitle != null &&
            followUpQuestion != null
        ) {
            GraphBeautificationFollowUpContext(
                stepId = followUpStepId,
                stepTitle = followUpStepTitle,
                question = followUpQuestion,
            )
        } else {
            null
        }
        return BeautificationPayload(goal, preferredStyle, explanationFocus, followUp, granularity)
    }

    fun parseNullableRevision(payload: String): Long? {
        validatePayloadSize(payload, GraphBrowserPayloadKind.IDENTIFIER)
        return payload.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    fun parseEncodedList(payload: String): List<String> {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        if (payload.isBlank()) {
            return emptyList()
        }
        return payload
            .split(',')
            .mapNotNull { raw ->
                raw.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
            }
    }

    fun parseLayoutPositions(payload: String): Map<String, GraphLayoutPosition> {
        validatePayloadSize(payload, GraphBrowserPayloadKind.GRAPH_EDIT_SCRIPT)
        if (payload.isBlank()) {
            return emptyMap()
        }
        return payload
            .split('\u001e')
            .mapNotNull { entry ->
                val parts = entry.split(PAYLOAD_SEPARATOR)
                if (parts.size != 3) {
                    return@mapNotNull null
                }
                val nodeId = decodePayloadValue(parts[0])
                val x = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val y = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                nodeId to GraphLayoutPosition(x = x, y = y)
            }
            .toMap()
    }

    private fun decodePayloadValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

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
