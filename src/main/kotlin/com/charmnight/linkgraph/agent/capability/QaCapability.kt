package com.charmnight.linkgraph.agent.capability

import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.llm.GraphQaScopeResolver
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.EvidenceTraceEntry
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.agent.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.agent.artifact.GraphSummaryArtifact
import com.charmnight.linkgraph.agent.artifact.QaEvidenceTraceArtifact
import com.charmnight.linkgraph.agent.artifact.QaConclusionArtifact
import com.charmnight.linkgraph.agent.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.agent.runtime.AgentRunPhase
import com.charmnight.linkgraph.agent.runtime.AgentRunState
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeDeadlineExceededException
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.agent.runtime.AgentStepExecutionResult
import com.charmnight.linkgraph.agent.runtime.AgentStepRecord
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.agent.runtime.StepExecutor
import com.charmnight.linkgraph.agent.runtime.StopPolicy
import com.charmnight.linkgraph.agent.runtime.failureReasonBeforeNextFileRead
import com.charmnight.linkgraph.agent.runtime.withConfiguredRuntimeTimeout
import com.charmnight.linkgraph.agent.runtime.withRuntimeDeadlineTimeout
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.llm.tools.AgentTool
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.BuildReviewEvidenceBundleTool
import com.charmnight.linkgraph.llm.tools.CodeReadToolFacade
import com.charmnight.linkgraph.llm.tools.CreateCandidateDraftTool
import com.charmnight.linkgraph.llm.tools.AffectedProjectNodesTool
import com.charmnight.linkgraph.llm.tools.ExplainProjectNodeTool
import com.charmnight.linkgraph.llm.tools.ExploreProjectContextTool
import com.charmnight.linkgraph.llm.tools.FindRelatedTestsTool
import com.charmnight.linkgraph.llm.tools.FindJvmRelationsTool
import com.charmnight.linkgraph.llm.tools.FindJvmSymbolTool
import com.charmnight.linkgraph.llm.tools.FindProjectPathTool
import com.charmnight.linkgraph.llm.tools.FindProxyTargetsTool
import com.charmnight.linkgraph.llm.tools.FindReflectionTargetsTool
import com.charmnight.linkgraph.llm.tools.FindServiceProvidersTool
import com.charmnight.linkgraph.llm.tools.GetBlastRadiusTool
import com.charmnight.linkgraph.llm.tools.GetArchitectureIndexSummaryTool
import com.charmnight.linkgraph.llm.tools.GetChangedSymbolsTool
import com.charmnight.linkgraph.llm.tools.GetCurrentGraphTool
import com.charmnight.linkgraph.llm.tools.GetDraftWorkbenchTool
import com.charmnight.linkgraph.llm.tools.GetProjectIndexDigestTool
import com.charmnight.linkgraph.llm.tools.GetSelectedScopeTool
import com.charmnight.linkgraph.llm.tools.GraphToolFacade
import com.charmnight.linkgraph.llm.tools.QueryProjectGraphTool
import com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool
import com.charmnight.linkgraph.llm.tools.ReadSymbolTool
import com.charmnight.linkgraph.llm.tools.QueryArchitectureRelationsTool
import com.charmnight.linkgraph.llm.tools.ResolveAnchorTool
import com.charmnight.linkgraph.agent.tools.ToolExecutionContext
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.llm.tools.DraftToolFacade
import com.charmnight.linkgraph.llm.tools.EditGraphTool
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.sourceFilePathOrLocationPath
import com.charmnight.linkgraph.model.sourceLocation
import java.util.ArrayDeque
import java.util.UUID

/**
 * 第一版问答 capability。
 * runtime 负责读取图与代码证据，最终问答由正式执行器完成。
 */
