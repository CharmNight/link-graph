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
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.usage.ClassUsageSearchLimits

/**
 * 图谱浏览器负载解析器。
 *
 * 提供前端与工作台之间桥接消息负载的体积校验与解析能力，
 * 将 JSON 文本解析为图编辑请求或索引化图谱请求等应用层对象。
 */
internal object GraphBrowserPayloadParser {
    /**
     * 校验负载字符串的字符数是否在指定类型允许的上限以内。
     *
     * 超出限制时抛出携带类型标签与具体数值的非法参数异常，避免超大输入冲击后续解析流程。
     */
    fun validatePayloadSize(
        payload: String,
        kind: GraphBrowserPayloadKind,
    ) {
        require(payload.length <= kind.maxChars) {
            "${kind.label} payload 过大：${payload.length} chars，最大允许 ${kind.maxChars} chars。"
        }
    }

    /**
     * 将图编辑脚本负载解析为图编辑请求对象。
     *
     * 先校验体积再借助 LLM 友好的 JSON 解析器读取根对象，并交给专门的解析器进一步转换为请求模型。
     * 解析失败时返回带 issues 的 [GraphEditRequestParseResult]，由调用方决定如何反馈给前端。
     */
    fun parseGraphEditRequest(payload: String): GraphEditRequestParseResult {
        validatePayloadSize(payload, GraphBrowserPayloadKind.GRAPH_EDIT_SCRIPT)
        val root = LlmJsonCodec.parseObject(payload)
        return GraphEditRequestPayloadParser.parse(root)
    }

    /**
     * 将结构化图谱请求负载解析为索引化图谱请求对象。
     *
     * 必须存在预设类型字段，其他诸如包名、范围、视口、类图、使用与评审等子项按可选方式逐一解析，
     * 最终通过工厂组装为统一的索引化图谱请求。
     */
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

    /**
     * 解析视口配置项，控制图谱展示时可见节点与边的最大数量上限。
     */
    private fun parseIndexedViewport(raw: Map<*, *>?): IndexedGraphViewportOptions =
        IndexedGraphViewportOptions(
            maxVisibleNodes = raw?.intOrNull("maxVisibleNodes"),
            maxVisibleEdges = raw?.intOrNull("maxVisibleEdges"),
        )

    /**
     * 解析类图相关的展示选项，包括邻域层数与成员数量限制，缺省值用于保证基础体验。
     */
    private fun parseIndexedClassDiagramOptions(raw: Map<*, *>?): IndexedClassDiagramOptions =
        IndexedClassDiagramOptions(
            neighborhoodLimit = raw?.intOrNull("neighborhoodLimit") ?: 24,
            memberLimit = raw?.intOrNull("memberLimit") ?: 5,
        )

    /**
     * 解析关系细节层级，类图子项优先于顶层配置。
     *
     * 将原始字符串规范化为大写形式并匹配到对应枚举，无法识别时回退为仅结构层级。
     */
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

    /**
     * 解析类使用情况查询相关的选项，含目标节点、来源文件、各类上限与是否包含引用等配置。
     *
     * 数值类字段在缺省时使用搜索限制工具定义的默认值，并经过限幅函数约束到合法范围。
     */
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

    /**
     * 解析评审类图谱的展示上限，涉及变更节点、关联测试节点、上游与下游节点数量的默认约束。
     */
    private fun parseIndexedReviewOptions(raw: Map<*, *>?): IndexedReviewGraphOptions =
        IndexedReviewGraphOptions(
            maxChangedNodes = raw?.intOrNull("maxChangedNodes") ?: 120,
            maxRelatedTestNodes = raw?.intOrNull("maxRelatedTestNodes") ?: 40,
            maxUpstreamNodes = raw?.intOrNull("maxUpstreamNodes") ?: 40,
            maxDownstreamNodes = raw?.intOrNull("maxDownstreamNodes") ?: 40,
        )

    /** 取出必填字符串字段，若缺失或为空白则按描述信息抛出异常。 */
    private fun Map<*, *>.requiredString(
        key: String,
        description: String,
    ): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: error("$description is required")

    /** 取出指定键对应的字符串列表，过滤掉其中空白项并返回非空结果。 */
    private fun Map<*, *>.stringList(key: String): List<String> {
        return (this[key] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }
    }

    /** 按枚举名称将键对应的字符串解析为对应枚举实例，匹配失败或缺失时返回空。 */
    private inline fun <reified T : Enum<T>> Map<*, *>.enumValue(key: String): T? {
        val raw = this[key] as? String ?: return null
        return enumValues<T>().firstOrNull { it.name == raw }
    }

    /** 取出布尔字段，缺失时返回给定的默认值。 */
    private fun Map<*, *>.booleanOrDefault(
        key: String,
        defaultValue: Boolean,
    ): Boolean = (this[key] as? Boolean) ?: defaultValue

    /** 取出布尔字段，缺失时返回空。 */
    private fun Map<*, *>.booleanOrNull(key: String): Boolean? = this[key] as? Boolean

    /** 将数值字段转为 Int，缺失或类型不符时返回空。 */
    private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

    /** 取出字符串字段，缺失时返回空。 */
    private fun Map<*, *>.stringOrNull(key: String): String? = this[key] as? String

}

/**
 * 图谱浏览器负载的种类枚举。
 *
 * 每个种类携带字符上限与面向用户的标签，用于在校验失败时给出更友好的提示。
 */
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

/**
 * 图谱浏览器各类负载的字符上限常量集合。
 */
internal object GraphBrowserPayloadLimits {
    const val GRAPH_EDIT_SCRIPT_MAX_CHARS: Int = 512 * 1024
    const val MERMAID_PAYLOAD_MAX_CHARS: Int = 1024 * 1024
    const val STRUCTURED_PAYLOAD_MAX_CHARS: Int = 64 * 1024
    const val IDENTIFIER_PAYLOAD_MAX_CHARS: Int = 8 * 1024
    const val DEBUG_TRACE_PAYLOAD_MAX_CHARS: Int = 64 * 1024
}
