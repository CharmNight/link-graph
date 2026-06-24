package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 围绕“设计基线 vs 代码事实图”的差异生成解释与修订草稿。
 * 该服务负责拼装提示词、调用远程模型、解析响应，并在远程不可用时回退为本地规则化差异分析。
 */
class GraphDiffPatchService(
    /** 负责构造差异问答提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
) {
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseParser(gateway)

    /** 执行差异问答，必要时回退到本地规则结果。 */
    fun review(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult {
        /** 清洗后的生成设置。 */
        val sanitized = settings.sanitized()
        /** 差异问答提示词包。 */
        val promptPackage = promptFactory.buildDiffReviewPromptPackage(context, question, sanitized)
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(context, question, promptPackage.preview)
        }
        /** 远程连接参数。 */
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(context, question, promptPackage.preview).copy(
                warnings = listOf(sanitized.remoteLlmSetupHint("本地规则差异分析")),
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "差异分析",
                schema = LlmStructuredSchemas.PATCH_RESULT,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                RemoteGraphPatchResultParser.parse(content, promptPackage.preview, question)
            }
        }.map { remote ->
            remote.value.withPrependedWarnings(remote.warnings)
        }.getOrElse { error ->
            buildMockResult(context, question, promptPackage.preview).copy(
                warnings = listOf(
                    buildRemoteFallbackWarning(error),
                ),
            )
        }
    }

    /** 构造不依赖远程模型的本地差异分析结果。 */
    private fun buildMockResult(
        context: GraphDiffContext,
        question: String,
        prompt: String,
    ): GraphPatchResult {
        /** 当前聚焦的差异条目 ID 集合。 */
        val focusedEntryIds = context.selectedDiffItemIds.toSet()
        /** 需要优先解释的差异条目。 */
        val prioritizedEntries = context.diff.entries.filter { entry ->
            entry.elementId in focusedEntryIds
        }
        /** 仅存在于设计图中的节点差异条目。 */
        val designOnlyEntries = (prioritizedEntries + context.diff.entries).filter { entry ->
            entry.elementKind == GraphDiffElementKind.NODE && entry.status == DiffStatus.ONLY_IN_MERMAID
        }.distinctBy { it.elementId }
        /** 根据差异定位到的设计基线节点。 */
        val designNodes = designOnlyEntries.mapNotNull { entry ->
            context.designBaseline.nodes.firstOrNull { node -> node.id == entry.elementId }
        }.ifEmpty {
            context.designBaseline.nodes.filter { baselineNode ->
                context.factGraph.nodes.none { factNode ->
                    factNode.id == baselineNode.id ||
                        factNode.title == baselineNode.title ||
                        (!factNode.signature.isNullOrBlank() && factNode.signature == baselineNode.signature)
                }
            }.take(1)
        }
        /** 由设计缺失节点生成的草稿操作。 */
        val operations = designNodes.map { designNode ->
            GraphPatchOperation(
                id = "diff-add-node-${designNode.id}",
                action = GraphPatchAction.ADD_NODE,
                elementKind = GraphDiffElementKind.NODE,
                elementId = designNode.id,
                title = "根据设计基线补节点",
                summary = "把仅存在于设计图的节点转成草稿建议",
                node = designNode.toDraftSuggestion(),
                metadata = mapOf("draft.claimType" to DRAFT_CLAIM_TYPE_STRUCTURAL_SUGGESTION),
            )
        }
        /** 面向用户展示的差异解释。 */
        val answer = if (designNodes.isNotEmpty()) {
            /** 设计缺失节点标题列表。 */
            val titles = designNodes.joinToString("、") { it.title }
            /** 当前回答使用的范围标签。 */
            val scopeLabel = if (focusedEntryIds.isEmpty()) "当前差异" else "当前焦点差异"
            """
            结论：${scopeLabel}里存在“仅设计图有”的节点 $titles。
            关键影响：
            - 设计期望已经明确，但代码事实层还没有对应实现，后续 AI 开发容易忽略这段业务意图。
            建议动作：
            - 先把上述节点转成草稿修订节点，再决定是否继续生成代码或补充连线。
            注意事项：
            - 当前回答来自本地规则分析，仍需结合真实实现复核。
            """.trimIndent()
        } else {
            """
            结论：当前差异更偏向关系不一致或设计遗漏表达。
            关键影响：
            - 如果不先澄清差异含义，后续生成的草稿可能会把错误关系继续放大。
            建议动作：
            - 先人工确认差异语义，再决定是否生成草稿修订。
            注意事项：
            - 当前回答来自本地规则分析，仍需结合真实实现复核。
            """.trimIndent()
        }
        /** 结构化证据结论列表。 */
        val findings = designNodes.mapIndexed { index, designNode ->
            ResultEvidenceFinding(
                id = "diff-finding-$index",
                claim = "当前差异直接显示设计基线中存在节点“${designNode.title}”，但代码事实图里没有对应实现。",
                evidenceLevel = ResultEvidenceLevel.DIRECT_GRAPH,
                references = listOf(ResultEvidenceReference(nodeId = designNode.id)),
            )
        }
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = question,
            answer = if (question.contains("修订")) "$answer\n建议动作：\n- 当前结果已附带修订草稿，可先预览再写回。" else answer,
            promptPreview = prompt,
            patch = GraphPatch(
                summary = "已根据差异生成修订草稿。",
                operations = operations,
                addedNodeIds = operations.mapNotNull { operation -> operation.node?.id },
            ),
            findings = findings,
        )
    }

    /** 生成远程失败后的回退警告文案。 */
    private fun buildRemoteFallbackWarning(error: Throwable): String {
        return "远程 LLM 差异分析失败，已回退为本地规则分析：${LlmUserMessageFormatter.describe(error)}"
    }

    /** 把设计节点转换为草稿建议节点。 */
    private fun GraphNode.toDraftSuggestion(): GraphNode {
        return copy(
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = metadata + mapOf(
                "draft.reason" to "diff-review",
                "draft.originId" to id,
                "draft.claimType" to DRAFT_CLAIM_TYPE_STRUCTURAL_SUGGESTION,
            ),
        )
    }

    /** 把远程返回的警告插到结果前面。 */
    private fun GraphPatchResult.withPrependedWarnings(extraWarnings: List<String>): GraphPatchResult {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    private companion object {
        /** 结构补全型草稿声明。 */
        private const val DRAFT_CLAIM_TYPE_STRUCTURAL_SUGGESTION = "STRUCTURAL_SUGGESTION"
    }
}
