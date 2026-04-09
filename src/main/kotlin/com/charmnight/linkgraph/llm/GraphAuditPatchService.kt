package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 基于当前审计范围生成“回答 + 草稿 patch 预览”。
 * 一期先提供规则化本地结果，保证链路不断。
 */
class GraphAuditPatchService(
    /** 负责构造审计提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
) {
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 执行链路审计，必要时回退到本地规则结果。 */
    fun audit(
        context: GraphAuditContext,
        question: String,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult {
        /** 清洗后的生成设置。 */
        val sanitized = settings.sanitized()
        /** 审计提示词包。 */
        val promptPackage = promptFactory.buildAuditPromptPackage(context, question, sanitized)
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(context, question, promptPackage.preview)
        }
        /** 远程连接参数。 */
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(context, question, promptPackage.preview).copy(
                warnings = listOf(sanitized.remoteLlmSetupHint("本地规则审计")),
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "审计",
                schema = PATCH_RESULT_SCHEMA,
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
                    buildRemoteFallbackWarning("审计", error),
                ),
            )
        }
    }

    /** 构造不依赖远程模型的本地审计结果。 */
    private fun buildMockResult(
        context: GraphAuditContext,
        question: String,
        prompt: String,
    ): GraphPatchResult {
        /** 当前审计范围内的节点。 */
        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        /** 用户是否在问题中显式强调兜底或默认逻辑。 */
        val hasFallbackIntent = question.contains("兜底") || question.contains("默认")
        /** 草稿说明节点标题。 */
        val noteTitle = if (hasFallbackIntent) "默认兜底说明" else "审计补充说明"
        /** 草稿说明节点正文。 */
        val noteDoc = if (hasFallbackIntent) {
            "AI 审计建议：当前范围可能遗漏默认兜底逻辑，建议在草稿层补充说明并人工确认真实实现。"
        } else {
            "AI 审计建议：当前范围存在待确认业务规则，建议先以草稿说明节点补充。"
        }
        /** 用于生成稳定节点 ID 的范围键。 */
        val scopeKey = scopeNodes.map(GraphNode::id).sorted().joinToString(",").ifBlank { "scope" }
        /** 草稿说明节点 ID。 */
        val noteId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-$noteTitle", "draft-ai")
        /** 草稿说明节点。 */
        val noteNode = GraphNode(
            id = noteId,
            type = NodeType.DOC_PAGE,
            title = noteTitle,
            doc = noteDoc,
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = mapOf(
                "draft.reason" to "audit",
                "draft.claimType" to DRAFT_CLAIM_TYPE_RISK_HINT,
            ),
        )
        /** 初始补丁操作列表。 */
        val operations = mutableListOf(
            GraphPatchOperation(
                id = "audit-add-node-$noteId",
                action = GraphPatchAction.ADD_NODE,
                elementKind = GraphDiffElementKind.NODE,
                elementId = noteId,
                title = "新增审计说明节点",
                summary = "把审计建议落到草稿层",
                node = noteNode,
                metadata = mapOf("draft.claimType" to DRAFT_CLAIM_TYPE_RISK_HINT),
            ),
        )
        scopeNodes.ifEmpty { context.factGraph.nodes.take(1) }.distinctBy(GraphNode::id).forEach { node ->
            /** 把说明节点挂到当前范围节点上的草稿边。 */
            val edge = GraphEdge(
                id = GraphEdge.stableId(EdgeType.LINKS_DOC, node.id, noteId, "draft-ai"),
                type = EdgeType.LINKS_DOC,
                fromNodeId = node.id,
                toNodeId = noteId,
                label = "审计建议",
                sourceTag = GraphSourceTag.DRAFT_AI,
                metadata = mapOf("draft.reason" to "audit"),
            )
            operations += GraphPatchOperation(
                id = "audit-add-edge-${edge.id}",
                action = GraphPatchAction.ADD_EDGE,
                elementKind = GraphDiffElementKind.EDGE,
                elementId = edge.id,
                title = "补充审计关系",
                summary = "把审计说明挂到当前范围节点上",
                edge = edge,
                metadata = mapOf("draft.claimType" to DRAFT_CLAIM_TYPE_RISK_HINT),
            )
        }
        /** 当前回答使用的范围标签。 */
        val scopeLabel = when {
            context.selectedNodeIds.isEmpty() -> "整图"
            scopeNodes.size > 1 -> "当前框选范围（${scopeNodes.size} 个节点）"
            else -> "当前节点"
        }
        /** 面向用户展示的审计回答。 */
        val answer = if (hasFallbackIntent) {
            """
            结论：$scopeLabel 里存在待确认边界，当前规则分析建议先补一个“默认兜底说明”节点。
            关键影响：
            - 当路由条件未命中或黑逻辑只在运行时生效时，人工审计无法从当前图中直接确认真实兜底分支。
            建议动作：
            - 先把“默认兜底说明”作为草稿节点挂到当前范围关联节点旁边，再由人工确认是否需要继续落代码。
            注意事项：
            - 当前回答来自本地规则分析，仍需结合真实实现复核。
            """.trimIndent()
        } else {
            """
            结论：$scopeLabel 里存在待确认业务规则，当前规则分析建议先补一条草稿说明。
            关键影响：
            - 如果直接交给 AI 生成代码，遗漏的业务约束可能会被当成不存在，从而产生错误实现。
            建议动作：
            - 先把待确认规则补成草稿说明节点，后续再决定是否继续生成代码。
            注意事项：
            - 当前回答来自本地规则分析，仍需结合真实实现复核。
            """.trimIndent()
        }
        /** 单条结论里的核心陈述。 */
        val findingClaim = if (hasFallbackIntent) {
            "当前上下文没有直接观察到默认兜底分支。"
        } else {
            "当前上下文没有直接观察到足以证明完整业务规则的证据。"
        }
        /** 结构化证据结论列表。 */
        val findings = scopeNodes.ifEmpty { context.factGraph.nodes.take(1) }
            .distinctBy(GraphNode::id)
            .mapIndexed { index, node ->
                ResultEvidenceFinding(
                    id = "audit-finding-$index",
                    claim = findingClaim,
                    evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                    references = listOf(ResultEvidenceReference(nodeId = node.id)),
                )
            }
        return GraphPatchResult(
            source = LlmResultSource.MOCK,
            question = question,
            answer = answer,
            promptPreview = prompt,
            patch = GraphPatch(
                summary = "已生成审计草稿 patch 预览。",
                operations = operations,
                addedNodeIds = listOf(noteId),
                addedEdgeIds = operations.mapNotNull { op -> op.edge?.id },
            ),
            findings = findings,
        )
    }

    /** 生成远程失败后的回退警告文案。 */
    private fun buildRemoteFallbackWarning(
        scene: String,
        error: Throwable,
    ): String {
        return "远程 LLM ${scene}失败，已回退为本地规则分析：${LlmUserMessageFormatter.describe(error)}"
    }

    /** 把远程返回的警告插到结果前面。 */
    private fun GraphPatchResult.withPrependedWarnings(extraWarnings: List<String>): GraphPatchResult {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    private companion object {
        /** 风险提示型草稿声明。 */
        private const val DRAFT_CLAIM_TYPE_RISK_HINT = "RISK_HINT"
        /** 远程审计返回必须遵守的 JSON 结构。 */
        private const val PATCH_RESULT_SCHEMA = """
{
  "answer": "审计或差异说明",
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
  "warnings": ["可选警告"],
  "patch": {
    "summary": "patch 摘要",
    "operations": [],
    "addedNodeIds": [],
    "removedNodeIds": [],
    "addedEdgeIds": [],
    "removedEdgeIds": []
    }
}
"""
    }
}
