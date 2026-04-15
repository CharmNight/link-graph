package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationService
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditInvestigationLead
import com.charmnight.linkgraph.workbench.AuditInvestigationLeadStatus
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.AuditModelTurn
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.hasDirectEvidence

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
        sourceLeadId: String? = null,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult {
        val effectiveContext = context.withDerivedEvidenceTrace()
        val sanitized = settings.sanitized()
        val currentSession = ensureUserQuestion(session ?: emptySession(effectiveContext), question)
        val promptPackage = promptFactory.buildAuditPromptPackage(effectiveContext, question, sanitized, currentSession)
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(effectiveContext, question, promptPackage.preview, currentSession, sourceLeadId)
        }
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(effectiveContext, question, promptPackage.preview, currentSession, sourceLeadId).copy(
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
                context = effectiveContext,
                session = currentSession,
                sourceLeadId = sourceLeadId,
            )
        }.getOrElse { error ->
            buildMockResult(effectiveContext, question, promptPackage.preview, currentSession, sourceLeadId).copy(
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
        sourceLeadId: String? = null,
    ): GraphPatchResult {
        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        val hasFallbackIntent = question.contains("兜底") || question.contains("默认")
        val explanationIntent = question.contains("介绍") || question.contains("解释") || question.contains("讲解")
        val explicitAuditIntent = question.contains("审计")
            || question.contains("问题")
            || question.contains("风险")
            || question.contains("漏洞")
            || question.contains("遗漏")
            || question.contains("修改")
            || question.contains("调整")
            || question.contains("修正")
        val scopeKey = scopeNodes.map(GraphNode::id).sorted().joinToString(",").ifBlank { "scope" }
        val scopeLabel = when {
            context.selectedNodeIds.isEmpty() -> "整图"
            scopeNodes.size > 1 -> "当前框选范围（${scopeNodes.size} 个节点）"
            else -> "当前节点"
        }
        val explanationAnswer = buildString {
            append("当前范围说明：").append(scopeLabel).append("。")
            if (scopeNodes.isNotEmpty()) {
                append("本轮主要涉及：")
                append(scopeNodes.joinToString(" -> ") { it.title.ifBlank { it.id } })
                append("。")
            }
            if (context.factGraph.edges.isNotEmpty()) {
                append("当前看到的调用/连接数量为 ").append(context.factGraph.edges.size).append("。")
            }
        }
        val answer = if (explanationIntent && !explicitAuditIntent && !hasFallbackIntent) {
            explanationAnswer
        } else if (hasFallbackIntent) {
            """
            当前轮结论：$scopeLabel 里存在待确认边界，应先登记为“默认兜底规则”风险线索。
            处理建议：先确认条件未命中时的处理分支，拿到直接证据后再决定是否写入草稿层。
            """.trimIndent()
        } else {
            """
            当前轮结论：$scopeLabel 里存在待确认业务规则，应先登记为风险线索而不是直接写草稿。
            处理建议：先确认真实业务约束，拿到直接证据后再决定是否写入草稿层。
            """.trimIndent()
        }
        val findingClaim = if (explanationIntent && !explicitAuditIntent && !hasFallbackIntent) {
            "当前图里可以直接观察到该链路范围内的节点与连接关系。"
        } else if (hasFallbackIntent) {
            "当前上下文没有直接观察到默认兜底分支。"
        } else {
            "当前上下文没有直接观察到足以证明完整业务规则的证据。"
        }
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
        val investigationLeads = if (explanationIntent && !explicitAuditIntent && !hasFallbackIntent) {
            emptyList()
        } else {
            listOf(
                AuditInvestigationLead(
                    leadId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-audit-change", "audit-lead"),
                    status = AuditInvestigationLeadStatus.OPEN,
                    title = if (hasFallbackIntent) "补充默认兜底规则" else "补充业务规则说明",
                    targetNodeIds = scopeNodes.ifEmpty { context.factGraph.nodes.take(1) }.map(GraphNode::id),
                    summary = if (hasFallbackIntent) {
                        "当前还不能证明默认兜底逻辑存在或不存在，需要继续核对条件未命中时的处理分支。"
                    } else {
                        "当前还不能证明这条业务规则真实存在，需要继续核对相关源码或图节点。"
                    },
                    evidenceGap = if (hasFallbackIntent) {
                        "目前没有直接看到条件未命中后的处理分支。"
                    } else {
                        "目前没有直接看到足以证明完整业务规则的源码或图事实。"
                    },
                    recommendedQuestion = if (hasFallbackIntent) {
                        "请继续取证：定位条件未命中时的默认处理分支，确认是否存在明确兜底逻辑。"
                    } else {
                        "请继续取证：定位这条链路对应的真实业务规则实现，确认当前图里缺失的是哪一段源码或分支。"
                    },
                    claimType = "RISK_HINT",
                    evidence = findings,
                ),
            )
        }
        return applyConversationTurn(
            base = GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = answer,
                promptPreview = prompt,
                findings = findings,
                investigationLeads = investigationLeads,
                sourceContext = context.sourceContext,
                evidenceTrace = context.evidenceTrace,
            ),
            context = context,
            session = session,
            sourceLeadId = sourceLeadId,
        )
    }

    /** 把本轮回答和候选变更写入会话。 */
    private fun applyConversationTurn(
        base: GraphPatchResult,
        context: GraphAuditContext,
        session: AuditConversationSession,
        sourceLeadId: String? = null,
    ): GraphPatchResult {
        val rawCandidateChanges = base.candidateChanges.ifEmpty { deriveCandidateChanges(base.patch, base.findings) }
        val classification = classifyAuditOutputs(
            candidateChanges = rawCandidateChanges,
            explicitInvestigationLeads = base.investigationLeads,
            context = context,
        )
        val turnResult = auditConversationService.applyModelTurn(
            session = session,
            modelTurn = AuditModelTurn(
                answer = base.answer,
                candidateChanges = classification.candidateChanges,
                investigationLeads = classification.investigationLeads,
                sourceLeadId = sourceLeadId,
            ),
        )
        return base.copy(
            patch = null,
            candidateChanges = turnResult.session.candidateChanges,
            newCandidateChanges = turnResult.newCandidateChanges,
            investigationLeads = turnResult.session.investigationLeads,
            newInvestigationLeads = turnResult.newInvestigationLeads,
            sourceContext = context.sourceContext,
            evidenceTrace = context.evidenceTrace,
            auditSession = turnResult.session,
        )
    }

    /** 当远程仍返回 patch 结构时，兜底转换为候选变更。 */
    private fun deriveCandidateChanges(
        patch: com.charmnight.linkgraph.model.GraphPatch?,
        findings: List<ResultEvidenceFinding>,
    ): List<CandidateDraftChange> {
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
                claimType = operation.metadata["draft.claimType"],
                evidence = findings,
            )
        }
    }

    private fun classifyAuditOutputs(
        candidateChanges: List<CandidateDraftChange>,
        explicitInvestigationLeads: List<AuditInvestigationLead>,
        context: GraphAuditContext,
    ): ClassifiedAuditOutputs {
        val promotableChanges = mutableListOf<CandidateDraftChange>()
        val investigationLeads = linkedMapOf<String, AuditInvestigationLead>()

        normalizeCandidateChanges(candidateChanges).forEach { change ->
            if (change.hasDirectEvidence()) {
                promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
            } else {
                val lead = leadFromWeakCandidateChange(change)
                investigationLeads[lead.leadId] = lead
            }
        }
        normalizeInvestigationLeads(explicitInvestigationLeads).forEach { lead ->
            investigationLeads[lead.leadId] = lead
        }

        return ClassifiedAuditOutputs(
            candidateChanges = promotableChanges,
            investigationLeads = investigationLeads.values.toList(),
        )
    }

    private fun normalizeCandidateChanges(changes: List<CandidateDraftChange>): List<CandidateDraftChange> {
        return changes.mapNotNull { change ->
            val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
            if (normalizedEvidence.isEmpty()) {
                return@mapNotNull null
            }
            change.copy(
                claimType = change.claimType ?: inferClaimType(normalizedEvidence),
                evidence = normalizedEvidence,
                editScopes = change.editScopes.distinctBy(EditScope::scopeId),
            )
        }
    }

    private fun deriveEditScopes(
        change: CandidateDraftChange,
        context: GraphAuditContext,
    ): List<EditScope> {
        val nodeById = (context.draftGraph.nodes + context.factGraph.nodes).distinctBy(GraphNode::id).associateBy(GraphNode::id)
        val sourceSnippetByNodeId = context.sourceContext.associateBy(SourceSnippetContext::nodeId)
        val supportingFindingIds = change.evidence.map(ResultEvidenceFinding::id)
        return change.targetNodeIds.mapNotNull { nodeId ->
            val node = nodeById[nodeId] ?: return@mapNotNull null
            val directReference = change.evidence.firstNotNullOfOrNull { finding ->
                finding.references.firstOrNull { reference ->
                    (reference.nodeId == null || reference.nodeId == nodeId) && !reference.filePath.isNullOrBlank()
                }?.let { reference -> finding to reference }
            }
            val reference = directReference?.second
            val snippet = sourceSnippetByNodeId[nodeId]
            val filePath = reference?.filePath
                ?: snippet?.filePath
                ?: node.metadata["source.filePath"]
                ?: node.location?.substringBefore(':')
                ?: return@mapNotNull null
            EditScope(
                scopeId = "scope-${change.changeId}-$nodeId",
                targetNodeId = nodeId,
                filePath = filePath,
                language = inferLanguage(filePath),
                symbolKind = node.type.name,
                symbolSignature = node.signature,
                startOffset = snippet?.startOffset ?: node.metadata["source.startOffset"]?.toIntOrNull(),
                endOffset = snippet?.endOffset ?: node.metadata["source.endOffset"]?.toIntOrNull(),
                startLine = reference?.startLine ?: snippet?.startLine ?: node.metadata["source.startLine"]?.toIntOrNull(),
                endLine = reference?.endLine ?: snippet?.endLine ?: node.metadata["source.endLine"]?.toIntOrNull(),
                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK", "REPLACE_METHOD_BODY", "ADD_IMPORT"),
                supportingFindingIds = supportingFindingIds,
            )
        }.distinctBy(EditScope::scopeId)
    }

    private fun inferLanguage(filePath: String): String {
        return when {
            filePath.endsWith(".kt", ignoreCase = true) -> "KOTLIN"
            filePath.endsWith(".java", ignoreCase = true) -> "JAVA"
            else -> "TEXT"
        }
    }

    private fun normalizeInvestigationLeads(leads: List<AuditInvestigationLead>): List<AuditInvestigationLead> {
        return leads.mapNotNull { lead ->
            val normalizedEvidence = lead.evidence.distinctBy(ResultEvidenceFinding::id)
            if (normalizedEvidence.isEmpty()) {
                return@mapNotNull null
            }
            lead.copy(
                claimType = lead.claimType ?: inferClaimType(normalizedEvidence),
                summary = lead.summary.ifBlank {
                    normalizedEvidence.firstOrNull()?.claim ?: lead.title
                },
                evidenceGap = lead.evidenceGap.ifBlank {
                    inferEvidenceGap(normalizedEvidence)
                },
                recommendedQuestion = lead.recommendedQuestion.ifBlank {
                    buildRecommendedQuestion(lead.title, normalizedEvidence)
                },
                evidence = normalizedEvidence,
            )
        }
    }

    private fun inferClaimType(evidence: List<ResultEvidenceFinding>): String {
        return if (evidence.any { finding ->
                finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
                    finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
            }
        ) {
            "CODE_FACT"
        } else {
            "RISK_HINT"
        }
    }

    private fun leadFromWeakCandidateChange(change: CandidateDraftChange): AuditInvestigationLead {
        val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
        return AuditInvestigationLead(
            leadId = "lead-${change.changeId}",
            status = AuditInvestigationLeadStatus.OPEN,
            title = change.title,
            targetStepIds = change.targetStepIds,
            targetNodeIds = change.targetNodeIds,
            summary = change.reason.ifBlank { change.impactSummary },
            evidenceGap = inferEvidenceGap(normalizedEvidence),
            recommendedQuestion = buildRecommendedQuestion(change.title, normalizedEvidence),
            claimType = change.claimType ?: inferClaimType(normalizedEvidence),
            evidence = normalizedEvidence,
        )
    }

    private fun inferEvidenceGap(evidence: List<ResultEvidenceFinding>): String {
        return when {
            evidence.any { it.evidenceLevel == ResultEvidenceLevel.CALLSITE_ONLY } ->
                "当前只看到调用点，没有看到被调实现或完整分支。"
            evidence.any { it.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED } ->
                "当前上下文没有直接观察到这条行为对应的源码、节点或分支。"
            else ->
                "当前证据还不足以把这条结论提升为可入草稿的真实变更。"
        }
    }

    private fun buildRecommendedQuestion(
        title: String,
        evidence: List<ResultEvidenceFinding>,
    ): String {
        return when {
            evidence.any { it.evidenceLevel == ResultEvidenceLevel.CALLSITE_ONLY } ->
                "请继续取证：沿着这条调用继续展开被调实现，确认“$title”是否真的成立。"
            evidence.any { it.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED } ->
                "请继续取证：补充能直接证明“$title”的源码片段、条件分支或图节点。"
            else ->
                "请继续取证：核对“$title”的直接源码证据，再决定是否进入草稿。"
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

    private fun GraphAuditContext.withDerivedEvidenceTrace(): GraphAuditContext {
        if (evidenceTrace.isNotEmpty() || sourceContext.isEmpty()) {
            return this
        }
        return copy(
            evidenceTrace = sourceContext.map { snippet ->
                EvidenceTraceEntry(
                    nodeId = snippet.nodeId,
                    filePath = snippet.filePath,
                    reason = "本轮审计直接附带的源码片段",
                    startLine = snippet.startLine,
                    endLine = snippet.endLine,
                    includedInPrompt = true,
                )
            },
        )
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
  "investigationLeads": [
    {
      "leadId": "稳定ID",
      "status": "OPEN|PROMOTED|DISMISSED|SUPERSEDED",
      "title": "风险线索标题",
      "targetStepIds": [],
      "targetNodeIds": [],
      "summary": "当前已经观察到什么",
      "evidenceGap": "还缺什么证据",
      "recommendedQuestion": "下一轮建议追问什么",
      "claimType": "RISK_HINT",
      "supportingFindingIds": ["必须对应 findings[*].id"]
    }
  ],
  "warnings": ["可选警告"],
  "patch": null
}
"""
    }

    private data class ClassifiedAuditOutputs(
        val candidateChanges: List<CandidateDraftChange>,
        val investigationLeads: List<AuditInvestigationLead>,
    )
}