class QaCapability(
    /** 默认运行预算。 */
    private val defaultBudget: RunBudget = RunBudget(),
    /** 正式问答执行器。 */
    private val qaExecutor: QaExecutor,
    /** capability 可用工具注册表。 */
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        listOf<AgentTool>(
            GetDraftWorkbenchTool(DraftToolFacade()),
            GetCurrentGraphTool(GraphToolFacade()),
            EditGraphTool(),
            GetSelectedScopeTool(GraphToolFacade()),
            ResolveAnchorTool(CodeReadToolFacade()),
            ReadSourceSnippetTool(CodeReadToolFacade()),
            ReadSymbolTool(CodeReadToolFacade()),
            GetArchitectureIndexSummaryTool(),
            FindJvmSymbolTool(),
            ExploreProjectContextTool(),
            QueryProjectGraphTool(),
            FindProjectPathTool(),
            ExplainProjectNodeTool(),
            AffectedProjectNodesTool(),
            GetProjectIndexDigestTool(),
            FindJvmRelationsTool(),
            QueryArchitectureRelationsTool(),
            FindServiceProvidersTool(),
            FindReflectionTargetsTool(),
            FindProxyTargetsTool(),
            GetChangedSymbolsTool(),
            GetBlastRadiusTool(),
            FindRelatedTestsTool(),
            BuildReviewEvidenceBundleTool(),
            CreateCandidateDraftTool(),
        ),
    ),
) : AgentCapability<QaCapabilityInput, GraphPatchResult> {
    private val wholeGraphEvidenceTargetLimit: Int = 5
    private val explicitSelectionTraversalDepth: Int = 2

    /** capability 稳定标识，外部通过该字符串识别问答能力。 */
    override val capabilityId: String = "qa"

    /** 构造问答运行的初始状态，包含运行 ID、用户问题与预算。 */
    override fun buildInitialState(
        input: QaCapabilityInput,
        runtimeContext: AgentRuntimeContext,
    ): AgentRunState {
        return AgentRunState(
            runId = "qa-${UUID.randomUUID()}",
            capabilityId = capabilityId,
            phase = AgentRunPhase.CREATED,
            userGoal = input.question,
            budget = defaultBudget.withConfiguredRuntimeTimeout(input.settings),
            stepIndex = 0,
            artifactRefs = emptyList(),
        )
    }

    /** 按问答模式动态决定可用工具集合：基础工具默认开放，review 类工具与图变更工具按需开放。 */
    override fun allowedTools(input: QaCapabilityInput): Set<String> {
        val registeredTools = toolRegistry.names()
        val tools = registeredTools
            .filterTo(linkedSetOf()) { toolName -> toolName !in REVIEW_TOOL_NAMES && toolName !in GRAPH_MUTATION_TOOL_NAMES }
        if (input.effectiveMode in REVIEW_TOOL_MODES || input.requestedMode in REVIEW_TOOL_MODES) {
            tools += registeredTools.filter { toolName -> toolName in REVIEW_TOOL_NAMES }
        }
        if (input.effectiveMode in GRAPH_MUTATION_TOOL_MODES || input.requestedMode in GRAPH_MUTATION_TOOL_MODES) {
            tools += registeredTools.filter { toolName -> toolName in GRAPH_MUTATION_TOOL_NAMES }
        }
        return tools
    }

    /** 返回停止策略；关闭证据读取预算触发停止，避免读取到一半被中断。 */
    override fun stopPolicy(input: QaCapabilityInput): StopPolicy =
        StopPolicy.default().copy(stopWhenEvidenceReadBudgetReached = false)

    /** 从运行末态的 QaConclusionArtifact 中取出最终问答结果，缺失则视为运行异常。 */
    override fun finalize(
        runState: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): GraphPatchResult {
        val qaArtifact = runState.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<QaConclusionArtifact>()
            .lastOrNull()
            ?: error("问答运行结束时缺少 QaConclusionArtifact")
        return qaArtifact.result
    }

    /** 创建分步执行器：依次读取草稿、收集图摘要、按需读取代码证据，最后执行真实问答。 */
    override fun createStepExecutor(input: QaCapabilityInput): StepExecutor {
        return StepExecutor { state, runtimeContext ->
            when (state.stepIndex) {
                0 -> readDraftWorkbench(state, runtimeContext)
                1 -> collectGraphSummary(state, runtimeContext, input)
                2 -> collectCodeEvidenceIfNeeded(state, runtimeContext, input)
                else -> executeQaStep(state, runtimeContext, input)
            }
        }
    }

    /** 第 0 步：读取草稿工作台边界，作为后续证据读取的起点。 */
    private fun readDraftWorkbench(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.FAILED,
                    summary = "read-draft-workbench",
                    toolName = "get_draft_workbench",
                ),
                lastModelOutput = "缺少编辑器快照，无法读取草稿边界。",
            ),
        )
        val result = toolRegistry.require("get_draft_workbench").invoke(
            input = emptyMap(),
            context = runtimeContext.toolExecutionContext(
                snapshot = snapshot,
                runBudget = state.budget,
            ),
        )
        val candidateCount = result.payload["candidateCount"] as? Int ?: 0
        val confirmedCount = result.payload["confirmedCount"] as? Int ?: 0
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "read-draft-workbench",
                    toolName = result.toolName,
                ),
                lastModelOutput = "已读取草稿边界：candidate=$candidateCount, confirmed=$confirmedCount。",
            ),
        )
    }

    /** 直接执行问答的便捷入口，跳过分步 runtime，主要用于测试或上层直调。 */
    fun executeQa(
        input: QaCapabilityInput,
        runtimeContext: AgentRuntimeContext,
        state: AgentRunState = buildInitialState(input, runtimeContext),
    ): GraphPatchResult {
        return qaExecutor.invoke(input, runtimeContext, state)
    }

    /**
     * 第一步先读取图摘要，确保 runtime 主链路已经具备按需读取图的能力。
     */
    private fun collectGraphSummary(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: QaCapabilityInput,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.FAILED,
                    summary = "read-graph-summary",
                    toolName = "get_current_graph",
                    nodeId = input.qaContext.selectedNodeIds.firstOrNull(),
                ),
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少图快照，无法继续问答。",
            ),
        )
        val toolContext = runtimeContext.toolExecutionContext(
            snapshot = snapshot,
            runBudget = state.budget,
        )
        val selectedScopeResult = toolRegistry.require("get_selected_scope").invoke(
            input = mapOf("selectedNodeIds" to input.qaContext.selectedNodeIds),
            context = toolContext,
        )
        val graphResult = toolRegistry.require("get_current_graph").invoke(emptyMap(), toolContext)
        val graph = graphResult.payload["graph"] as? GraphDocument ?: GraphDocument()
        @Suppress("UNCHECKED_CAST")
        val selectedNodeIds = selectedScopeResult.payload["selectedNodeIds"] as? List<String>
            ?: (graphResult.payload["selectedNodeIds"] as? List<String> ?: emptyList())
        if (graph.nodes.isEmpty()) {
            return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "read-graph-summary",
                        toolName = graphResult.toolName,
                        nodeId = selectedNodeIds.firstOrNull(),
                    ),
                    failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                    lastModelOutput = "当前工作台没有可供问答的图节点，无法继续执行。",
                ),
            )
        }
        val artifactRef = runtimeContext.artifactStore.save(
            GraphSummaryArtifact(
                artifactId = "${state.runId}-graph-summary-${state.stepIndex}",
                graph = graph,
                selectedNodeIds = selectedNodeIds,
                graphSource = graphResult.payload["graphSource"]?.toString() ?: "unknown",
            ),
        )
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                artifactRefs = state.artifactRefs + artifactRef,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "read-graph-summary",
                    toolName = selectedScopeResult.toolName,
                    nodeId = selectedNodeIds.firstOrNull(),
                ),
                lastModelOutput = "已读取图摘要，准备执行问答。",
            ),
        )
    }

    /** 真正调用执行器执行问答，并把结果固化为 QaConclusionArtifact，同时把候选变更写入草稿。 */
    private fun executeQaStep(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: QaCapabilityInput,
    ): AgentStepExecutionResult {
        return runCatching {
            runtimeContext.requireWithinDeadline()
            val runtimeEvidenceInput = buildRuntimeEvidenceInput(input, state, runtimeContext)
            val executorInput = runtimeEvidenceInput.toExecutorInput(input)
            runtimeContext.requireWithinDeadline()
            val result = executeQa(
                input = executorInput.copy(settings = executorInput.settings.withRuntimeDeadlineTimeout(runtimeContext)),
                runtimeContext = runtimeContext,
                state = state,
            ).withRuntimeEvidence(runtimeEvidenceInput)
            runtimeContext.requireWithinDeadline()
            val artifact = QaConclusionArtifact(
                artifactId = "${state.runId}-qa-conclusion-${state.stepIndex}",
                result = result,
            )
            val qaArtifactRef = runtimeContext.artifactStore.save(artifact)
            val candidateArtifactRefs = result.newCandidateChanges
                .ifEmpty { result.candidateChanges }
                .distinctBy { candidate -> candidate.changeId }
                .map { candidate ->
                    val toolResult = toolRegistry.require("create_candidate_draft").invoke(
                        input = mapOf("candidate" to candidate),
                        context = runtimeContext.toolExecutionContext(
                            snapshot = runtimeContext.snapshotSupplier() ?: ToolGraphSnapshot(),
                            runBudget = state.budget,
                        ),
                    )
                    toolResult.payload["artifactRef"] as? com.charmnight.linkgraph.agent.artifact.ArtifactRef
                }
                .filterNotNull()
            AgentStepExecutionResult.complete(
                state.copy(
                    phase = AgentRunPhase.SUCCEEDED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + candidateArtifactRefs + qaArtifactRef,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.SUCCEEDED,
                        summary = if (candidateArtifactRefs.isEmpty()) {
                            "execute-qa"
                        } else {
                            "execute-qa-and-create-candidate-drafts"
                        },
                        toolName = if (candidateArtifactRefs.isEmpty()) null else "create_candidate_draft",
                    ),
                    lastModelOutput = result.answer,
                ),
            )
        }.getOrElse { throwable ->
            if (throwable is AgentRuntimeDeadlineExceededException) {
                return AgentStepExecutionResult.fail(
                    state.copy(
                        phase = AgentRunPhase.FAILED,
                        budget = state.budget.recordStep(),
                        stepIndex = state.stepIndex + 1,
                        stepRecords = state.stepRecords + AgentStepRecord(
                            stepIndex = state.stepIndex,
                            phase = AgentRunPhase.FAILED,
                            summary = "execute-qa-timeout",
                        ),
                        lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                        failureReason = AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED,
                    ),
                )
            }
            AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "execute-qa",
                    ),
                    lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                    failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                ),
            )
        }
    }

    /**
     * 第二步根据当前图选区按需读取代码。
     * 预加载源码只参与预算约束，不能作为本轮 QA prompt 的源码证据；prompt 只能使用 runtime 实际读取成功的 artifact。
     */
    private fun collectCodeEvidenceIfNeeded(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: QaCapabilityInput,
    ): AgentStepExecutionResult {
        val preloadedBudget = recordPreloadedCodeEvidenceBudget(state.budget, input.qaContext.sourceContext)
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = preloadedBudget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取代码证据。",
            ),
        )
        val targetNodeIds = resolveCodeEvidenceTargetNodeIds(
            input = input,
            state = state,
            runtimeContext = runtimeContext,
        )
        if (targetNodeIds.isEmpty()) {
            return AgentStepExecutionResult.continueWith(
                state.copy(
                    phase = AgentRunPhase.RUNNING,
                    budget = preloadedBudget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.RUNNING,
                        summary = "skip-code-evidence-read",
                        nodeId = targetNodeIds.firstOrNull(),
                    ),
                    lastModelOutput = "当前问题没有显式选区，先按图证据继续问答。",
                ),
            )
        }

        var nextBudget = preloadedBudget.recordStep()
        val nextArtifacts = state.artifactRefs.toMutableList()
        val evidenceTraces = mutableListOf<EvidenceTraceEntry>()
        var promptEvidenceCount = 0
        var usedToolName: String? = null
        for ((index, nodeId) in targetNodeIds.withIndex()) {
            val reasonBeforeNextFileRead = nextBudget.failureReasonBeforeNextFileRead()
            if (reasonBeforeNextFileRead != null) {
                evidenceTraces += EvidenceTraceEntry(
                    nodeId = nodeId,
                    filePath = nodeId,
                    reason = "未继续读取源码：问答证据读取预算已用尽（$reasonBeforeNextFileRead）。",
                    includedInPrompt = false,
                )
                break
            }
            val toolContext = runtimeContext.toolExecutionContext(
                snapshot = snapshot,
                runBudget = nextBudget,
            )
            val anchorResult = toolRegistry.require("resolve_anchor").invoke(
                input = mapOf("nodeId" to nodeId),
                context = toolContext,
            )
            val resolution = anchorResult.payload["resolution"] as? com.charmnight.linkgraph.llm.tools.QaEvidenceAnchorResolution
            val anchor = anchorResult.payload["node"] as? GraphNode
            if (anchor == null) {
                evidenceTraces += EvidenceTraceEntry(
                    nodeId = nodeId,
                    resolvedNodeId = resolution?.resolvedNodeId,
                    filePath = nodeId,
                    reason = "未读取到源码：${anchorResult.errorMessage ?: "未解析到代码锚点"}",
                    includedInPrompt = false,
                    mappingTrace = resolution?.mappingTrace.orEmpty(),
                )
                continue
            }
            val snippetRead = readSnippetForAnchor(anchor, toolContext)
            val snippetContext = snippetRead.sourceContext
            val snippet = snippetContext?.snippet?.takeIf { it.isNotBlank() }
            if (snippetContext == null || snippet == null) {
                evidenceTraces += EvidenceTraceEntry(
                    nodeId = nodeId,
                    resolvedNodeId = anchor.id.takeIf { resolvedNodeId -> resolvedNodeId != nodeId },
                    filePath = traceLocation(anchor),
                    reason = "未读取到源码：${snippetRead.failureReason}",
                    startLine = anchor.sourceLocation().startLine,
                    endLine = anchor.sourceLocation().endLine,
                    includedInPrompt = false,
                    mappingTrace = resolution?.mappingTrace.orEmpty(),
                )
                continue
            }
            val snippetLineCount = snippet.lineSequence().count()
            val budgetAfterRead = nextBudget.recordFileRead(snippetLineCount)
            usedToolName = usedToolName ?: if (!anchor.signature.isNullOrBlank()) "read_symbol" else "read_source_snippet"
            if (snippetLineCount > nextBudget.maxSnippetLines) {
                nextBudget = budgetAfterRead
                evidenceTraces += EvidenceTraceEntry(
                    nodeId = nodeId,
                    resolvedNodeId = anchor.id.takeIf { resolvedNodeId -> resolvedNodeId != nodeId },
                    filePath = snippetContext.filePath,
                    reason = "跳过源码片段：单段 $snippetLineCount 行超过预算 ${nextBudget.maxSnippetLines} 行。",
                    startLine = snippetContext.startLine,
                    endLine = snippetContext.endLine,
                    includedInPrompt = false,
                    mappingTrace = resolution?.mappingTrace.orEmpty(),
                )
                continue
            }
            if (budgetAfterRead.totalSnippetLinesRead > nextBudget.maxTotalSnippetLines) {
                nextBudget = budgetAfterRead
                evidenceTraces += EvidenceTraceEntry(
                    nodeId = nodeId,
                    resolvedNodeId = anchor.id.takeIf { resolvedNodeId -> resolvedNodeId != nodeId },
                    filePath = snippetContext.filePath,
                    reason = "跳过源码片段：累计源码行数 ${budgetAfterRead.totalSnippetLinesRead} 超过预算 ${nextBudget.maxTotalSnippetLines} 行。",
                    startLine = snippetContext.startLine,
                    endLine = snippetContext.endLine,
                    includedInPrompt = false,
                    mappingTrace = resolution?.mappingTrace.orEmpty(),
                )
                continue
            }
            val artifactRef = runtimeContext.artifactStore.save(
                CodeEvidenceArtifact(
                    artifactId = "${state.runId}-code-evidence-$index",
                    nodeId = anchor.id,
                    filePath = snippetContext.filePath,
                    snippet = snippet,
                    startLine = snippetContext.startLine,
                    endLine = snippetContext.endLine,
                ),
            )
            nextArtifacts += artifactRef
            promptEvidenceCount += 1
            evidenceTraces += EvidenceTraceEntry(
                nodeId = nodeId,
                resolvedNodeId = anchor.id.takeIf { resolvedNodeId -> resolvedNodeId != nodeId },
                filePath = snippetContext.filePath,
                reason = "runtime-code-read",
                startLine = snippetContext.startLine,
                endLine = snippetContext.endLine,
                includedInPrompt = true,
                mappingTrace = resolution?.mappingTrace.orEmpty(),
            )
            nextBudget = budgetAfterRead
        }
        if (evidenceTraces.isNotEmpty()) {
            nextArtifacts += runtimeContext.artifactStore.save(
                QaEvidenceTraceArtifact(
                    artifactId = "${state.runId}-qa-evidence-trace-${state.stepIndex}",
                    traces = evidenceTraces,
                ),
            )
        }

        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = nextBudget,
                stepIndex = state.stepIndex + 1,
                artifactRefs = nextArtifacts,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = if (promptEvidenceCount > 0) {
                        "read-code-evidence"
                    } else {
                        "skip-code-evidence-read"
                    },
                    toolName = usedToolName,
                    nodeId = targetNodeIds.firstOrNull(),
                ),
                lastModelOutput = if (promptEvidenceCount > 0) {
                    "已按需读取代码证据，准备继续问答。"
                } else {
                    "未定位到可读取的代码锚点，先按图证据继续问答。"
                },
            ),
        )
    }

    /** 解析当前轮需要读取代码证据的节点 ID 列表：优先显式选区，其次 runtime 选取的节点，最后整图采样。 */
    private fun resolveCodeEvidenceTargetNodeIds(
        input: QaCapabilityInput,
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): List<String> {
        val explicitSelection = input.qaContext.selectedNodeIds.distinct()
        val graphSummary = extractGraphSummary(state, runtimeContext)
        val runtimeGraph = graphSummary?.graph
            ?: input.qaContext.editableGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            ?: input.qaContext.factGraph
        if (explicitSelection.isNotEmpty()) {
            return expandExplicitSelectionEvidenceTargets(runtimeGraph, explicitSelection)
        }
        val runtimeSelection = graphSummary?.selectedNodeIds.orEmpty().distinct()
        if (runtimeSelection.isNotEmpty()) {
            return expandExplicitSelectionEvidenceTargets(runtimeGraph, runtimeSelection)
        }
        return selectWholeGraphEvidenceTargets(runtimeGraph)
    }

    /** 显式选区场景下，按 BFS 在限定深度内展开与选中节点相关的可读证据目标。 */
    private fun expandExplicitSelectionEvidenceTargets(
        graph: GraphDocument,
        selectedNodeIds: List<String>,
    ): List<String> {
        if (selectedNodeIds.isEmpty()) {
            return emptyList()
        }
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        val orderedTargets = linkedSetOf<String>()
        val visitedNodeIds = mutableSetOf<String>()
        val queue = ArrayDeque(selectedNodeIds.map { nodeId -> TraversalTarget(nodeId, 0) })
        while (queue.isNotEmpty() && orderedTargets.size < wholeGraphEvidenceTargetLimit) {
            val current = queue.removeFirst()
            if (!visitedNodeIds.add(current.nodeId)) {
                continue
            }
            val node = nodeById[current.nodeId] ?: continue
            if (hasReadableSourceAnchor(node)) {
                orderedTargets += node.id
            }
            if (current.depth >= explicitSelectionTraversalDepth) {
                continue
            }
            relatedNodeIds(graph, current.nodeId).forEach { nextNodeId ->
                if (nextNodeId !in visitedNodeIds) {
                    queue += TraversalTarget(nextNodeId, current.depth + 1)
                }
            }
        }
        return orderedTargets.ifEmpty {
            selectedNodeIds
        }.toList()
    }

    /** 整图采样场景下，按优先级选取前若干个方法类节点作为代码证据目标。 */
    private fun selectWholeGraphEvidenceTargets(
        graph: GraphDocument,
    ): List<String> {
        return graph.nodes
            .asSequence()
            .filter(::isWholeGraphCodeEvidenceTarget)
            .sortedWith(
                compareBy<GraphNode>(
                    ::wholeGraphEvidencePriority,
                    { it.title },
                    { it.id },
                ),
            )
            .take(wholeGraphEvidenceTargetLimit)
            .map(GraphNode::id)
            .toList()
    }

    /** 返回与指定节点通过边直接相连的其他节点 ID 列表。 */
    private fun relatedNodeIds(
        graph: GraphDocument,
        nodeId: String,
    ): List<String> {
        return graph.edges
            .asSequence()
            .filter { edge -> edge.fromNodeId == nodeId || edge.toNodeId == nodeId }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .filter { relatedNodeId -> relatedNodeId != nodeId }
            .distinct()
            .toList()
    }

    /** 判断节点是否具备可读取的源码锚点（签名或文件路径）。 */
    private fun hasReadableSourceAnchor(node: GraphNode): Boolean {
        return !node.signature.isNullOrBlank() || !node.sourceLocation().filePath.isNullOrBlank()
    }

    /** 判断节点是否可作为整图场景的代码证据目标，要求有源码锚点且类型属于方法/类等可读类型。 */
    private fun isWholeGraphCodeEvidenceTarget(node: GraphNode): Boolean {
        if (!hasReadableSourceAnchor(node)) {
            return false
        }
        return node.type in setOf(
            NodeType.METHOD,
            NodeType.FLOW_ACTION,
            NodeType.FLOW_SCOPE,
            NodeType.TERMINAL,
            NodeType.CLASS,
            NodeType.INTERFACE,
            NodeType.ENUM,
            NodeType.ANNOTATION,
            NodeType.RECORD,
            NodeType.OBJECT,
        )
    }

    /** 整图采样时按节点类型给出优先级，方法类节点优先于其他类型。 */
    private fun wholeGraphEvidencePriority(node: GraphNode): Int {
        return when (node.type) {
            NodeType.METHOD -> 0
            NodeType.FLOW_ACTION -> 1
            NodeType.FLOW_SCOPE -> 2
            NodeType.TERMINAL -> 3
            else -> 4
        }
    }

    /** 把外部预加载的源码片段按行数累计到预算中，避免后续读取超额。 */
    private fun recordPreloadedCodeEvidenceBudget(
        budget: RunBudget,
        sourceContexts: List<SourceSnippetContext>,
    ): RunBudget {
        return sourceContexts
            .filter { snippet -> !snippet.snippet.isNullOrBlank() }
            .distinctBy { snippet -> "${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
            .fold(budget) { nextBudget, snippet ->
                nextBudget.recordFileRead(snippet.snippet!!.lineSequence().count())
            }
    }

    /**
     * 从锚点优先按 symbol 读取，读不到再回退到显式文件片段读取。
     */
    private fun readSnippetForAnchor(
        anchor: GraphNode,
        toolContext: ToolExecutionContext,
    ): SnippetReadResult {
        if (!anchor.signature.isNullOrBlank()) {
            val symbolResult = toolRegistry.require("read_symbol").invoke(
                input = mapOf("symbolSignature" to anchor.signature),
                context = toolContext,
            )
            val snippet = symbolResult.payload["sourceSnippetContext"] as? SourceSnippetContext
            if (snippet?.snippet.isNullOrBlank().not()) {
                return SnippetReadResult(sourceContext = snippet)
            }
        }
        val sourceLocation = anchor.sourceLocation()
        val filePath = sourceLocation.filePath ?: return SnippetReadResult(
            failureReason = "节点缺少源码文件路径，且 symbol 未解析到源码。",
        )
        val startLine = sourceLocation.startLine
        val endLine = sourceLocation.endLine
        val snippetResult = toolRegistry.require("read_source_snippet").invoke(
            input = mapOf(
                "filePath" to filePath,
                "startLine" to startLine,
                "endLine" to endLine,
            ),
            context = toolContext,
        )
        val snippet = snippetResult.payload["snippet"]?.toString()?.takeIf { it.isNotBlank() } ?: return SnippetReadResult(
            failureReason = snippetResult.errorMessage ?: "文件片段为空。",
        )
        return SnippetReadResult(
            sourceContext = SourceSnippetContext(
                nodeId = anchor.id,
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                snippet = snippet,
            ),
        )
    }

    /** 把 runtime 期间累积的图、源码证据和取证轨迹归并为执行器可消费的输入。 */
    private fun buildRuntimeEvidenceInput(
        input: QaCapabilityInput,
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): QaRuntimeEvidenceInput {
        val graphSummary = extractGraphSummary(state, runtimeContext)
        val selectedNodeIds = graphSummary?.selectedNodeIds
            ?.ifEmpty { input.qaContext.selectedNodeIds }
            ?: input.qaContext.selectedNodeIds
        val runtimeEditableGraph = graphSummary?.graph
            ?.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            ?: input.qaContext.editableGraph
        val runtimeSourceContext = state.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<CodeEvidenceArtifact>()
            .map { artifact ->
                SourceSnippetContext(
                    nodeId = artifact.nodeId,
                    filePath = artifact.filePath,
                    startLine = artifact.startLine,
                    endLine = artifact.endLine,
                    snippet = artifact.snippet,
                )
            }
            .toList()
            .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
        val runtimeEvidenceTrace = state.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<QaEvidenceTraceArtifact>()
            .flatMap { artifact -> artifact.traces.asSequence() }
            .toList()
            .distinctBy { trace -> "${trace.nodeId}:${trace.filePath}:${trace.startLine}:${trace.endLine}:${trace.reason}" }
        return QaRuntimeEvidenceInput(
            editableGraph = runtimeEditableGraph,
            selectedNodeIds = selectedNodeIds,
            sourceContext = runtimeSourceContext
                .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" },
            evidenceTrace = runtimeEvidenceTrace
                .distinctBy { trace -> "${trace.nodeId}:${trace.filePath}:${trace.startLine}:${trace.endLine}:${trace.reason}" },
        )
    }

    /** 用 runtime 累积的证据替换原始输入中的相关字段，构造正式执行器使用的输入。 */
    private fun QaRuntimeEvidenceInput.toExecutorInput(
        input: QaCapabilityInput,
    ): QaCapabilityInput {
        return input.copy(
            qaContext = input.qaContext.copy(
                editableGraph = editableGraph,
                selectedNodeIds = selectedNodeIds,
                sourceContext = sourceContext,
                evidenceTrace = evidenceTrace,
            ),
            session = input.session,
        )
    }

    /** 把 runtime 累积的源码与取证轨迹合并回问答结果，并在缺失关键证据时附带提示。 */
    private fun GraphPatchResult.withRuntimeEvidence(
        runtimeEvidenceInput: QaRuntimeEvidenceInput,
    ): GraphPatchResult {
        val runtimeSourceContext = runtimeEvidenceInput.sourceContext
            .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
        val runtimeTrace = runtimeEvidenceInput.evidenceTrace
            .distinctBy { trace -> "${trace.nodeId}:${trace.filePath}:${trace.startLine}:${trace.endLine}:${trace.reason}" }
        val missingPromptEvidenceWarning = if (runtimeTrace.isNotEmpty() && runtimeTrace.none(EvidenceTraceEntry::includedInPrompt)) {
            listOf("本轮没有读取到可送入 prompt 的真实源码片段；回答只能基于当前图和历史会话，不能视为完整代码上下文分析。")
        } else {
            emptyList()
        }
        val budgetLimitedEvidenceWarning = if (runtimeTrace.any { trace ->
                !trace.includedInPrompt && trace.reason.contains("预算")
            }) {
            listOf("本轮源码证据读取受预算限制，回答可能只覆盖已读取片段。")
        } else {
            emptyList()
        }
        return copy(
            sourceContext = (sourceContext + runtimeSourceContext)
                .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" },
            evidenceTrace = (evidenceTrace + runtimeTrace)
                .distinctBy { trace -> "${trace.nodeId}:${trace.filePath}:${trace.startLine}:${trace.endLine}:${trace.reason}" },
            warnings = (missingPromptEvidenceWarning + budgetLimitedEvidenceWarning + warnings).distinct(),
        )
    }

    /** 解析节点对应的可读位置标签，用于日志和取证轨迹展示。 */
    private fun traceLocation(anchor: GraphNode): String {
        return anchor.sourceFilePathOrLocationPath()
            ?: anchor.location
            ?: anchor.signature
            ?: anchor.id
    }

    /** 从 artifact 列表中提取最近一次保存的图摘要 artifact。 */
    private fun extractGraphSummary(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): GraphSummaryArtifact? {
        return state.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<GraphSummaryArtifact>()
            .lastOrNull()
    }

    /** 问答执行器接口，由上层注入真实实现（通常是远程模型调用）。 */
    fun interface QaExecutor {
        /** 根据当前 capability 输入、运行上下文与状态生成问答结果。 */
        fun invoke(
            input: QaCapabilityInput,
            runtimeContext: AgentRuntimeContext,
            state: AgentRunState,
        ): GraphPatchResult
    }

    /** BFS 遍历过程中记录节点 ID 与已遍历深度。 */
    private data class TraversalTarget(
        val nodeId: String,
        val depth: Int,
    )

    /** 单次源码读取尝试的结果，可携带成功读到的片段或失败原因。 */
    private data class SnippetReadResult(
        val sourceContext: SourceSnippetContext? = null,
        val failureReason: String = "未知原因。",
    )
}

