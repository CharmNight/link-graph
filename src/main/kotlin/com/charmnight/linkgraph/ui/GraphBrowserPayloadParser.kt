package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedClassDiagramOptions
import com.charmnight.linkgraph.application.indexed.IndexedClassUsageOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphPreset
import com.charmnight.linkgraph.application.indexed.IndexedGraphPresetRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequestFactory
import com.charmnight.linkgraph.application.indexed.IndexedGraphRelationDetail
import com.charmnight.linkgraph.application.indexed.IndexedGraphViewportOptions
import com.charmnight.linkgraph.application.indexed.IndexedReviewGraphOptions
import com.charmnight.linkgraph.application.edit.GraphEditRequestPayloadParser
import com.charmnight.linkgraph.llm.LlmJsonCodec
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.usage.ClassUsageSearchLimits

internal object GraphBrowserPayloadParser {
    fun validatePayloadSize(
        payload: String,
        kind: GraphBrowserPayloadKind,
    ) {
        require(payload.length <= kind.maxChars) {
            "${kind.label} payload 过大：${payload.length} chars，最大允许 ${kind.maxChars} chars。"
        }
    }

    fun parseGraphEditRequest(payload: String): GraphEditRequest {
        validatePayloadSize(payload, GraphBrowserPayloadKind.GRAPH_EDIT_SCRIPT)
        val root = LlmJsonCodec.parseObject(payload)
        return GraphEditRequestPayloadParser.parse(root)
    }

    fun parseIndexedGraphRequest(payload: String): IndexedGraphRequest {
        validatePayloadSize(payload, GraphBrowserPayloadKind.STRUCTURED)
        val root = LlmJsonCodec.parseObject(payload)
        val preset = root.enumValue<IndexedGraphPreset>("preset")
            ?: error("indexed graph request preset is required")
        val classDiagramRaw = root["classDiagram"] as? Map<*, *>
        return IndexedGraphRequestFactory.fromPreset(
            IndexedGraphPresetRequest(
                preset = preset,
                packageName = root["packageName"] as? String,
                scopeNodeId = root["scopeNodeId"] as? String,
                selectedDiffItemIds = root.stringList("selectedDiffItemIds"),
                includeExternalLibraries = root.booleanOrNull("includeExternalLibraries"),
                includeJdk = root.booleanOrNull("includeJdk"),
                relationDetail = parseRelationDetail(root, classDiagramRaw),
                viewport = parseIndexedViewport(root["viewport"] as? Map<*, *>),
                classDiagram = classDiagramRaw?.let(::parseIndexedClassDiagramOptions),
                usage = (root["usage"] as? Map<*, *>)?.let(::parseIndexedClassUsageOptions),
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

    private fun parseRelationDetail(
        root: Map<*, *>,
        classDiagram: Map<*, *>?,
    ): IndexedGraphRelationDetail? =
        (classDiagram?.stringOrNull("relationDetail") ?: root.stringOrNull("relationDetail"))
            ?.trim()
            ?.uppercase()
            ?.let { value ->
                when (value) {
                    IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS.name -> IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS
                    IndexedGraphRelationDetail.COMPLETE.name -> IndexedGraphRelationDetail.COMPLETE
                    IndexedGraphRelationDetail.STRUCTURE_ONLY.name -> IndexedGraphRelationDetail.STRUCTURE_ONLY
                    else -> IndexedGraphRelationDetail.STRUCTURE_ONLY
                }
            }

    private fun parseIndexedClassUsageOptions(raw: Map<*, *>): IndexedClassUsageOptions =
        IndexedClassUsageOptions(
            enabled = raw.booleanOrDefault("enabled", false),
            targetNodeId = (raw["targetNodeId"] as? String)?.takeIf(String::isNotBlank),
            targetQualifiedName = (raw["targetQualifiedName"] as? String)?.takeIf(String::isNotBlank),
            sourceVirtualFileUrl = (raw["sourceVirtualFileUrl"] as? String)?.takeIf(String::isNotBlank),
            sourcePath = (raw["sourcePath"] as? String)?.takeIf(String::isNotBlank),
            maxUsageGroups = ClassUsageSearchLimits.clampUsageGroups(
                raw.intOrNull("maxUsageGroups") ?: ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
            ),
            maxUsageEntries = ClassUsageSearchLimits.clampUsageEntries(
                raw.intOrNull("maxUsageEntries") ?: ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
            ),
            includeImports = raw.booleanOrDefault("includeImports", false),
        )

    private fun parseIndexedReviewOptions(raw: Map<*, *>?): IndexedReviewGraphOptions =
        IndexedReviewGraphOptions(
            maxChangedNodes = raw?.intOrNull("maxChangedNodes") ?: 120,
            maxRelatedTestNodes = raw?.intOrNull("maxRelatedTestNodes") ?: 40,
            maxUpstreamNodes = raw?.intOrNull("maxUpstreamNodes") ?: 40,
            maxDownstreamNodes = raw?.intOrNull("maxDownstreamNodes") ?: 40,
        )

    private fun Map<*, *>.requiredString(
        key: String,
        description: String,
    ): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: error("$description is required")

    private fun Map<*, *>.stringList(key: String): List<String> {
        return (this[key] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }
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

    private fun Map<*, *>.stringOrNull(key: String): String? = this[key] as? String

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
