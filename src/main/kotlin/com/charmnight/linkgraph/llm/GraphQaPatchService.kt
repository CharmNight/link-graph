package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.llm.qa.withDerivedEvidenceTrace
import com.charmnight.linkgraph.llm.qa.withPrependedWarnings
import com.charmnight.linkgraph.model.diagnostics.GraphPatchDiagnostics
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.CandidateDraftDiagnostics
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationService
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaModelTurn
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
class GraphQaPatchService(
    /** 负责构造问答提示词。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程 LLM 请求。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 负责维护会话与候选变更。 */
    private val qaConversationService: QaConversationService = QaConversationService(),
    /** 负责从本地可信上下文推导 edit scope 路径。 */
    private val trustedEditScopePathResolver: TrustedEditScopePathResolver = TrustedEditScopePathResolver(),
) {
    private val logger = Logger.getInstance(GraphQaPatchService::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseParser(gateway)
    /** 统一候选变更 patch 归一化器。 */
    private val candidatePatchComposer = CandidateGraphPatchComposer()

    /** 执行链路问答，必要时回退到本地规则结果。 */
    fun answer(
        context: GraphQaContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: QaConversationSession? = null,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
        onPreview: ((String, Boolean) -> Unit)? = null,
        runtimeEvidenceTrusted: Boolean = false,
    ): GraphPatchResult {
        val effectiveContext = context.withDerivedEvidenceTrace()
        val sanitized = settings.sanitized()
        val currentSession = ensureUserQuestion(session ?: emptySession(effectiveContext), question)
        val resolvedEffectiveMode = effectiveMode
        val promptPackage = promptFactory.buildQaPromptPackage(
            effectiveContext,
            question,
            sanitized,
            currentSession,
            requestedMode = requestedMode,
            effectiveMode = resolvedEffectiveMode,
        )
        if (traceEnabled) {
            // sourceContext 可能含真实源码（包括密钥行）；用 redactForTrace 过滤疑似密钥行后再写日志。
            logger.warn(
                redactForTrace(
                    "问答请求证据快照: question=${question.trim()}, selectedNodeIds=${effectiveContext.selectedNodeIds}, " +
                        "sourceContext=${sourceContextSummaries(effectiveContext.sourceContext)}, " +
                        "evidenceTrace=${evidenceTraceSummaries(effectiveContext.evidenceTrace)}",
                ),
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
                runtimeEvidenceTrusted = runtimeEvidenceTrusted,
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
                runtimeEvidenceTrusted = runtimeEvidenceTrusted,
            ).copy(
                warnings = listOf(runtimeWarning(sanitized.remoteLlmSetupHint("本地规则问答"))),
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
            val remoteResult = remote.value.withPrependedWarnings(remote.warnings.map(::runtimeWarning))
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
                runtimeEvidenceTrusted = false,
            ).copy(
                warnings = listOf(buildRemoteFallbackWarning("问答", error)),
            )
        }
    }

    /** 构造不依赖远程模型的本地问答结果。 */
    private fun buildMockResult(
        context: GraphQaContext,
        question: String,
        prompt: String,
        session: QaConversationSession,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
        runtimeEvidenceTrusted: Boolean = false,
    ): GraphPatchResult {
        val scopeNodes = GraphQaScopeResolver.resolveScopeNodes(context)
        val analysisGraph = context.editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: context.factGraph
        val hasFallbackIntent = question.contains("兜底") || question.contains("默认")
        val explanationIntent = question.contains("介绍") || question.contains("解释") || question.contains("讲解")
        val explicitQaIntent = question.contains("复核")
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
        val hasLocalRuleChangeHint = (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
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
        } else if (hasLocalRuleChangeHint) {
            """
            当前轮结论：本地规则在 $scopeLabel 已直接观察到可落点的源码证据，但不会生成待确认变更。
            处理建议：先登记为风险线索；需要可确认变更时，请使用远程模型或 runtime 证据链生成结构化候选变更。
            """.trimIndent()
        } else if (explanationIntent && !explicitQaIntent && !hasFallbackIntent) {
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
        val findings = if (hasLocalRuleChangeHint) {
            directSourceFindings
        } else {
            val findingClaim = if (explanationIntent && !explicitQaIntent && !hasFallbackIntent) {
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
                        id = "qa-finding-$index",
                        claim = findingClaim,
                        evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                        references = listOf(ResultEvidenceReference(nodeId = node.id)),
                    )
                }
        }
        val candidateChanges = if (hasLocalRuleChangeHint && runtimeEvidenceTrusted) {
            listOf(
                CandidateDraftChange(
                    changeId = GraphNode.stableId(NodeType.DOC_PAGE, directSourceTargets.joinToString(",") { it.id }, "runtime-candidate-change"),
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                    title = buildMockCandidateTitle(question, directSourceTargets),
                    targetNodeIds = directSourceTargets.map(GraphNode::id),
                    reason = "runtime 已读取直接源码证据并锚定到本轮修改请求涉及的位置。",
                    impactSummary = "已具备 runtime 代码证据，可继续进入精确代码 diff 生成。",
                    claimType = "CODE_FACT",
                    evidence = findings,
                ),
            )
        } else {
            emptyList()
        }
        val investigationThreads = if (
            effectiveMode == QaMode.ANSWER ||
            (hasLocalRuleChangeHint && runtimeEvidenceTrusted) ||
            (explanationIntent && !explicitQaIntent && !hasFallbackIntent)
        ) {
            emptyList()
        } else {
            listOf(
                InvestigationThread(
                    threadId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-qa-change", "qa-thread"),
                    status = InvestigationThreadStatus.OPEN,
                    title = if (hasLocalRuleChangeHint) {
                        buildMockCandidateTitle(question, directSourceTargets)
                    } else if (hasFallbackIntent) {
                        "补充默认兜底规则"
                    } else {
                        "补充业务规则说明"
                    },
                    targetNodeIds = scopeNodes.ifEmpty { analysisGraph.nodes.take(1) }.map(GraphNode::id),
                    summary = if (hasLocalRuleChangeHint) {
                        "本地规则只确认当前源码片段与修改请求相关，不能直接生成待确认变更。"
                    } else if (hasFallbackIntent) {
                        "当前还不能证明默认兜底逻辑存在或不存在，需要继续核对条件未命中时的处理分支。"
                    } else {
                        "当前还不能证明这条业务规则真实存在，需要继续核对相关源码或图节点。"
                    },
                    evidenceGap = if (hasLocalRuleChangeHint) {
                        "缺少远程模型或 runtime 结构化候选变更结果。"
                    } else if (hasFallbackIntent) {
                        "目前没有直接看到条件未命中后的处理分支。"
                    } else {
                        "目前没有直接看到足以证明完整业务规则的源码或图事实。"
                    },
                    recommendedQuestion = if (hasLocalRuleChangeHint) {
                        "请基于当前直接源码证据生成结构化候选变更，并通过本地 edit scope 校验。"
                    } else if (hasFallbackIntent) {
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
                source = LlmResultSource.LOCAL_RULE,
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
            ).let { result ->
                if (runtimeEvidenceTrusted) result.markRuntimeEvidenceTrusted() else result
            },
            context = context,
            session = session,
            sourceThreadId = sourceThreadId,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )
    }

    /** 把问答上下文中已有的源码片段转换为直接源码证据结论，用于本地规则化场景的快速证据补齐。 */
    private fun buildMockDirectSourceFindings(
        context: GraphQaContext,
    ): List<ResultEvidenceFinding> {
        return context.sourceContext
            .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
            .mapIndexed { index, snippet ->
                ResultEvidenceFinding(
                    id = "qa-direct-source-$index",
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

    /** 解析本地规则化场景下候选变更应当落到的目标节点列表。 */
    private fun resolveMockDirectSourceTargets(
        context: GraphQaContext,
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

    /** 根据用户问题或目标节点标题生成本地规则化候选变更的标题。 */
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
        context: GraphQaContext,
        session: QaConversationSession,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
    ): GraphPatchResult {
        val rawCandidateChanges = base.candidateChanges.ifEmpty { deriveCandidateChanges(base.patch, base.findings) }
        val classification = classifyQaOutputs(
            candidateChanges = rawCandidateChanges,
            explicitInvestigationThreads = base.investigationThreads,
            context = context,
            question = base.question,
            source = base.source,
            runtimeEvidenceTrusted = base.hasRuntimeEvidenceTrustedMarker(),
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
        val turnResult = qaConversationService.applyModelTurn(
            session = session,
            modelTurn = QaModelTurn(
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
            qaSession = sessionAfterBoundary,
        )
    }

    /** 当远程仍返回 patch 结构时，兜底转换为候选变更。 */
    /** 当远程仍以 patch 形式返回结果时，把每条 operation 转换为候选变更，便于统一后续归一化流程。 */
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

    /** 把候选变更与风险线程按证据强度分类：直接证据充足的提升为待确认项，证据不足的降级为风险线程。 */
    private fun classifyQaOutputs(
        candidateChanges: List<CandidateDraftChange>,
        explicitInvestigationThreads: List<InvestigationThread>,
        context: GraphQaContext,
        question: String,
        source: LlmResultSource,
        runtimeEvidenceTrusted: Boolean,
        effectiveMode: QaMode,
        sourceThreadId: String?,
    ): ClassifiedQaOutputs {
        val promotableChanges = mutableListOf<CandidateDraftChange>()
        val investigationThreads = linkedMapOf<String, InvestigationThread>()

        val candidateInput = if (
            canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted) &&
            (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO)
        ) {
            candidateChanges
        } else {
            emptyList()
        }
        normalizeCandidateChanges(candidateInput, context).forEach { change ->
            if (change.hasDirectEvidence()) {
                if (traceEnabled) {
                    logger.warn("问答候选变更保留为待确认项: ${CandidateDraftDiagnostics.summarizeCandidateChange(change)}")
                }
                promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
            } else {
                val thread = threadFromWeakCandidateChange(change)
                if (traceEnabled) {
                    logger.warn(
                        "问答候选变更降级为线索: ${CandidateDraftDiagnostics.summarizeCandidateChange(change)}, " +
                            "threadId=${thread.threadId}, strongestEvidence=${change.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE"}",
                    )
                }
                investigationThreads[thread.threadId] = thread
            }
        }
        val normalizedInvestigationThreads = normalizeInvestigationThreads(explicitInvestigationThreads)
        if (
            canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted) &&
            (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
            promotableChanges.isEmpty() &&
            questionExplicitlyRequestsChange(question)
        ) {
            promoteThreadsToCandidateChanges(normalizedInvestigationThreads, context).forEach { change ->
                if (traceEnabled) {
                    logger.warn(
                        "问答风险线程提升为待确认项: threadBackfill=${change.changeId}, " +
                            "question=${question.trim()}, " +
                            "candidate=${CandidateDraftDiagnostics.summarizeCandidateChange(change)}",
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

        return ClassifiedQaOutputs(
            candidateChanges = promotableChanges,
            investigationThreads = investigationThreads.values.toList(),
        )
    }

    /** 判断当前结果是否具备进入"待确认候选变更"路径的资格：详见 top-level fun canUseConfirmableCandidatePath。 */
    private fun canUseConfirmableCandidatePath(
        source: LlmResultSource,
        runtimeEvidenceTrusted: Boolean,
    ): Boolean = com.charmnight.linkgraph.llm.qa.canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted)

    /** 归一化候选变更列表：去重证据、丢弃无证据项、补充 claimType 与 editScopes 等。 */
    private fun normalizeCandidateChanges(
        changes: List<CandidateDraftChange>,
        context: GraphQaContext,
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
                    "问答候选变更完成归一化: ${CandidateDraftDiagnostics.summarizeCandidateChange(normalizedCandidate)}, " +
                        "directEvidence=${normalizedCandidate.hasDirectEvidence()}, " +
                        "graphPatch=${GraphPatchDiagnostics.summarizeGraphPatch(normalizedCandidate.graphPatch)}",
                )
            }
            normalizedCandidate
        }
    }

    /** 基于候选变更的目标节点与源码片段，推导出精确的 edit scope 列表。 */
    private fun deriveEditScopes(
        change: CandidateDraftChange,
        context: GraphQaContext,
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

    /** 根据文件扩展名推断语言种类，未识别时回退为 TEXT。 */
    private fun inferLanguage(filePath: String): String =
        com.charmnight.linkgraph.llm.qa.inferLanguage(filePath)

    /** 返回可编辑符号签名：流程类节点优先从元数据取所属方法签名，其他节点直接返回 signature 字段。 */
    private fun editableSymbolSignature(node: GraphNode): String? =
        com.charmnight.linkgraph.llm.qa.editableSymbolSignature(node)

    /** 归一化风险线程列表：丢弃无证据项，并补齐 claimType、summary、evidenceGap、recommendedQuestion 等字段。 */
    private fun normalizeInvestigationThreads(threads: List<InvestigationThread>): List<InvestigationThread> =
        com.charmnight.linkgraph.llm.qa.normalizeInvestigationThreads(threads)

    /** 当本轮没有候选变更但用户明确要求修改时，把满足条件的风险线程提升为候选变更。 */
    private fun promoteThreadsToCandidateChanges(
        threads: List<InvestigationThread>,
        context: GraphQaContext,
    ): List<CandidateDraftChange> {
        val promotedCandidates = threads
            .filter(::isEligibleForCandidatePromotion)
            .map(::candidateFromThread)
        return normalizeCandidateChanges(promotedCandidates, context)
            .filter { change -> change.hasDirectEvidence() }
    }

    /** 判断风险线程是否可被提升为候选变更：详见 top-level fun isEligibleForCandidatePromotion。 */
    private fun isEligibleForCandidatePromotion(thread: InvestigationThread): Boolean =
        com.charmnight.linkgraph.llm.qa.isEligibleForCandidatePromotion(thread)

    /** 把风险线程转换为候选变更：详见 top-level fun candidateFromThread。 */
    private fun candidateFromThread(thread: InvestigationThread): CandidateDraftChange =
        com.charmnight.linkgraph.llm.qa.candidateFromThread(thread)

    /** 根据线程 ID 生成对应的候选变更 ID：详见 top-level fun promotedChangeIdForThread。 */
    private fun promotedChangeIdForThread(threadId: String): String =
        com.charmnight.linkgraph.llm.qa.promotedChangeIdForThread(threadId)

    /** 生成风险线程提升为候选变更后的展示标题：详见 top-level fun promotedCandidateTitle。 */
    private fun promotedCandidateTitle(thread: InvestigationThread): String =
        com.charmnight.linkgraph.llm.qa.promotedCandidateTitle(thread)

    /** 根据证据等级推断声明类型：详见 top-level fun inferClaimType。 */
    private fun inferClaimType(evidence: List<ResultEvidenceFinding>): String =
        com.charmnight.linkgraph.llm.qa.inferClaimType(evidence)

    /** 把证据不足的候选变更降级为风险线程：详见 top-level fun threadFromWeakCandidateChange。 */
    private fun threadFromWeakCandidateChange(change: CandidateDraftChange): InvestigationThread =
        com.charmnight.linkgraph.llm.qa.threadFromWeakCandidateChange(change)

    /** 根据证据等级推断当前证据缺口描述：详见 top-level fun inferEvidenceGap。 */
    private fun inferEvidenceGap(evidence: List<ResultEvidenceFinding>): String =
        com.charmnight.linkgraph.llm.qa.inferEvidenceGap(evidence)

    /** 生成下一轮推荐的追问问题：详见 top-level fun buildRecommendedQuestion。 */
    private fun buildRecommendedQuestion(
        title: String,
        evidence: List<ResultEvidenceFinding>,
    ): String = com.charmnight.linkgraph.llm.qa.buildRecommendedQuestion(title, evidence)

    /** 启发式判断用户问题是否明确要求修改代码：详见 top-level fun questionExplicitlyRequestsChange。 */
    private fun questionExplicitlyRequestsChange(question: String): Boolean =
        com.charmnight.linkgraph.llm.qa.questionExplicitlyRequestsChange(question)

    /** 把当前用户问题写入会话。 */
    private fun ensureUserQuestion(
        session: QaConversationSession,
        question: String,
    ): QaConversationSession = com.charmnight.linkgraph.llm.qa.ensureUserQuestion(session, question)

    /** 基于当前范围生成默认空会话：详见 top-level fun emptySession。 */
    private fun emptySession(context: GraphQaContext): QaConversationSession =
        com.charmnight.linkgraph.llm.qa.emptySession(context)

    /** 生成远程失败后的回退警告文案：详见 top-level fun buildRemoteFallbackWarning。 */
    private fun buildRemoteFallbackWarning(
        scene: String,
        error: Throwable,
    ): String = com.charmnight.linkgraph.llm.qa.buildRemoteFallbackWarning(scene, error)

    /** 把警告文本统一加上 RUNTIME 前缀：详见 top-level fun runtimeWarning。 */
    private fun runtimeWarning(warning: String): String =
        com.charmnight.linkgraph.llm.qa.runtimeWarning(warning)

    /** 把远程返回的警告插到结果前面：详见 top-level fun withPrependedWarnings（已 import）。 */
    /** 把源码片段补充为取证轨迹条目：详见 top-level fun withDerivedEvidenceTrace（已 import）。 */

    /** 问答结果归一化过程中产生的内部结构，包含分类后的候选变更与风险线程。 */
    private data class ClassifiedQaOutputs(
        val candidateChanges: List<CandidateDraftChange>,
        val investigationThreads: List<InvestigationThread>,
    )

    /** 生成候选变更的简短摘要字符串，用于 trace 日志输出。 */
    private fun candidateSummaries(changes: List<CandidateDraftChange>): String =
        com.charmnight.linkgraph.llm.qa.candidateSummaries(changes)

    /** 生成风险线程的简短摘要字符串，用于 trace 日志输出。 */
    private fun threadSummaries(threads: List<InvestigationThread>): String =
        com.charmnight.linkgraph.llm.qa.threadSummaries(threads)

    /** 生成源码片段的简短摘要字符串，用于 trace 日志输出。 */
    private fun sourceContextSummaries(sourceContext: List<SourceSnippetContext>): String =
        com.charmnight.linkgraph.llm.qa.sourceContextSummaries(sourceContext)

    /** 生成取证轨迹的简短摘要字符串，用于 trace 日志输出。 */
    private fun evidenceTraceSummaries(evidenceTrace: List<EvidenceTraceEntry>): String =
        com.charmnight.linkgraph.llm.qa.evidenceTraceSummaries(evidenceTrace)
}
