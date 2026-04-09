package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationService
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.AuditModelTurn
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus

/**
 * 基于当前审计范围生成“对话回答 + 待确认候选变更”。
 * 审计不会直接写草稿层，所有修改都先停留在候选变更区。
 */
class GraphAuditPatchService(
    /** 负责构造审计提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 负责维护会话与候选变更。 */
    private val auditConversationService: AuditConversationService = AuditConversationService(),
) {
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 执行链路审计，必要时回退到本地规则结果。 */
    fun audit(
        context: GraphAuditContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: AuditConversationSession? = null,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult {
        val sanitized = settings.sanitized()
        val currentSession = ensureUserQuestion(session ?: emptySession(context), question)
        val promptPackage = promptFactory.buildAuditPromptPackage(context, question, sanitized, currentSession)
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(context, question, promptPackage.preview, currentSession)
        }
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(context, question, promptPackage.preview, currentSession).copy(
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
            applyConversationTurn(
                base = remote.value.withPrependedWarnings(remote.warnings),
                session = currentSession,
            )
        }.getOrElse { error ->
            buildMockResult(context, question, promptPackage.preview, currentSession).copy(
                warnings = listOf(buildRemoteFallbackWarning("审计", error)),
            )
        }
    }

    /** 构造不依赖远程模型的本地审计结果。 */
    private fun buildMockResult(
        context: GraphAuditContext,
        question: String,
        prompt: String,
        session: AuditConversationSession,
    ): GraphPatchResult {
        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        val hasFallbackIntent = question.contains("兜底") || question.contains("默认")
        val scopeKey = scopeNodes.map(GraphNode::id).sorted().joinToString(",").ifBlank { "scope" }
        val scopeLabel = when {
            context.selectedNodeIds.isEmpty() -> "整图"
            scopeNodes.size > 1 -> "当前框选范围（${scopeNodes.size} 个节点）"
            else -> "当前节点"
        }
        val answer = if (hasFallbackIntent) {
            """
            当前轮结论：$scopeLabel 里存在待确认边界，建议补一条“默认兜底规则”候选变更。
            处理建议：先确认条件未命中时的处理分支，再决定是否写入草稿层。
            """.trimIndent()
        } else {
            """
            当前轮结论：$scopeLabel 里存在待确认业务规则，建议先补一条候选变更再继续讨论。
            处理建议：先确认真实业务约束，再决定是否写入草稿层。
            """.trimIndent()
        }
        val findingClaim = if (hasFallbackIntent) {
            "当前上下文没有直接观察到默认兜底分支。"
        } else {
            "当前上下文没有直接观察到足以证明完整业务规则的证据。"
        }
        val candidateChanges = listOf(
            CandidateDraftChange(
                changeId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-audit-change", "audit"),
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = if (hasFallbackIntent) "补充默认兜底规则" else "补充业务规则说明",
                targetNodeIds = scopeNodes.ifEmpty { context.factGraph.nodes.take(1) }.map(GraphNode::id),
                beforeState = "当前图中未确认对应业务规则",
                afterState = if (hasFallbackIntent) {
                    "补充默认兜底逻辑说明，并确认条件未命中时的处理分支"
                } else {
                    "补充当前范围缺失的业务规则说明，并在确认后再写入草稿"
                },
                reason = findingClaim,
                impactSummary = if (hasFallbackIntent) {
                    "会影响未命中条件时的最终执行路径。"
                } else {
                    "会影响当前链路的业务解释与后续代码生成。"
                },
            ),
        )
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
        return applyConversationTurn(
            base = GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = answer,
                promptPreview = prompt,
                findings = findings,
                candidateChanges = candidateChanges,
            ),
            session = session,
        )
    }

    /** 把本轮回答和候选变更写入会话。 */
    private fun applyConversationTurn(
        base: GraphPatchResult,
        session: AuditConversationSession,
    ): GraphPatchResult {
        val candidateChanges = base.candidateChanges.ifEmpty { deriveCandidateChanges(base.patch) }
        val turnResult = auditConversationService.applyModelTurn(
            session = session,
            modelTurn = AuditModelTurn(
                answer = base.answer,
                candidateChanges = candidateChanges,
            ),
        )
        return base.copy(
            patch = null,
            candidateChanges = turnResult.session.candidateChanges,
            newCandidateChanges = turnResult.newCandidateChanges,
            auditSession = turnResult.session,
        )
    }

    /** 当远程仍返回 patch 结构时，兜底转换为候选变更。 */
    private fun deriveCandidateChanges(patch: com.charmnight.linkgraph.model.GraphPatch?): List<CandidateDraftChange> {
        patch ?: return emptyList()
        return patch.operations.map { operation ->
            CandidateDraftChange(
                changeId = operation.id,
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = operation.title ?: operation.summary ?: operation.elementId,
                targetNodeIds = listOfNotNull(operation.node?.id, operation.edge?.fromNodeId, operation.edge?.toNodeId).distinct(),
                beforeState = null,
                afterState = operation.summary ?: operation.title,
                reason = "由远程审计建议生成。",
                impactSummary = patch.summary ?: "",
            )
        }
    }

    /** 把当前用户问题写入会话。 */
    private fun ensureUserQuestion(
        session: AuditConversationSession,
        question: String,
    ): AuditConversationSession {
        if (session.messages.lastOrNull()?.role == AuditMessageRole.USER && session.messages.lastOrNull()?.content == question) {
            return session
        }
        return session.copy(
            messages = session.messages + AuditConversationMessage(
                messageId = "${session.sessionId}-user-${session.messages.size + 1}",
                role = AuditMessageRole.USER,
                content = question,
                focusTargetId = session.focusTargetId,
            ),
        )
    }

    /** 基于当前范围生成默认空会话。 */
    private fun emptySession(context: GraphAuditContext): AuditConversationSession {
        val scopeKey = context.selectedNodeIds.sorted().joinToString(",")
            .ifBlank { context.factGraph.nodes.firstOrNull()?.id ?: "graph" }
        return AuditConversationSession(
            sessionId = "audit-${GraphNode.stableId(NodeType.DOC_PAGE, scopeKey, "session")}",
            scopeKey = scopeKey,
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
  "candidateChanges": [
    {
      "changeId": "稳定ID",
      "status": "PENDING_CONFIRMATION|CONFIRMED|REJECTED|SUPERSEDED",
      "title": "候选变更标题",
      "targetStepIds": [],
      "targetNodeIds": [],
      "beforeState": "修改前状态",
      "afterState": "修改后状态",
      "reason": "为什么建议这样改",
      "impactSummary": "影响摘要"
    }
  ],
  "warnings": ["可选警告"],
  "patch": null
}
"""
    }
}
