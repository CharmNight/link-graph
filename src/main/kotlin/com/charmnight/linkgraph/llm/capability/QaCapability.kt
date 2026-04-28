package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditScopeResolver
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.llm.artifact.GraphSummaryArtifact
import com.charmnight.linkgraph.llm.artifact.QaConclusionArtifact
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.AgentStepExecutionResult
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.runtime.StepExecutor
import com.charmnight.linkgraph.llm.runtime.StopPolicy
import com.charmnight.linkgraph.llm.runtime.budgetExceededStepResult
import com.charmnight.linkgraph.llm.runtime.failureReasonBeforeNextFileRead
import com.charmnight.linkgraph.llm.runtime.failureReasonForBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.llm.tools.AgentTool
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.CodeReadToolFacade
import com.charmnight.linkgraph.llm.tools.CreateCandidateDraftTool
import com.charmnight.linkgraph.llm.tools.GetCurrentGraphTool
import com.charmnight.linkgraph.llm.tools.GetDraftWorkbenchTool
import com.charmnight.linkgraph.llm.tools.GetSelectedScopeTool
import com.charmnight.linkgraph.llm.tools.GraphToolFacade
import com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool
import com.charmnight.linkgraph.llm.tools.ReadSymbolTool
import com.charmnight.linkgraph.llm.tools.ResolveAnchorTool
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.DraftToolFacade
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
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
    private val auditExecutor: AuditExecutor,
    /** capability 可用工具注册表。 */
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        listOf<AgentTool>(
            GetDraftWorkbenchTool(DraftToolFacade()),
            GetCurrentGraphTool(GraphToolFacade()),
            GetSelectedScopeTool(GraphToolFacade()),
            ResolveAnchorTool(CodeReadToolFacade()),
            ReadSourceSnippetTool(CodeReadToolFacade()),
            ReadSymbolTool(CodeReadToolFacade()),
            CreateCandidateDraftTool(),
        ),
    ),
) : AgentCapability<QaCapabilityInput, GraphPatchResult> {
    private val wholeGraphEvidenceTargetLimit: Int = 5
    private val explicitSelectionTraversalDepth: Int = 2

    override val capabilityId: String = "qa"

    override fun buildInitialState(
        input: QaCapabilityInput,
        runtimeContext: AgentRuntimeContext,
    ): AgentRunState {
        return AgentRunState(
            runId = "qa-${UUID.randomUUID()}",
            capabilityId = capabilityId,
            phase = AgentRunPhase.CREATED,
            userGoal = input.question,
            budget = defaultBudget,
            stepIndex = 0,
            artifactRefs = emptyList(),
        )
    }

    override fun allowedTools(input: QaCapabilityInput): Set<String> {
        return setOf(
            "get_draft_workbench",
            "get_current_graph",
            "get_selected_scope",
            "resolve_anchor",
            "read_source_snippet",
            "read_symbol",
            "create_candidate_draft",
        )
    }

    override fun stopPolicy(input: QaCapabilityInput): StopPolicy = StopPolicy.default()

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

    override fun createStepExecutor(input: QaCapabilityInput): StepExecutor {
        return StepExecutor { state, runtimeContext ->
            when (state.stepIndex) {
                0 -> readDraftWorkbench(state, runtimeContext)
                1 -> collectGraphSummary(state, runtimeContext, input)
                2 -> collectCodeEvidenceIfNeeded(state, runtimeContext, input)
                else -> executeAuditStep(state, runtimeContext, input)
            }
        }
    }

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
            context = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
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

    fun executeAudit(
        input: QaCapabilityInput,
        runtimeContext: AgentRuntimeContext,
        state: AgentRunState = buildInitialState(input, runtimeContext),
    ): GraphPatchResult {
        return auditExecutor.invoke(input, runtimeContext, state)
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
                    nodeId = input.auditContext.selectedNodeIds.firstOrNull(),
                ),
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少图快照，无法继续问答。",
            ),
        )
        val toolContext = ToolExecutionContext(
            project = runtimeContext.project,
            snapshot = snapshot,
            artifactStore = runtimeContext.artifactStore,
            runBudget = state.budget,
        )
        val selectedScopeResult = toolRegistry.require("get_selected_scope").invoke(
            input = mapOf("selectedNodeIds" to input.auditContext.selectedNodeIds),
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

    private fun executeAuditStep(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: QaCapabilityInput,
    ): AgentStepExecutionResult {
        return runCatching {
            val augmentedInput = buildAugmentedInput(input, state, runtimeContext)
            val result = executeAudit(
                input = augmentedInput,
                runtimeContext = runtimeContext,
                state = state,
            )
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
                        context = ToolExecutionContext(
                            project = runtimeContext.project,
                            snapshot = runtimeContext.snapshotSupplier() ?: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(),
                            artifactStore = runtimeContext.artifactStore,
                            runBudget = state.budget,
                        ),
                    )
                    toolResult.payload["artifactRef"] as? com.charmnight.linkgraph.llm.artifact.ArtifactRef
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
                            "execute-audit"
                        } else {
                            "execute-audit-and-create-candidate-drafts"
                        },
                        toolName = if (candidateArtifactRefs.isEmpty()) null else "create_candidate_draft",
                    ),
                    lastModelOutput = result.answer,
                ),
            )
        }.getOrElse { throwable ->
            AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "execute-audit",
                    ),
                    lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                    failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                ),
            )
        }
    }

    /**
     * 第二步根据当前图选区按需读取代码。
     * 只有当当前上下文没有现成源码证据时，runtime 才补充读取，避免继续依赖一次性大上下文。
     */
    private fun collectCodeEvidenceIfNeeded(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: QaCapabilityInput,
    ): AgentStepExecutionResult {
        val preloadedBudget = recordPreloadedCodeEvidenceBudget(state.budget, input.auditContext.sourceContext)
        val preloadedFailureReason = StopPolicy.default().evaluate(state.copy(budget = preloadedBudget))
        if (preloadedFailureReason != null) {
            return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = preloadedBudget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                    summary = "reject-preloaded-code-evidence-over-budget",
                    nodeId = input.auditContext.selectedNodeIds.firstOrNull(),
                ),
                    failureReason = preloadedFailureReason,
                    lastModelOutput = "预加载源码证据超出 runtime 预算，已拒绝继续问答。",
                ),
            )
        }
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
        var usedToolName: String? = null
        targetNodeIds.forEachIndexed { index, nodeId ->
            nextBudget.failureReasonBeforeNextFileRead()?.let { reason ->
                return budgetExceededStepResult(
                    state = state,
                    budget = nextBudget,
                    failureReason = reason,
                    summary = "read-code-evidence",
                    toolName = usedToolName ?: "read_source_snippet",
                    nodeId = nodeId,
                    artifactRefs = nextArtifacts,
                    lastModelOutput = "runtime 预算已耗尽，停止继续读取问答代码证据。",
                )
            }
            val toolContext = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
                runBudget = nextBudget,
            )
            val anchorResult = toolRegistry.require("resolve_anchor").invoke(
                input = mapOf("nodeId" to nodeId),
                context = toolContext,
            )
            val anchor = anchorResult.payload["node"] as? GraphNode ?: return@forEachIndexed
            val snippetContext = readSnippetForAnchor(anchor, toolContext) ?: return@forEachIndexed
            val snippet = snippetContext.snippet?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val artifactRef = runtimeContext.artifactStore.save(
                CodeEvidenceArtifact(
                    artifactId = "${state.runId}-code-evidence-$index",
                    nodeId = nodeId,
                    filePath = snippetContext.filePath,
                    snippet = snippet,
                    startLine = snippetContext.startLine,
                    endLine = snippetContext.endLine,
                ),
            )
            nextArtifacts += artifactRef
            nextBudget = nextBudget.recordFileRead(snippet.lineSequence().count())
            usedToolName = usedToolName ?: if (!anchor.signature.isNullOrBlank()) "read_symbol" else "read_source_snippet"
            StopPolicy.default().failureReasonForBudget(state, nextBudget)?.let { reason ->
                return budgetExceededStepResult(
                    state = state,
                    budget = nextBudget,
                    failureReason = reason,
                    summary = "read-code-evidence",
                    toolName = usedToolName,
                    nodeId = nodeId,
                    artifactRefs = nextArtifacts,
                    lastModelOutput = "读取问答代码证据后触发 runtime 预算上限。",
                )
            }
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
                    summary = if (nextArtifacts.size > state.artifactRefs.size) {
                        "read-code-evidence"
                    } else {
                        "skip-code-evidence-read"
                    },
                    toolName = usedToolName,
                    nodeId = targetNodeIds.firstOrNull(),
                ),
                lastModelOutput = if (nextArtifacts.size > state.artifactRefs.size) {
                    "已按需读取代码证据，准备继续问答。"
                } else {
                    "未定位到可读取的代码锚点，先按图证据继续问答。"
                },
            ),
        )
    }

    private fun resolveCodeEvidenceTargetNodeIds(
        input: QaCapabilityInput,
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): List<String> {
        val explicitSelection = input.auditContext.selectedNodeIds.distinct()
        val graphSummary = extractGraphSummary(state, runtimeContext)
        val runtimeGraph = graphSummary?.graph
            ?: input.auditContext.editableGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            ?: input.auditContext.factGraph
        if (explicitSelection.isNotEmpty()) {
            return expandExplicitSelectionEvidenceTargets(runtimeGraph, explicitSelection)
        }
        val runtimeSelection = graphSummary?.selectedNodeIds.orEmpty().distinct()
        if (runtimeSelection.isNotEmpty()) {
            return expandExplicitSelectionEvidenceTargets(runtimeGraph, runtimeSelection)
        }
        return selectWholeGraphEvidenceTargets(runtimeGraph)
    }

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
            selectedNodeIds.filter { nodeId -> nodeById[nodeId]?.let(::hasReadableSourceAnchor) == true }
        }.toList()
    }

    private fun selectWholeGraphEvidenceTargets(
        graph: GraphDocument,
    ): List<String> {
        return graph.nodes
            .asSequence()
            .filter(::hasReadableSourceAnchor)
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

    private fun hasReadableSourceAnchor(node: GraphNode): Boolean {
        return !node.signature.isNullOrBlank() || !node.metadata["source.filePath"].isNullOrBlank()
    }

    private fun wholeGraphEvidencePriority(node: GraphNode): Int {
        return when (node.type) {
            NodeType.METHOD -> 0
            NodeType.FLOW_ACTION -> 1
            NodeType.FLOW_SCOPE -> 2
            NodeType.TERMINAL -> 3
            else -> 4
        }
    }

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
    ): SourceSnippetContext? {
        if (!anchor.signature.isNullOrBlank()) {
            val symbolResult = toolRegistry.require("read_symbol").invoke(
                input = mapOf("symbolSignature" to anchor.signature),
                context = toolContext,
            )
            val snippet = symbolResult.payload["sourceSnippetContext"] as? SourceSnippetContext
            if (snippet?.snippet.isNullOrBlank().not()) {
                return snippet
            }
        }
        val filePath = anchor.metadata["source.filePath"] ?: return null
        val startLine = anchor.metadata["source.startLine"]?.toIntOrNull()
        val endLine = anchor.metadata["source.endLine"]?.toIntOrNull()
        val snippetResult = toolRegistry.require("read_source_snippet").invoke(
            input = mapOf(
                "filePath" to filePath,
                "startLine" to startLine,
                "endLine" to endLine,
            ),
            context = toolContext,
        )
        val snippet = snippetResult.payload["snippet"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return SourceSnippetContext(
            nodeId = anchor.id,
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = snippet,
        )
    }

    /**
     * 旧问答执行器仍吃 GraphAuditContext，因此这里把 runtime 实际读取到的代码证据回填进去。
     */
    private fun buildAugmentedInput(
        input: QaCapabilityInput,
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): QaCapabilityInput {
        val graphSummary = extractGraphSummary(state, runtimeContext)
        val selectedNodeIds = graphSummary?.selectedNodeIds
            ?.ifEmpty { input.auditContext.selectedNodeIds }
            ?: input.auditContext.selectedNodeIds
        val codeEvidence = state.artifactRefs
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
        return input.copy(
            auditContext = input.auditContext.copy(
                selectedNodeIds = selectedNodeIds,
                sourceContext = codeEvidence
                    .distinctBy { snippet -> "${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" },
                evidenceTrace = codeEvidence.map { evidence ->
                    EvidenceTraceEntry(
                        nodeId = evidence.nodeId,
                        filePath = evidence.filePath,
                        reason = "runtime-code-read",
                        startLine = evidence.startLine,
                        endLine = evidence.endLine,
                        includedInPrompt = true,
                    )
                }.distinctBy { trace -> "${trace.filePath}:${trace.startLine}:${trace.endLine}:${trace.reason}" },
            ),
            session = input.session,
        )
    }

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

    fun interface AuditExecutor {
        fun invoke(
            input: QaCapabilityInput,
            runtimeContext: AgentRuntimeContext,
            state: AgentRunState,
        ): GraphPatchResult
    }

    private data class TraversalTarget(
        val nodeId: String,
        val depth: Int,
    )
}

/**
 * 问答 capability 的输入结构。
 * 当前包装问答执行器所需的上下文，后续可继续扩展 tool 决策与 artifact 依赖。
 */
data class QaCapabilityInput(
    /** 用户问题。 */
    val question: String,
    /** 当前问答上下文。 */
    val auditContext: GraphAuditContext,
    /** 当前生效设置。 */
    val settings: LinkGraphSettingsState = LinkGraphSettingsState(),
    /** 当前多轮问答会话。 */
    val session: AuditConversationSession? = null,
    /** 如果是追问，则记录上游 threadId。 */
    val sourceThreadId: String? = null,
    /** 流式预览回调。 */
    val onPreview: ((String, Boolean) -> Unit)? = null,
)