/** 允许开放 review 类工具的问答模式集合。 */
private val REVIEW_TOOL_MODES = setOf(
    QaMode.REVIEW,
    QaMode.CHANGE,
    QaMode.INVESTIGATE,
)

/** review 类工具名称集合。 */
private val REVIEW_TOOL_NAMES = setOf(
    "get_changed_symbols",
    "get_blast_radius",
    "find_related_tests",
    "build_review_evidence_bundle",
)

/** 允许开放图变更工具的问答模式集合。 */
private val GRAPH_MUTATION_TOOL_MODES = setOf(
    QaMode.CHANGE,
)

/** 图变更工具名称集合。 */
private val GRAPH_MUTATION_TOOL_NAMES = setOf(
    "edit_graph",
)

/**
 * 问答 runtime 累积的可信证据输入。
 * 包装可编辑图、选区、源码片段和取证轨迹，供执行器在生成最终回答时使用。
 */
data class QaRuntimeEvidenceInput(
    /** runtime 当前可用的可编辑图。 */
    val editableGraph: GraphDocument,
    /** 当前选中的节点 ID 列表。 */
    val selectedNodeIds: List<String>,
    /** runtime 实际读取成功的源码片段。 */
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    /** runtime 完整取证轨迹，包括被跳过或失败的尝试。 */
    val evidenceTrace: List<EvidenceTraceEntry> = emptyList(),
)

/**
 * 问答 capability 的输入结构。
 * 当前包装问答执行器所需的上下文，后续可继续扩展 tool 决策与 artifact 依赖。
 */
data class QaCapabilityInput(
    /** 用户问题。 */
    val question: String,
    /** 当前问答上下文。 */
    val qaContext: GraphQaContext,
    /** 当前生效设置。 */
    val settings: LinkGraphSettingsState = LinkGraphSettingsState(),
    /** 当前多轮问答会话。 */
    val session: QaConversationSession? = null,
    /** 如果是追问，则记录上游 threadId。 */
    val sourceThreadId: String? = null,
    /** 前端请求的问答模式。 */
    val requestedMode: QaMode = QaMode.AUTO,
    /** 后端实际执行的问答模式。 */
    val effectiveMode: QaMode = QaMode.AUTO,
    /** 流式预览回调。 */
    val onPreview: ((String, Boolean) -> Unit)? = null,
)
