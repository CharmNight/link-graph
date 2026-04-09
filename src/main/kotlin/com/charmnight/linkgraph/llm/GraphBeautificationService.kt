package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 为“链路 + 代码 -> 可读性美化/解释”预留的稳定服务接口。
 * 当前先用本地规则输出稳定可读的解释结果，后续可平滑接入远程 LLM。
 */
interface GraphBeautificationService {
    /** 基于链路讲解上下文生成说明结果。 */
    fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphBeautificationResult
}

class DefaultGraphBeautificationService(
    /** 负责构造讲解提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 远程不可用时使用的本地讲解服务。 */
    private val fallbackService: GraphBeautificationService = PlaceholderGraphBeautificationService(promptFactory),
) : GraphBeautificationService {
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 优先调用远程讲解，失败时自动回退到本地规则。 */
    override fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)?,
    ): GraphBeautificationResult {
        /** 清洗后的生成设置。 */
        val sanitized = settings.sanitized()
        /** 链路讲解提示词包。 */
        val promptPackage = promptFactory.buildBeautificationPromptPackage(context, sanitized)
        if (!sanitized.usesRemoteProvider()) {
            return fallbackService.beautify(context, sanitized, onPreview)
        }
        /** 远程连接参数。 */
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            /** 本地讲解结果。 */
            val fallbackResult = fallbackService.beautify(context, sanitized, onPreview)
            return fallbackResult.copy(
                warnings = listOf(sanitized.remoteLlmSetupHint("本地规则讲解")) + fallbackResult.warnings,
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "链路讲解",
                schema = BEAUTIFICATION_SCHEMA,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                RemoteGraphBeautificationResultParser.parse(content, promptPackage.preview)
            }
        }.map { remote ->
            remote.value.withPrependedWarnings(remote.warnings)
        }.getOrElse { error ->
            /** 本地讲解结果。 */
            val fallbackResult = fallbackService.beautify(context, sanitized, onPreview)
            fallbackResult.copy(
                warnings = listOf(
                    buildRemoteFallbackWarning(error),
                ) + fallbackResult.warnings,
            )
        }
    }

    /** 生成远程失败后的回退警告文案。 */
    private fun buildRemoteFallbackWarning(error: Throwable): String {
        return "远程 LLM 链路讲解失败，已回退为本地规则讲解：${LlmUserMessageFormatter.describe(error)}"
    }

    /** 把远程返回的警告插到结果前面。 */
    private fun GraphBeautificationResult.withPrependedWarnings(extraWarnings: List<String>): GraphBeautificationResult {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    private companion object {
        /** 远程链路讲解返回必须遵守的 JSON 结构。 */
        private const val BEAUTIFICATION_SCHEMA = """
{
  "summaryTitle": "摘要标题",
  "summary": "整体说明",
  "sections": [
    {
      "id": "稳定ID",
      "title": "分段标题",
      "content": "分段说明"
    }
  ],
  "findings": [
    {
      "id": "稳定ID",
      "claim": "一条必须可追溯的关键结论",
      "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
      "references": [
        {
          "nodeId": "可选节点ID",
          "filePath": "可选源码路径",
          "startLine": 1,
          "endLine": 3
        }
      ]
    }
  ],
  "warnings": ["可选警告"]
}
"""
    }
}

