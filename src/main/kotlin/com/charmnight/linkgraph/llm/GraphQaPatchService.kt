package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.application.port.GraphQaPatchPort
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
    private val gateway: LlmGateway = com.charmnight.linkgraph.llm.RoutingLlmGateway(),
    /** 负责维护会话与候选变更。 */
    private val qaConversationService: QaConversationService = QaConversationService(),
    /** 负责从本地可信上下文推导编辑作用域路径。 */
    private val trustedEditScopePathResolver: TrustedEditScopePathResolver = TrustedEditScopePathResolver(),
) : GraphQaPatchPort {
    private val logger = Logger.getInstance(GraphQaPatchService::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    /** 统一候选变更补丁归一化器。 */
    private val candidatePatchComposer = CandidateGraphPatchComposer()
    /** P2-1 引入：把候选变更 / 风险线程分类与编辑作用域派生收敛为独立类。 */
    private val classifier = com.charmnight.linkgraph.llm.qa.QaPatchClassifier(
        candidatePatchComposer = candidatePatchComposer,
        trustedEditScopePathResolver = trustedEditScopePathResolver,
        traceEnabled = traceEnabled,
        logger = logger,
    )
    /** P2-1 引入：本地规则化问答结果构造独立类。 */
    private val localRuleBuilder = com.charmnight.linkgraph.llm.qa.QaPatchLocalRuleBuilder()
    /** 负责处理结构化 JSON 响应与自动修复。 */
    private val responseSupport = RemoteStructuredResponseParser(gateway)

    /** 执行链路问答，必要时回退到本地规则结果。 */
    override fun answer(
        context: GraphQaContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: QaConversationSession?,
        sourceThreadId: String?,
        requestedMode: QaMode,
        effectiveMode: QaMode,
        onPreview: ((String, Boolean) -> Unit)?,
        runtimeEvidenceTrusted: Boolean,
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

    /** 构造不依赖远程模型的本地问答结果：通过 [localRuleBuilder] 构造 base，再走 applyConversationTurn 写入会话。 */
    private fun buildMockResult(
        context: GraphQaContext,
        question: String,
        prompt: String,
        session: QaConversationSession,
        sourceThreadId: String?,
        requestedMode: QaMode,
        effectiveMode: QaMode,
        runtimeEvidenceTrusted: Boolean,
    ): GraphPatchResult =
        applyConversationTurn(
            base = localRuleBuilder.build(
                context = context,
                question = question,
                prompt = prompt,
                sourceThreadId = sourceThreadId,
                requestedMode = requestedMode,
                effectiveMode = effectiveMode,
                runtimeEvidenceTrusted = runtimeEvidenceTrusted,
            ),
            context = context,
            session = session,
            sourceThreadId = sourceThreadId,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
        )
    /** 本地规则构造函数 buildMockResult / buildMockDirectSourceFindings / resolveMockDirectSourceTargets /
     *  buildMockCandidateTitle 已封装到 [localRuleBuilder]（QaPatchLocalRuleBuilder）。 */

    /** 把本轮回答和候选变更写入会话。 */
    private fun applyConversationTurn(
        base: GraphPatchResult,
        context: GraphQaContext,
        session: QaConversationSession,
        sourceThreadId: String?,
        requestedMode: QaMode,
        effectiveMode: QaMode,
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

    /** 当远程仍以补丁形式返回结果时，把每条操作转换为候选变更：详见顶层函数 deriveCandidateChanges。 */
    private fun deriveCandidateChanges(
        patch: com.charmnight.linkgraph.model.GraphPatch?,
        findings: List<ResultEvidenceFinding>,
    ): List<CandidateDraftChange> =
        com.charmnight.linkgraph.llm.qa.deriveCandidateChanges(patch, findings)

    /** 把候选变更与风险线程按证据强度分类：委托给 [classifier]（详见 QaPatchClassifier.classify）。 */
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
        val result = classifier.classify(
            candidateChanges = candidateChanges,
            explicitInvestigationThreads = explicitInvestigationThreads,
            context = context,
            question = question,
            source = source,
            runtimeEvidenceTrusted = runtimeEvidenceTrusted,
            effectiveMode = effectiveMode,
            sourceThreadId = sourceThreadId,
        )
        return ClassifiedQaOutputs(
            candidateChanges = result.candidateChanges,
            investigationThreads = result.investigationThreads,
        )
    }

    /** 判断当前结果是否具备进入"待确认候选变更"路径的资格：详见顶层函数 canUseConfirmableCandidatePath。 */
    private fun canUseConfirmableCandidatePath(
        source: LlmResultSource,
        runtimeEvidenceTrusted: Boolean,
    ): Boolean = com.charmnight.linkgraph.llm.qa.canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted)

    /** 候选变更归类函数 normalizeCandidateChanges / deriveEditScopes / promoteThreadsToCandidateChanges /
     *  inferLanguage / editableSymbolSignature 已封装到 [classifier]（QaPatchClassifier）。 */

    /** 归一化风险线程列表：丢弃无证据项，并补齐 claimType、summary、evidenceGap、recommendedQuestion 等字段。 */
    private fun normalizeInvestigationThreads(threads: List<InvestigationThread>): List<InvestigationThread> =
        com.charmnight.linkgraph.llm.qa.normalizeInvestigationThreads(threads)

    /** 判断风险线程是否可被提升为候选变更：详见顶层函数 isEligibleForCandidatePromotion。 */
    private fun isEligibleForCandidatePromotion(thread: InvestigationThread): Boolean =
        com.charmnight.linkgraph.llm.qa.isEligibleForCandidatePromotion(thread)

    /** 把风险线程转换为候选变更：详见顶层函数 candidateFromThread。 */
    private fun candidateFromThread(thread: InvestigationThread): CandidateDraftChange =
        com.charmnight.linkgraph.llm.qa.candidateFromThread(thread)

    /** 根据线程 ID 生成对应的候选变更 ID：详见顶层函数 promotedChangeIdForThread。 */
    private fun promotedChangeIdForThread(threadId: String): String =
        com.charmnight.linkgraph.llm.qa.promotedChangeIdForThread(threadId)

    /** 生成风险线程提升为候选变更后的展示标题：详见顶层函数 promotedCandidateTitle。 */
    private fun promotedCandidateTitle(thread: InvestigationThread): String =
        com.charmnight.linkgraph.llm.qa.promotedCandidateTitle(thread)

    /** 根据证据等级推断声明类型：详见顶层函数 inferClaimType。 */
    private fun inferClaimType(evidence: List<ResultEvidenceFinding>): String =
        com.charmnight.linkgraph.llm.qa.inferClaimType(evidence)

    /** 把证据不足的候选变更降级为风险线程：详见顶层函数 threadFromWeakCandidateChange。 */
    private fun threadFromWeakCandidateChange(change: CandidateDraftChange): InvestigationThread =
        com.charmnight.linkgraph.llm.qa.threadFromWeakCandidateChange(change)

    /** 根据证据等级推断当前证据缺口描述：详见顶层函数 inferEvidenceGap。 */
    private fun inferEvidenceGap(evidence: List<ResultEvidenceFinding>): String =
        com.charmnight.linkgraph.llm.qa.inferEvidenceGap(evidence)

    /** 生成下一轮推荐的追问问题：详见顶层函数 buildRecommendedQuestion。 */
    private fun buildRecommendedQuestion(
        title: String,
        evidence: List<ResultEvidenceFinding>,
    ): String = com.charmnight.linkgraph.llm.qa.buildRecommendedQuestion(title, evidence)

    /** 启发式判断用户问题是否明确要求修改代码：详见顶层函数 questionExplicitlyRequestsChange。 */
    private fun questionExplicitlyRequestsChange(question: String): Boolean =
        com.charmnight.linkgraph.llm.qa.questionExplicitlyRequestsChange(question)

    /** 把当前用户问题写入会话。 */
    private fun ensureUserQuestion(
        session: QaConversationSession,
        question: String,
    ): QaConversationSession = com.charmnight.linkgraph.llm.qa.ensureUserQuestion(session, question)

    /** 基于当前范围生成默认空会话：详见顶层函数 emptySession。 */
    private fun emptySession(context: GraphQaContext): QaConversationSession =
        com.charmnight.linkgraph.llm.qa.emptySession(context)

    /** 生成远程失败后的回退警告文案：详见顶层函数 buildRemoteFallbackWarning。 */
    private fun buildRemoteFallbackWarning(
        scene: String,
        error: Throwable,
    ): String = com.charmnight.linkgraph.llm.qa.buildRemoteFallbackWarning(scene, error)

    /** 把警告文本统一加上 RUNTIME 前缀：详见顶层函数 runtimeWarning。 */
    private fun runtimeWarning(warning: String): String =
        com.charmnight.linkgraph.llm.qa.runtimeWarning(warning)

    /** 把远程返回的警告插到结果前面：详见顶层函数 withPrependedWarnings（已导入）。 */
    /** 把源码片段补充为取证轨迹条目：详见顶层函数 withDerivedEvidenceTrace（已导入）。 */

    /** 问答结果归一化过程中产生的内部结构，包含分类后的候选变更与风险线程。 */
    private data class ClassifiedQaOutputs(
        val candidateChanges: List<CandidateDraftChange>,
        val investigationThreads: List<InvestigationThread>,
    )

    /** 生成候选变更的简短摘要字符串，用于跟踪日志输出。 */
    private fun candidateSummaries(changes: List<CandidateDraftChange>): String =
        com.charmnight.linkgraph.llm.qa.candidateSummaries(changes)

    /** 生成风险线程的简短摘要字符串，用于跟踪日志输出。 */
    private fun threadSummaries(threads: List<InvestigationThread>): String =
        com.charmnight.linkgraph.llm.qa.threadSummaries(threads)

    /** 生成源码片段的简短摘要字符串，用于跟踪日志输出。 */
    private fun sourceContextSummaries(sourceContext: List<SourceSnippetContext>): String =
        com.charmnight.linkgraph.llm.qa.sourceContextSummaries(sourceContext)

    /** 生成取证轨迹的简短摘要字符串，用于跟踪日志输出。 */
    private fun evidenceTraceSummaries(evidenceTrace: List<EvidenceTraceEntry>): String =
        com.charmnight.linkgraph.llm.qa.evidenceTraceSummaries(evidenceTrace)
}
