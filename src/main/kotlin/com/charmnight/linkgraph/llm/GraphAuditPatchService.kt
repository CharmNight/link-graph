package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.services.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.services.GenerationDiagnostics
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationService
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.AuditModelTurn
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.CandidateGraphPatchComposer
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.hasDirectEvidence
import com.intellij.openapi.diagnostic.Logger

/**
 * 基于当前问答范围生成“对话回答 + 待确认候选变更”。
 * 问答不会直接写草稿层，所有修改都先停留在候选变更区。
 */
class GraphAuditPatchService(
    /** 负责构造问答提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 负责维护会话与候选变更。 */
    private val auditConversationService: AuditConversationService = AuditConversationService(),
    /** 负责从本地可信上下文推导 edit scope 路径。 */
    private val trustedEditScopePathResolver: TrustedEditScopePathResolver = TrustedEditScopePathResolver(),
) {
    private val logger = Logger.getInstance(GraphAuditPatchService::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)
    /** 统一候选变更 patch 归一化器。 */
    private val candidatePatchComposer = CandidateGraphPatchComposer()

    /** 执行链路问答，必要时回退到本地规则结果。 */
    fun audit(
        context: GraphAuditContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: AuditConversationSession? = null,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GraphPatchResult {
        val effectiveContext = context.withDerivedEvidenceTrace()
        val sanitized = settings.sanitized()
        val currentSession = ensureUserQuestion(session ?: emptySession(effectiveContext), question)
        val resolvedEffectiveMode = effectiveMode
        val promptPackage = promptFactory.buildAuditPromptPackage(
            effectiveContext,
            question,
            sanitized,
            currentSession,
            requestedMode = requestedMode,
            effectiveMode = resolvedEffectiveMode,
        )
        if (traceEnabled) {
            logger.warn(
                "问答请求证据快照: question=${question.trim()}, selectedNodeIds=${effectiveContext.selectedNodeIds}, " +
                    "sourceContext=${sourceContextSummaries(effectiveContext.sourceContext)}, " +
                    "evidenceTrace=${evidenceTraceSummaries(effectiveContext.evidenceTrace)}",
            )
        }
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(
                context = effectiveContext,
                question = question,
                prompt = promptPackage.preview,
                session = currentSession,
                sourceThreadId = sourceThreadId,
                requestedMode = requestedMode,
                effectiveMode = resolvedEffectiveMode,
            )
        }
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(
                context = effectiveContext,
                question = question,
                prompt = promptPackage.preview,
                session = currentSession,
                sourceThreadId = sourceThreadId,
                requestedMode = requestedMode,
                effectiveMode = resolvedEffectiveMode,
            ).copy(
                warnings = listOf(sanitized.remoteLlmSetupHint("本地规则问答")),
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "问答",
                schema = LlmStructuredSchemas.PATCH_RESULT,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                RemoteGraphPatchResultParser.parse(content, promptPackage.preview, question)
            }
        }.map { remote ->
            val remoteResult = remote.value.withPrependedWarnings(remote.warnings)
            if (traceEnabled) {
                logger.warn(
                    "问答结果进入归一化: source=${remoteResult.source}, findings=${remoteResult.findings.size}, " +
                        "candidateChanges=${remoteResult.candidateChanges.size}, investigationThreads=${remoteResult.investigationThreads.size}, " +
                        "patchOperations=${remoteResult.patch?.operations?.size ?: 0}, " +
                        "candidateSummaries=${candidateSummaries(remoteResult.candidateChanges)}",
                )
            }
            applyConversationTurn(
                base = remoteResult,
                context = effectiveContext,
                session = currentSession,
                sourceThreadId = sourceThreadId,
                requestedMode = requestedMode,
                effectiveMode = resolvedEffectiveMode,
            )
        }.getOrElse { error ->
            buildMockResult(
                context = effectiveContext,
                question = question,
                prompt = promptPackage.preview,
                session = currentSession,
                sourceThreadId = sourceThreadId,
                requestedMode = requestedMode,
                effectiveMode = resolvedEffectiveMode,
            ).copy(
                warnings = listOf(buildRemoteFallbackWarning("问答", error)),
            )
        }
    }

    /** 构造不依赖远程模型的本地问答结果。 */
    private fun buildMockResult(
        context: GraphAuditContext,
        question: String,
        prompt: String,
        session: AuditConversationSession,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
    ): GraphPatchResult {
        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        val analysisGraph = context.editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: context.factGraph
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
        val directSourceFindings = buildMockDirectSourceFindings(context)
        val directSourceTargets = resolveMockDirectSourceTargets(context, scopeNodes, analysisGraph)
        val canBuildCandidateChange = (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
            questionExplicitlyRequestsChange(question) &&
            directSourceFindings.isNotEmpty() &&
            directSourceTargets.isNotEmpty()
        val explanationAnswer = buildString {
            append("当前范围说明：").append(scopeLabel).append("。")
            if (scopeNodes.isNotEmpty()) {
                append("本轮主要涉及：")
                append(scopeNodes.joinToString(" -> ") { it.title.ifBlank { it.id } })
                append("。")
            }
            if (analysisGraph.edges.isNotEmpty()) {
                append("当前看到的调用/连接数量为 ").append(analysisGraph.edges.size).append("。")
            }
        }
        val answer = if (effectiveMode == QaMode.ANSWER) {
            explanationAnswer.ifBlank {
                "当前轮结论：当前证据不足以完整回答该问题；本轮不会生成候选变更或风险线程。"
            }
        } else if (canBuildCandidateChange) {
            """
            当前轮结论：$scopeLabel 已直接观察到可落点的源码证据，已生成待确认变更。
            处理建议：下一步应基于当前 edit scope 继续生成精确代码 diff，而不是退回风险线索。
            """.trimIndent()
        } else if (explanationIntent && !explicitAuditIntent && !hasFallbackIntent) {
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
        val findings = if (canBuildCandidateChange) {
            directSourceFindings
        } else {
            val findingClaim = if (explanationIntent && !explicitAuditIntent && !hasFallbackIntent) {
                "当前图里可以直接观察到该链路范围内的节点与连接关系。"
            } else if (hasFallbackIntent) {
                "当前上下文没有直接观察到默认兜底分支。"
            } else {
                "当前上下文没有直接观察到足以证明完整业务规则的证据。"
            }
            scopeNodes.ifEmpty { analysisGraph.nodes.take(1) }
                .distinctBy(GraphNode::id)
                .mapIndexed { index, node ->
                    ResultEvidenceFinding(
                        id = "audit-finding-$index",
                        claim = findingClaim,
                        evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                        references = listOf(ResultEvidenceReference(nodeId = node.id)),
                    )
                }
        }
        val candidateChanges = if (canBuildCandidateChange) {
            listOf(
                CandidateDraftChange(
                    changeId = buildMockCandidateChangeId(directSourceTargets),
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                    title = buildMockCandidateTitle(question, directSourceTargets),
                    targetNodeIds = directSourceTargets.map(GraphNode::id),
                    reason = "当前源码片段已直接锚定到本轮修改请求涉及的位置。",
                    impactSummary = "已具备直接源码证据，可继续进入精确代码 diff 生成。",
                    claimType = "CODE_FACT",
                    evidence = findings,
                ),
            )
        } else {
            emptyList()
        }
        val investigationThreads = if (
            effectiveMode == QaMode.ANSWER ||
            canBuildCandidateChange ||
            (explanationIntent && !explicitAuditIntent && !hasFallbackIntent)
        ) {
            emptyList()
        } else {
            listOf(
                InvestigationThread(
                    threadId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-audit-change", "audit-thread"),
                    status = InvestigationThreadStatus.OPEN,
                    title = if (hasFallbackIntent) "补充默认兜底规则" else "补充业务规则说明",
                    targetNodeIds = scopeNodes.ifEmpty { analysisGraph.nodes.take(1) }.map(GraphNode::id),
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
                requestedMode = requestedMode,
                effectiveMode = effectiveMode,
                answer = answer,
                promptPreview = prompt,
                findings = findings,
                candidateChanges = candidateChanges,
                investigationThreads = investigationThreads,
                sourceContext = context.sourceContext,
                evidenceTrace = context.evidenceTrace,
            ),
            context = context,
            session = session,
            sourceThreadId = sourceThreadId,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )
    }

    private fun buildMockDirectSourceFindings(
        context: GraphAuditContext,
    ): List<ResultEvidenceFinding> {
        return context.sourceContext
            .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
            .mapIndexed { index, snippet ->
                ResultEvidenceFinding(
                    id = "audit-direct-source-$index",
                    claim = "当前源码片段里已经直接定位到本轮修改请求涉及的实现位置。",
                    evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                    references = listOf(
                        ResultEvidenceReference(
                            nodeId = snippet.nodeId,
                            filePath = snippet.filePath,
                            startLine = snippet.startLine,
                            endLine = snippet.endLine,
                        ),
                    ),
                )
            }
    }

    private fun resolveMockDirectSourceTargets(
        context: GraphAuditContext,
        scopeNodes: List<GraphNode>,
        analysisGraph: GraphDocument,
    ): List<GraphNode> {
        val nodeById = analysisGraph.nodes.associateBy(GraphNode::id)
        val preferredNodeIds = (
            scopeNodes.map(GraphNode::id) +
                context.sourceContext.map(SourceSnippetContext::nodeId)
            ).distinct()
        return preferredNodeIds.mapNotNull(nodeById::get).ifEmpty {
            analysisGraph.nodes.take(1)
        }
    }

    private fun buildMockCandidateChangeId(targetNodes: List<GraphNode>): String {
        val scopeKey = targetNodes.joinToString(",") { it.id }.ifBlank { "scope" }
        return GraphNode.stableId(NodeType.DOC_PAGE, scopeKey, "mock-candidate-change")
    }

    private fun buildMockCandidateTitle(
        question: String,
        targetNodes: List<GraphNode>,
    ): String {
        val normalizedQuestion = question.trim().removeSuffix("。")
        val trimmedQuestion = normalizedQuestion.removePrefix("请").trim()
        if (trimmedQuestion.isNotBlank()) {
            return trimmedQuestion.take(64)
        }
        val nodeLabel = targetNodes.joinToString(" / ") { node -> node.title.ifBlank { node.id } }
        return "调整 $nodeLabel"
    }

    /** 把本轮回答和候选变更写入会话。 */
    private fun applyConversationTurn(
        base: GraphPatchResult,
        context: GraphAuditContext,
        session: AuditConversationSession,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
    ): GraphPatchResult {
        val rawCandidateChanges = base.candidateChanges.ifEmpty { deriveCandidateChanges(base.patch, base.findings) }
        val classification = classifyAuditOutputs(
            candidateChanges = rawCandidateChanges,
            explicitInvestigationThreads = base.investigationThreads,
            context = context,
            question = base.question,
            effectiveMode = effectiveMode,
            sourceThreadId = sourceThreadId,
        )
        if (traceEnabled) {
            logger.warn(
                "问答结果归一化完成: rawCandidateChanges=${rawCandidateChanges.size}, " +
                    "derivedFromPatch=${base.candidateChanges.isEmpty() && base.patch != null && rawCandidateChanges.isNotEmpty()}, " +
                    "classifiedCandidates=${classification.candidateChanges.size}, " +
                    "classifiedInvestigationThreads=${classification.investigationThreads.size}, " +
                    "candidateSummaries=${candidateSummaries(classification.candidateChanges)}, " +
                    "threadSummaries=${threadSummaries(classification.investigationThreads)}",
            )
        }
        val turnResult = auditConversationService.applyModelTurn(
            session = session,
            modelTurn = AuditModelTurn(
                answer = base.answer,
                candidateChanges = classification.candidateChanges,
                investigationThreads = classification.investigationThreads,
                sourceThreadId = sourceThreadId,
                observedNodeIds = (
                    context.sourceContext.map(SourceSnippetContext::nodeId) +
                        context.evidenceTrace.map(EvidenceTraceEntry::nodeId) +
                        classification.investigationThreads.flatMap(InvestigationThread::targetNodeIds) +
                        classification.candidateChanges.flatMap(CandidateDraftChange::targetNodeIds)
                    ).distinct(),
                observedFilePaths = (
                    context.sourceContext.map(SourceSnippetContext::filePath) +
                        context.evidenceTrace.map(EvidenceTraceEntry::filePath) +
                        classification.investigationThreads.flatMap { thread ->
                            thread.evidence.flatMap { finding ->
                                finding.references.mapNotNull(ResultEvidenceReference::filePath)
                            }
                        } +
                        classification.candidateChanges.flatMap { change ->
                            change.evidence.flatMap { finding ->
                                finding.references.mapNotNull(ResultEvidenceReference::filePath)
                            }
                        }
                    ).distinct(),
            ),
        )
        val sessionAfterBoundary = when (effectiveMode) {
            QaMode.ANSWER -> turnResult.session.copy(
                candidateChanges = emptyList(),
                investigationThreads = emptyList(),
                turnOutcomes = emptyList(),
                focusTargetId = null,
            )
            QaMode.REVIEW -> turnResult.session.copy(
                candidateChanges = emptyList(),
            )
            QaMode.INVESTIGATE -> turnResult.session.copy(
                candidateChanges = emptyList(),
                investigationThreads = turnResult.session.investigationThreads
                    .filter { thread -> sourceThreadId == null || thread.threadId == sourceThreadId },
                turnOutcomes = turnResult.session.turnOutcomes
                    .filter { outcome -> sourceThreadId == null || outcome.threadId == sourceThreadId },
                focusTargetId = sourceThreadId,
            )
            QaMode.CHANGE,
            QaMode.AUTO,
            -> turnResult.session
        }
        return base.copy(
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
            patch = null,
            candidateChanges = when (effectiveMode) {
                QaMode.CHANGE, QaMode.AUTO -> turnResult.session.candidateChanges
                else -> emptyList()
            },
            newCandidateChanges = when (effectiveMode) {
                QaMode.CHANGE, QaMode.AUTO -> turnResult.newCandidateChanges
                else -> emptyList()
            },
            investigationThreads = when (effectiveMode) {
                QaMode.ANSWER -> emptyList()
                QaMode.INVESTIGATE -> sessionAfterBoundary.investigationThreads
                else -> sessionAfterBoundary.investigationThreads
            },
            latestTurnOutcome = if (effectiveMode == QaMode.ANSWER) null else turnResult.latestTurnOutcome
                ?.takeIf { outcome -> effectiveMode != QaMode.INVESTIGATE || sourceThreadId == null || outcome.threadId == sourceThreadId },
            recentTurnOutcomes = if (effectiveMode == QaMode.ANSWER) {
                emptyList()
            } else if (effectiveMode == QaMode.INVESTIGATE && sourceThreadId != null) {
                turnResult.recentTurnOutcomes.filter { outcome -> outcome.threadId == sourceThreadId }
            } else {
                turnResult.recentTurnOutcomes
            },
            sourceContext = context.sourceContext,
            evidenceTrace = context.evidenceTrace,
            auditSession = sessionAfterBoundary,
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
                // 这里会直接透传到候选草稿区，文案需要与问答链路口径保持一致。
                reason = "由远程问答建议生成。",
                impactSummary = patch.summary ?: "",
                claimType = operation.metadata["draft.claimType"],
                evidence = findings,
                graphPatch = com.charmnight.linkgraph.model.GraphPatch(
                    summary = patch.summary,
                    operations = listOf(operation),
                    addedNodeIds = patch.addedNodeIds.filter { it == operation.elementId },
                    removedNodeIds = patch.removedNodeIds.filter { it == operation.elementId },
                    addedEdgeIds = patch.addedEdgeIds.filter { it == operation.elementId },
                    removedEdgeIds = patch.removedEdgeIds.filter { it == operation.elementId },
                ),
            )
        }
    }

    private fun classifyAuditOutputs(
        candidateChanges: List<CandidateDraftChange>,
        explicitInvestigationThreads: List<InvestigationThread>,
        context: GraphAuditContext,
        question: String,
        effectiveMode: QaMode,
        sourceThreadId: String?,
    ): ClassifiedAuditOutputs {
        val promotableChanges = mutableListOf<CandidateDraftChange>()
        val investigationThreads = linkedMapOf<String, InvestigationThread>()

        val candidateInput = if (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) {
            candidateChanges
        } else {
            emptyList()
        }
        normalizeCandidateChanges(candidateInput, context).forEach { change ->
            if (change.hasDirectEvidence()) {
                if (traceEnabled) {
                    logger.warn("问答候选变更保留为待确认项: ${GenerationDiagnostics.summarizeCandidateChange(change)}")
                }
                promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
            } else {
                val thread = threadFromWeakCandidateChange(change)
                if (traceEnabled) {
                    logger.warn(
                        "问答候选变更降级为线索: ${GenerationDiagnostics.summarizeCandidateChange(change)}, " +
                            "threadId=${thread.threadId}, strongestEvidence=${change.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE"}",
                    )
                }
                investigationThreads[thread.threadId] = thread
            }
        }
        val normalizedInvestigationThreads = normalizeInvestigationThreads(explicitInvestigationThreads)
        if (
            (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
            promotableChanges.isEmpty() &&
            questionExplicitlyRequestsChange(question)
        ) {
            promoteThreadsToCandidateChanges(normalizedInvestigationThreads, context).forEach { change ->
                if (traceEnabled) {
                    logger.warn(
                        "问答风险线程提升为待确认项: threadBackfill=${change.changeId}, " +
                            "question=${question.trim()}, " +
                            "candidate=${GenerationDiagnostics.summarizeCandidateChange(change)}",
                    )
                }
                promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
            }
        }
        normalizedInvestigationThreads
            .filter { thread -> effectiveMode != QaMode.ANSWER }
            .filter { thread -> effectiveMode != QaMode.INVESTIGATE || sourceThreadId == null || thread.threadId == sourceThreadId }
            .forEach { thread ->
            investigationThreads[thread.threadId] = thread
        }

        return ClassifiedAuditOutputs(
            candidateChanges = promotableChanges,
            investigationThreads = investigationThreads.values.toList(),
        )
    }

    private fun normalizeCandidateChanges(
        changes: List<CandidateDraftChange>,
        context: GraphAuditContext,
    ): List<CandidateDraftChange> {
        val candidateBaseGraph = GraphDocument(
            nodes = (context.editableGraph.nodes + context.factGraph.nodes).distinctBy(GraphNode::id),
            edges = (context.editableGraph.edges + context.factGraph.edges).distinctBy(GraphEdge::id),
        )
        return changes.mapNotNull { change ->
            val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
            if (normalizedEvidence.isEmpty()) {
                if (traceEnabled) {
                    logger.warn("问答候选变更被丢弃: changeId=${change.changeId}, reason=empty-evidence")
                }
                return@mapNotNull null
            }
            val normalizedCandidate = candidatePatchComposer.normalizeCandidate(
                candidate = change.copy(
                    claimType = change.claimType ?: inferClaimType(normalizedEvidence),
                    evidence = normalizedEvidence,
                    editScopes = change.editScopes.distinctBy(EditScope::scopeId),
                ),
                baseGraph = candidateBaseGraph,
            )
            if (traceEnabled) {
                logger.warn(
                    "问答候选变更完成归一化: ${GenerationDiagnostics.summarizeCandidateChange(normalizedCandidate)}, " +
                        "directEvidence=${normalizedCandidate.hasDirectEvidence()}, " +
                        "graphPatch=${GenerationDiagnostics.summarizeGraphPatch(normalizedCandidate.graphPatch)}",
                )
            }
            normalizedCandidate
        }
    }

    private fun deriveEditScopes(
        change: CandidateDraftChange,
        context: GraphAuditContext,
    ): List<EditScope> {
        val nodeById = (context.editableGraph.nodes + context.factGraph.nodes).distinctBy(GraphNode::id).associateBy(GraphNode::id)
        val sourceSnippetByNodeId = context.sourceContext.associateBy(SourceSnippetContext::nodeId)
        val supportingFindingIds = change.evidence.map(ResultEvidenceFinding::id)
        return change.targetNodeIds.mapNotNull { nodeId ->
            val node = nodeById[nodeId] ?: return@mapNotNull null
            val directReference = change.evidence.firstNotNullOfOrNull { finding ->
                finding.references.firstOrNull { reference ->
                    reference.nodeId == null || reference.nodeId == nodeId
                }
            }
            val snippet = sourceSnippetByNodeId[nodeId]
            val location = trustedEditScopePathResolver.resolve(
                node = node,
                snippet = snippet,
                reference = directReference,
            ) ?: return@mapNotNull null
            EditScope(
                scopeId = "scope-${change.changeId}-$nodeId",
                targetNodeId = nodeId,
                filePath = location.filePath,
                language = inferLanguage(location.filePath),
                symbolKind = node.type.name,
                symbolSignature = editableSymbolSignature(node),
                startOffset = location.startOffset,
                endOffset = location.endOffset,
                startLine = location.startLine,
                endLine = location.endLine,
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

    private fun editableSymbolSignature(node: GraphNode): String? {
        return when (node.type) {
            NodeType.FLOW_SCOPE, NodeType.FLOW_ACTION, NodeType.TERMINAL ->
                node.metadata["flow.ownerMethod"]
                    ?: node.metadata["flow.anchorMethod"]
                    ?: node.signature
            else -> node.signature
        }
    }

    private fun normalizeInvestigationThreads(threads: List<InvestigationThread>): List<InvestigationThread> {
        return threads.mapNotNull { thread ->
            val normalizedEvidence = thread.evidence.distinctBy(ResultEvidenceFinding::id)
            if (normalizedEvidence.isEmpty()) {
                return@mapNotNull null
            }
            thread.copy(
                claimType = thread.claimType ?: inferClaimType(normalizedEvidence),
                summary = thread.summary.ifBlank {
                    normalizedEvidence.firstOrNull()?.claim ?: thread.title
                },
                evidenceGap = thread.evidenceGap.ifBlank {
                    inferEvidenceGap(normalizedEvidence)
                },
                recommendedQuestion = thread.recommendedQuestion.ifBlank {
                    buildRecommendedQuestion(thread.title, normalizedEvidence)
                },
                evidence = normalizedEvidence,
            )
        }
    }

    private fun promoteThreadsToCandidateChanges(
        threads: List<InvestigationThread>,
        context: GraphAuditContext,
    ): List<CandidateDraftChange> {
        val promotedCandidates = threads
            .filter(::isEligibleForCandidatePromotion)
            .map(::candidateFromThread)
        return normalizeCandidateChanges(promotedCandidates, context)
            .filter { change -> change.hasDirectEvidence() }
    }

    private fun isEligibleForCandidatePromotion(thread: InvestigationThread): Boolean {
        return thread.status == InvestigationThreadStatus.OPEN &&
            (thread.targetNodeIds.isNotEmpty() || thread.targetStepIds.isNotEmpty()) &&
            thread.evidence.any { finding ->
                finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
                    finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
            }
    }

    private fun candidateFromThread(thread: InvestigationThread): CandidateDraftChange {
        val normalizedEvidence = thread.evidence.distinctBy(ResultEvidenceFinding::id)
        return CandidateDraftChange(
            changeId = promotedChangeIdForThread(thread.threadId),
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = promotedCandidateTitle(thread),
            targetStepIds = thread.targetStepIds,
            targetNodeIds = thread.targetNodeIds,
            beforeState = null,
            afterState = thread.summary.takeIf { it.isNotBlank() },
            reason = thread.summary.ifBlank { thread.evidenceGap },
            impactSummary = thread.evidenceGap.ifBlank { thread.recommendedQuestion },
            claimType = thread.claimType ?: inferClaimType(normalizedEvidence),
            evidence = normalizedEvidence,
        )
    }

    private fun promotedChangeIdForThread(threadId: String): String {
        return if (threadId.startsWith("thread-")) {
            "change-${threadId.removePrefix("thread-")}"
        } else {
            "change-$threadId"
        }
    }

    private fun promotedCandidateTitle(thread: InvestigationThread): String {
        val rawTitle = thread.title.trim()
        if (rawTitle.isNotBlank() && rawTitle != thread.threadId) {
            return rawTitle
        }
        val summary = thread.summary.trim()
        if (summary.isNotBlank()) {
            return summary
        }
        val recommendedQuestion = thread.recommendedQuestion.trim()
        if (recommendedQuestion.isNotBlank()) {
            return recommendedQuestion
        }
        return thread.threadId
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

    private fun threadFromWeakCandidateChange(change: CandidateDraftChange): InvestigationThread {
        val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
        return InvestigationThread(
            threadId = "thread-${change.changeId}",
            status = InvestigationThreadStatus.OPEN,
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

    private fun questionExplicitlyRequestsChange(question: String): Boolean {
        val normalizedQuestion = question.replace(Regex("\\s+"), "")
        if (normalizedQuestion.isBlank()) {
            return false
        }
        val imperativeMarkers = listOf(
            "请把",
            "请将",
            "改成",
            "改为",
            "调整成",
            "调整为",
            "修成",
            "修复成",
            "补上",
            "加上",
            "怎么改",
            "如何改",
            "写成待确认变更",
            "写成可编辑图",
            "生成代码diff",
            "生成diff",
            "输出diff",
            "给出diff",
        )
        if (imperativeMarkers.any(normalizedQuestion::contains)) {
            return true
        }
        if (Regex("^(请)?(直接)?(修改|调整|修正|修复|改|修|补|加|将)").containsMatchIn(normalizedQuestion)) {
            return true
        }
        val discussionMarkers = listOf(
            "为什么",
            "为何",
            "是否",
            "是不是",
            "哪里",
            "在哪",
            "解释",
            "介绍",
            "讲解",
            "确认",
            "分析",
            "说明",
        )
        if (discussionMarkers.any(normalizedQuestion::contains)) {
            return false
        }
        return false
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
            .ifBlank { (context.editableGraph.nodes.firstOrNull() ?: context.factGraph.nodes.firstOrNull())?.id ?: "graph" }
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
                    // 该理由会展示给后续 runtime / UI 消费方，必须明确这是本轮问答附带的源码证据。
                    reason = "本轮问答直接附带的源码片段",
                    startLine = snippet.startLine,
                    endLine = snippet.endLine,
                    includedInPrompt = true,
                )
            },
        )
    }

    private data class ClassifiedAuditOutputs(
        val candidateChanges: List<CandidateDraftChange>,
        val investigationThreads: List<InvestigationThread>,
    )

    private fun candidateSummaries(changes: List<CandidateDraftChange>): String {
        if (changes.isEmpty()) {
            return "[]"
        }
        return changes.take(3).joinToString(
            prefix = "[",
            postfix = if (changes.size > 3) ", ...]" else "]",
        ) { change ->
            buildString {
                append(change.changeId)
                append(':')
                append(change.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE")
                append(':')
                append(change.targetNodeIds.joinToString("|").ifBlank { "-" })
            }
        }
    }

    private fun threadSummaries(threads: List<InvestigationThread>): String {
        if (threads.isEmpty()) {
            return "[]"
        }
        return threads.take(3).joinToString(
            prefix = "[",
            postfix = if (threads.size > 3) ", ...]" else "]",
        ) { thread ->
            buildString {
                append(thread.threadId)
                append(':')
                append(thread.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE")
                append(':')
                append(thread.targetNodeIds.joinToString("|").ifBlank { "-" })
            }
        }
    }

    private fun sourceContextSummaries(sourceContext: List<SourceSnippetContext>): String {
        if (sourceContext.isEmpty()) {
            return "[]"
        }
        return sourceContext.take(4).joinToString(
            prefix = "[",
            postfix = if (sourceContext.size > 4) ", ...]" else "]",
        ) { snippet ->
            buildString {
                append(snippet.nodeId)
                append('@')
                append(snippet.filePath)
                snippet.startLine?.let { append(':').append(it) }
                snippet.endLine?.let { append('-').append(it) }
                append(" => ")
                append(
                    snippet.snippet
                        .orEmpty()
                        .lineSequence()
                        .joinToString(" \\n ") { it.trim() }
                        .take(220),
                )
            }
        }
    }

    private fun evidenceTraceSummaries(evidenceTrace: List<EvidenceTraceEntry>): String {
        if (evidenceTrace.isEmpty()) {
            return "[]"
        }
        return evidenceTrace.take(6).joinToString(
            prefix = "[",
            postfix = if (evidenceTrace.size > 6) ", ...]" else "]",
        ) { trace ->
            buildString {
                append(trace.nodeId)
                append('@')
                append(trace.filePath)
                trace.startLine?.let { append(':').append(it) }
                trace.endLine?.let { append('-').append(it) }
                append('#')
                append(trace.reason)
                append("#included=")
                append(trace.includedInPrompt)
            }
        }
    }
}