class PlaceholderGraphBeautificationService(
    /** 负责构造讲解提示词预览。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
) : GraphBeautificationService {
    /** 基于本地规则生成稳定可读的链路讲解。 */
    override fun beautify(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)?,
    ): GraphBeautificationResult {
        /** 链路讲解提示词包，仅用于展示预览。 */
        val promptPackage = promptFactory.buildBeautificationPromptPackage(context, settings)
        /** 当前展示上下文。 */
        val presentation = context.presentationContext
        /** 当前可见图。 */
        val visibleGraph = presentation.graph
        /** 完整背景图。 */
        val fullGraph = presentation.fullGraph
        /** 当前讲解锚点节点。 */
        val anchorNode = resolveAnchorNode(context)
        /** 当前讲解锚点标题。 */
        val anchorTitle = anchorNode?.title?.ifBlank { null } ?: "当前链路"
        /** 当前可见节点 ID 集合。 */
        val visibleNodeIds = visibleGraph.nodes.map(GraphNode::id).toSet()
        /** 当前可见节点标题列表。 */
        val visibleNodeTitles = visibleGraph.nodes
            .asSequence()
            .filter { node -> node.id != anchorNode?.id }
            .map(GraphNode::title)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        /** 当前可用的源码片段列表。 */
        val visibleSnippets = context.sourceContext
            .mapNotNull { snippet -> snippet.snippet?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        /** 当前图外仍可继续展开的跨方法节点。 */
        val hiddenCrossMethodNodes = fullGraph.nodes.filter { node ->
            node.id !in visibleNodeIds &&
                node.type == NodeType.METHOD &&
                node.signature != anchorNode?.signature
        }

        /** “当前方法内部”章节正文。 */
        val currentMethodContent = buildCurrentMethodSection(anchorTitle, visibleSnippets, visibleNodeTitles)
        /** “跨方法扩展”章节正文。 */
        val crossMethodContent = buildCrossMethodSection(
            anchorTitle = anchorTitle,
            crossMethodNodes = hiddenCrossMethodNodes,
            hiddenCrossMethodNodeCount = presentation.hiddenCrossMethodNodeCount,
        )
        /** 最终输出的讲解章节列表。 */
        val sections = buildList {
            add(
                GraphBeautificationSection(
                    id = "current-method",
                    title = "当前方法内部",
                    content = currentMethodContent,
                ),
            )
            if (crossMethodContent.isNotBlank()) {
                add(
                    GraphBeautificationSection(
                        id = "cross-method",
                        title = "跨方法扩展",
                        content = crossMethodContent,
                    ),
                )
            }
        }
        /** 结构化证据结论列表。 */
        val findings = buildEvidenceFindings(
            context = context,
            anchorNodeId = anchorNode?.id,
        )
        /** 当前讲解附带的警告列表。 */
        val warnings = buildList {
            if (presentation.hiddenCurrentMethodNodeCount > 0) {
                add("当前方法内部仍有 ${presentation.hiddenCurrentMethodNodeCount} 个节点未展开，当前讲解只覆盖已展示部分。")
            }
            if (presentation.hiddenCrossMethodNodeCount > 0) {
                add("跨方法扩展仍有 ${presentation.hiddenCrossMethodNodeCount} 个节点未展开，可继续展开后再审阅完整链路。")
            }
            context.preferredStyle?.trim()?.takeIf(String::isNotBlank)?.let { style ->
                add("当前讲解已按“$style”风格整理。")
            }
        }
        /** 面向用户展示的整体摘要。 */
        val summary = buildString {
            append("当前链路围绕 ")
            append(anchorTitle)
            append(" 展开。")
            if (visibleSnippets.isNotEmpty()) {
                append("已展示的源码关键动作包括 ")
                append(visibleSnippets.take(2).joinToString("、") { it.quoted(48) })
                append("。")
            } else if (visibleNodeTitles.isNotEmpty()) {
                append("当前画布里已经可见的关键节点包括 ")
                append(visibleNodeTitles.take(2).joinToString("、"))
                append("。")
            }
            if (hiddenCrossMethodNodes.isNotEmpty()) {
                append("跨方法还能继续追到 ")
                append(hiddenCrossMethodNodes.take(2).joinToString("、") { it.title })
                append("。")
            }
        }
        return GraphBeautificationResult(
            source = LlmResultSource.MOCK,
            summaryTitle = "当前链路讲解",
            summary = summary,
            sections = sections,
            findings = findings,
            promptPreview = promptPackage.preview,
            warnings = warnings,
        )
    }

    /** 在当前展示图和完整图里解析讲解锚点节点。 */
    private fun resolveAnchorNode(context: GraphBeautificationContext): GraphNode? {
        /** 当前展示上下文。 */
        val presentation = context.presentationContext
        return presentation.graph.nodes.firstOrNull { it.id == presentation.anchorNodeId }
            ?: presentation.graph.nodes.firstOrNull { it.type == NodeType.METHOD }
            ?: presentation.fullGraph.nodes.firstOrNull { it.id == presentation.anchorNodeId }
            ?: presentation.fullGraph.nodes.firstOrNull { it.type == NodeType.METHOD }
            ?: presentation.graph.nodes.firstOrNull()
    }

    /** 构造“当前方法内部”章节正文。 */
    private fun buildCurrentMethodSection(
        anchorTitle: String,
        visibleSnippets: List<String>,
        visibleNodeTitles: List<String>,
    ): String {
        if (visibleSnippets.isEmpty()) {
            return if (visibleNodeTitles.isEmpty()) {
                "$anchorTitle 当前主要展示已展开的节点关系；由于还没有命中的源码片段，本段说明以链路节点为准。"
            } else {
                buildString {
                    append(anchorTitle)
                    append(" 当前主要围绕这些已展开节点组织：")
                    append(visibleNodeTitles.take(3).joinToString("、"))
                    append("。")
                }
            }
        }
        return buildString {
            append(anchorTitle)
            append(" 当前优先展示方法内部已经落到代码片段的关键动作：")
            append(visibleSnippets.take(3).joinToString("；") { it.quoted(88) })
            append("。")
        }
    }

    /** 构造“跨方法扩展”章节正文。 */
    private fun buildCrossMethodSection(
        anchorTitle: String,
        crossMethodNodes: List<GraphNode>,
        hiddenCrossMethodNodeCount: Int,
    ): String {
        if (crossMethodNodes.isEmpty()) {
            return if (hiddenCrossMethodNodeCount > 0) {
                "$anchorTitle 后面还有未完全展开的跨方法链路，但当前画布里还没有足够信息给出稳定结论。"
            } else {
                ""
            }
        }
        return buildString {
            append("$anchorTitle 在当前方法之外，还能继续延伸到 ")
            append(crossMethodNodes.take(3).joinToString("、") { it.title })
            append("。")
            if (hiddenCrossMethodNodeCount > crossMethodNodes.size) {
                append("当前只挑出了最关键的跨方法节点，其余分支仍可继续展开。")
            }
        }
    }

    /** 为讲解结果生成结构化证据结论。 */
    private fun buildEvidenceFindings(
        context: GraphBeautificationContext,
        anchorNodeId: String?,
    ): List<ResultEvidenceFinding> {
        /** 当前可见节点索引。 */
        val visibleNodesById = context.presentationContext.graph.nodes.associateBy(GraphNode::id)
        /** 直接来自源码片段的证据结论。 */
        val sourceFindings = context.sourceContext.mapIndexedNotNull { index, snippet ->
            val node = visibleNodesById[snippet.nodeId] ?: return@mapIndexedNotNull null
            ResultEvidenceFinding(
                id = "direct-source-$index",
                claim = "当前上下文直接展示了节点“${node.title}”。",
                evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                references = listOf(
                    ResultEvidenceReference(
                        nodeId = node.id,
                        filePath = snippet.filePath,
                        startLine = snippet.startLine,
                        endLine = snippet.endLine,
                    ),
                ),
            )
        }
        /** 仅看到调用点时的证据结论。 */
        val invocationFindings = context.presentationContext.graph.nodes
            .asSequence()
            .filter { node -> node.id != anchorNodeId }
            .filter { node -> node.metadata["flow.kind"] == "INVOCATION" }
            .mapIndexed { index, node ->
                ResultEvidenceFinding(
                    id = "callsite-only-$index",
                    claim = "当前画布只展示了对“${node.title.removePrefix("调用 ").trim()}”的调用点，尚未展示其方法体。",
                    evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                    references = listOf(ResultEvidenceReference(nodeId = node.id)),
                )
            }
            .toList()
        /** 当缺少源码和调用点证据时回退到图级证据。 */
        val graphFindings = if (sourceFindings.isNotEmpty() || invocationFindings.isNotEmpty()) {
            emptyList()
        } else {
            context.presentationContext.graph.nodes
                .asSequence()
                .filter { node -> node.id != anchorNodeId }
                .take(2)
                .mapIndexed { index, node ->
                    ResultEvidenceFinding(
                        id = "direct-graph-$index",
                        claim = "当前画布直接展示了节点“${node.title}”。",
                        evidenceLevel = ResultEvidenceLevel.DIRECT_GRAPH,
                        references = listOf(ResultEvidenceReference(nodeId = node.id)),
                    )
                }
                .toList()
        }
        return (sourceFindings + invocationFindings + graphFindings)
            .distinctBy { finding -> finding.claim to finding.evidenceLevel }
    }

    /** 把源码片段裁成适合摘要展示的引用文案。 */
    private fun String.quoted(limit: Int): String {
        /** 合并空白后的片段文本。 */
        val normalized = trim().replace(Regex("\\s+"), " ")
        /** 按上限裁剪后的片段文本。 */
        val clipped = if (normalized.length > limit) "${normalized.take(limit)}..." else normalized
        return "\"$clipped\""
    }
}
