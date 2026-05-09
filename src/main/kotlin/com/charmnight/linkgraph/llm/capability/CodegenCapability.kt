package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.CodeDraftArtifact
import com.charmnight.linkgraph.llm.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeDeadlineExceededException
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.AgentStepExecutionResult
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.runtime.StepExecutor
import com.charmnight.linkgraph.llm.runtime.StopPolicy
import com.charmnight.linkgraph.llm.runtime.budgetExceededStepResult
import com.charmnight.linkgraph.llm.runtime.failureReasonBeforeNextFileRead
import com.charmnight.linkgraph.llm.runtime.failureReasonForBudget
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.CheckWritableDraftTool
import com.charmnight.linkgraph.llm.tools.CodeReadToolFacade
import com.charmnight.linkgraph.llm.tools.DraftToolFacade
import com.charmnight.linkgraph.llm.tools.GetConfirmedIntentTool
import com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.ValidateEditScopeTool
import com.charmnight.linkgraph.llm.tools.ValidationToolFacade
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * 代码生成 capability。
 * 它先读取 confirmed intent，再读取目标代码证据，最后由正式执行器生成草稿并执行本地硬边界校验。
 */
internal class CodegenCapability(
    project: Project,
    private val defaultBudget: RunBudget = RunBudget(),
    private val codegenExecutor: CodegenExecutor,
    private val validationToolFacade: ValidationToolFacade = ValidationToolFacade(),
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        listOf(
            GetConfirmedIntentTool(DraftToolFacade()),
            ReadSourceSnippetTool(CodeReadToolFacade()),
            ValidateEditScopeTool(ValidationToolFacade()),
            CheckWritableDraftTool(ValidationToolFacade()),
        ),
    ),
) : AgentCapability<CodegenCapabilityInput, CodeGenerationResult> {
    override val capabilityId: String = "codegen"

    override fun buildInitialState(input: CodegenCapabilityInput, runtimeContext: AgentRuntimeContext): AgentRunState {
        return AgentRunState(
            runId = "codegen-${UUID.randomUUID()}",
            capabilityId = capabilityId,
            phase = AgentRunPhase.CREATED,
            userGoal = "生成代码草稿",
            budget = defaultBudget,
            stepIndex = 0,
            artifactRefs = emptyList(),
        )
    }

    override fun allowedTools(input: CodegenCapabilityInput): Set<String> {
        return setOf("get_confirmed_intent", "read_source_snippet", "validate_edit_scope", "check_writable_draft")
    }

    override fun stopPolicy(input: CodegenCapabilityInput): StopPolicy = StopPolicy.default()

    override fun finalize(runState: AgentRunState, runtimeContext: AgentRuntimeContext): CodeGenerationResult {
        val artifact = runState.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<CodeDraftArtifact>()
            .lastOrNull()
            ?: error("代码生成结束时缺少 CodeDraftArtifact")
        return artifact.draftResult
    }

    override fun createStepExecutor(input: CodegenCapabilityInput): StepExecutor {
        return StepExecutor { state, runtimeContext ->
            when (state.stepIndex) {
                0 -> readConfirmedIntent(state, runtimeContext)
                1 -> attachPlanArtifactIfPresent(state, runtimeContext, input)
                2 -> readCodeEvidence(state, runtimeContext, input)
                else -> generateDrafts(state, runtimeContext, input)
            }
        }
    }

    private fun readConfirmedIntent(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取 confirmed intent。",
            ),
        )
        val result = toolRegistry.require("get_confirmed_intent").invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
                runBudget = state.budget,
            ),
        )
        val confirmedCount = result.payload["confirmedCount"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val artifactRefs = result.payload["artifactRefs"] as? List<com.charmnight.linkgraph.llm.artifact.ArtifactRef> ?: emptyList()
        if (confirmedCount <= 0) {
            return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "read-confirmed-intent",
                        toolName = result.toolName,
                    ),
                    lastModelOutput = "当前没有已确认正式意图，禁止生成代码草稿。",
                ),
            )
        }
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                artifactRefs = state.artifactRefs + artifactRefs,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "read-confirmed-intent",
                    toolName = result.toolName,
                ),
                lastModelOutput = "已读取 confirmed intent，准备收集代码证据。",
            ),
        )
    }

    private fun attachPlanArtifactIfPresent(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: CodegenCapabilityInput,
    ): AgentStepExecutionResult {
        val plan = input.plan ?: return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "skip-plan-artifact",
                ),
                lastModelOutput = "当前没有实现计划，代码生成仅依赖 confirmed intent。",
            ),
        )
        val existingArtifact = runtimeContext.artifactStore.byType(com.charmnight.linkgraph.llm.artifact.ArtifactType.PLAN)
            .filterIsInstance<PlanArtifact>()
            .lastOrNull { artifact -> artifact.plan == plan }
        val artifactRef = existingArtifact?.let(runtimeContext.artifactStore::save)
            ?: runtimeContext.artifactStore.save(
                PlanArtifact(
                    artifactId = "plan-${state.runId}-${state.stepIndex}",
                    plan = plan,
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
                    summary = "attach-plan-artifact",
                ),
                lastModelOutput = "已绑定实现计划 artifact，准备读取代码证据。",
            ),
        )
    }

    private fun readCodeEvidence(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: CodegenCapabilityInput,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取代码证据。",
            ),
        )
        var nextBudget = state.budget.recordStep()
        val nextArtifacts = state.artifactRefs.toMutableList()
        extractConfirmedIntents(state, runtimeContext)
            .flatMap { change -> change.editScopes }
            .distinctBy { scope -> "${scope.filePath}:${scope.startLine}:${scope.endLine}" }
            .forEachIndexed { index, scope ->
                nextBudget.failureReasonBeforeNextFileRead()?.let { reason ->
                    return budgetExceededStepResult(
                        state = state,
                        budget = nextBudget,
                        failureReason = reason,
                        summary = "read-code-evidence",
                        toolName = "read_source_snippet",
                        nodeId = scope.targetNodeId,
                        artifactRefs = nextArtifacts,
                        lastModelOutput = "runtime 预算已耗尽，停止继续读取代码生成证据。",
                    )
                }
                val result = toolRegistry.require("read_source_snippet").invoke(
                    input = mapOf(
                        "filePath" to scope.filePath,
                        "startLine" to scope.startLine,
                        "endLine" to scope.endLine,
                    ),
                    context = ToolExecutionContext(
                        project = runtimeContext.project,
                        snapshot = snapshot,
                        artifactStore = runtimeContext.artifactStore,
                        runBudget = nextBudget,
                    ),
                )
                val snippet = result.payload["snippet"]?.toString().orEmpty()
                if (snippet.isNotBlank()) {
                    val ref = runtimeContext.artifactStore.save(
                        CodeEvidenceArtifact(
                            artifactId = "${state.runId}-code-evidence-$index",
                            nodeId = scope.targetNodeId,
                            filePath = scope.filePath,
                            snippet = snippet,
                            startLine = scope.startLine,
                            endLine = scope.endLine,
                        ),
                    )
                    nextArtifacts += ref
                    nextBudget = nextBudget.recordFileRead(snippet.lineSequence().count())
                    StopPolicy.default().failureReasonForBudget(state, nextBudget)?.let { reason ->
                        return budgetExceededStepResult(
                            state = state,
                            budget = nextBudget,
                            failureReason = reason,
                            summary = "read-code-evidence",
                            toolName = "read_source_snippet",
                            nodeId = scope.targetNodeId,
                            artifactRefs = nextArtifacts,
                            lastModelOutput = "读取代码生成证据后触发 runtime 预算上限。",
                        )
                    }
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
                    summary = "read-code-evidence",
                    toolName = "read_source_snippet",
                    nodeId = extractConfirmedIntents(state, runtimeContext)
                        .flatMap { change -> change.editScopes }
                        .firstOrNull()
                        ?.targetNodeId,
                ),
                lastModelOutput = "已读取代码证据，准备生成草稿。",
            ),
        )
    }

    private fun generateDrafts(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: CodegenCapabilityInput,
    ): AgentStepExecutionResult {
        return runCatching {
            runtimeContext.requireWithinDeadline()
            val confirmedChanges = extractConfirmedIntents(state, runtimeContext)
            val evidenceArtifacts = state.artifactRefs
                .asSequence()
                .mapNotNull(runtimeContext.artifactStore::get)
                .filterIsInstance<CodeEvidenceArtifact>()
                .toList()
            prevalidateExistingFileTargets(
                state = state,
                runtimeContext = runtimeContext,
                confirmedChanges = confirmedChanges,
                evidenceArtifacts = evidenceArtifacts,
            )?.let { return@runCatching it }
            val runtimeSourceContext = evidenceArtifacts.map { artifact ->
                val matchingScope = confirmedChanges
                    .asSequence()
                    .flatMap { change -> change.editScopes.asSequence() }
                    .firstOrNull { scope ->
                        referToSameFile(scope.filePath, artifact.filePath, runtimeContext.project.basePath) &&
                            scope.startLine == artifact.startLine &&
                            scope.endLine == artifact.endLine
                    }
                SourceSnippetContext(
                    nodeId = matchingScope?.targetNodeId.orEmpty(),
                    filePath = artifact.filePath,
                    startLine = artifact.startLine,
                    endLine = artifact.endLine,
                    snippet = artifact.snippet,
                )
            }
            runtimeContext.requireWithinDeadline()
            val result = codegenExecutor.invoke(
                input.copy(
                    generationContext = input.generationContext.copy(
                        confirmedChanges = confirmedChanges,
                        sourceContext = runtimeSourceContext,
                    ),
                ),
                runtimeContext,
                state,
            )
            runtimeContext.requireWithinDeadline()
            val invalidDraft = result.drafts.firstOrNull { draft ->
                !(toolRegistry.require("validate_edit_scope").invoke(
                    input = mapOf("draft" to draft),
                    context = ToolExecutionContext(
                        project = runtimeContext.project,
                        snapshot = runtimeContext.snapshotSupplier() ?: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(),
                        artifactStore = runtimeContext.artifactStore,
                        runBudget = state.budget,
                    ),
                ).payload["valid"] as? Boolean ?: false) ||
                    !validationToolFacade.hasReadEvidence(draft, evidenceArtifacts, runtimeContext.project.basePath) ||
                    !(toolRegistry.require("check_writable_draft").invoke(
                        input = mapOf("draft" to draft),
                        context = ToolExecutionContext(
                            project = runtimeContext.project,
                            snapshot = runtimeContext.snapshotSupplier() ?: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(),
                            artifactStore = runtimeContext.artifactStore,
                            runBudget = state.budget,
                        ),
                    ).payload["writable"] as? Boolean ?: false)
            }
            if (invalidDraft != null) {
                return@runCatching AgentStepExecutionResult.fail(
                    state.copy(
                        phase = AgentRunPhase.FAILED,
                        budget = state.budget.recordStep(),
                        stepIndex = state.stepIndex + 1,
                        failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                        stepRecords = state.stepRecords + AgentStepRecord(
                            stepIndex = state.stepIndex,
                            phase = AgentRunPhase.FAILED,
                                summary = "validate-generated-drafts",
                                toolName = "validate_edit_scope",
                                nodeId = invalidDraft.sourceNodeId,
                            ),
                        lastModelOutput = "existing-file 草稿缺少合法 scope 或未读取目标代码。",
                    ),
                )
            }
            val artifactRef = runtimeContext.artifactStore.save(
                CodeDraftArtifact(
                    artifactId = "codegen-current",
                    draftResult = result,
                ),
            )
            AgentStepExecutionResult.complete(
                state.copy(
                    phase = AgentRunPhase.SUCCEEDED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + artifactRef,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.SUCCEEDED,
                        summary = "generate-code-drafts",
                    ),
                    lastModelOutput = "已生成代码草稿。",
                ),
            )
        }.getOrElse { throwable ->
            if (throwable is AgentRuntimeDeadlineExceededException) {
                return AgentStepExecutionResult.fail(
                    state.copy(
                        phase = AgentRunPhase.FAILED,
                        budget = state.budget.recordStep(),
                        stepIndex = state.stepIndex + 1,
                        failureReason = AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED,
                        lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
            AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                    lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
        }
    }

    private fun prevalidateExistingFileTargets(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        confirmedChanges: List<com.charmnight.linkgraph.workbench.DraftWorkbenchEntry>,
        evidenceArtifacts: List<CodeEvidenceArtifact>,
    ): AgentStepExecutionResult.Fail? {
        val snapshot = runtimeContext.snapshotSupplier() ?: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot()
        val context = ToolExecutionContext(
            project = runtimeContext.project,
            snapshot = snapshot,
            artifactStore = runtimeContext.artifactStore,
            runBudget = state.budget,
        )
        confirmedChanges
            .flatMap { change -> change.editScopes }
            .distinctBy { scope -> "${scope.scopeId}:${scope.filePath}:${scope.startLine}:${scope.endLine}" }
            .forEach { scope ->
                val probeDraft = buildScopeProbeDraft(scope)
                val valid = toolRegistry.require("validate_edit_scope").invoke(
                    input = mapOf("draft" to probeDraft),
                    context = context,
                ).payload["valid"] as? Boolean ?: false
                val writable = toolRegistry.require("check_writable_draft").invoke(
                    input = mapOf("draft" to probeDraft),
                    context = context,
                ).payload["writable"] as? Boolean ?: false
                val hasEvidence = validationToolFacade.hasReadEvidence(probeDraft, evidenceArtifacts, runtimeContext.project.basePath)
                if (!valid || !writable || !hasEvidence) {
                    return AgentStepExecutionResult.Fail(
                        state.copy(
                            phase = AgentRunPhase.FAILED,
                            budget = state.budget.recordStep(),
                            stepIndex = state.stepIndex + 1,
                            stepRecords = state.stepRecords + AgentStepRecord(
                                stepIndex = state.stepIndex,
                                phase = AgentRunPhase.FAILED,
                                summary = "prevalidate-existing-file-targets",
                                toolName = if (!valid) "validate_edit_scope" else "check_writable_draft",
                                nodeId = scope.targetNodeId,
                            ),
                            failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                            lastModelOutput = "existing-file 目标在生成前未通过 scope、证据或可写性校验。",
                        ),
                    )
                }
            }
        return null
    }

    private fun buildScopeProbeDraft(scope: EditScope): GeneratedCodeDraft {
        val probeKind = scope.allowedChangeKinds
            .asSequence()
            .mapNotNull { kind ->
                when (kind) {
                    CodeEditOperationKind.REPLACE_METHOD_BODY.name,
                    "REPLACE_SYMBOL_BODY" -> CodeEditOperationKind.REPLACE_METHOD_BODY
                    CodeEditOperationKind.REPLACE_METHOD_BLOCK.name -> CodeEditOperationKind.REPLACE_METHOD_BLOCK
                    CodeEditOperationKind.INSERT_METHOD_AFTER.name -> CodeEditOperationKind.INSERT_METHOD_AFTER
                    CodeEditOperationKind.ADD_IMPORT.name -> CodeEditOperationKind.ADD_IMPORT
                    CodeEditOperationKind.ADD_FIELD.name -> CodeEditOperationKind.ADD_FIELD
                    else -> null
                }
            }
            .firstOrNull()
            ?: CodeEditOperationKind.REPLACE_METHOD_BODY
        return GeneratedCodeDraft(
            id = "scope-probe-${scope.scopeId}",
            sourceNodeId = scope.targetNodeId,
            title = "scope probe",
            targetPath = scope.filePath,
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "scope-probe-${scope.scopeId}",
                    filePath = scope.filePath,
                    scopeId = scope.scopeId,
                    kind = probeKind,
                    payload = "/* scope probe */",
                ),
            ),
            editScopes = listOf(scope),
        )
    }

    private fun extractConfirmedIntents(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): List<com.charmnight.linkgraph.workbench.DraftWorkbenchEntry> {
        return state.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<ConfirmedIntentArtifact>()
            .map(ConfirmedIntentArtifact::entry)
            .toList()
    }

    fun interface CodegenExecutor {
        fun invoke(
            input: CodegenCapabilityInput,
            runtimeContext: AgentRuntimeContext,
            state: AgentRunState,
        ): CodeGenerationResult
    }

    private fun referToSameFile(
        left: String,
        right: String,
        projectBasePath: String?,
    ): Boolean {
        val leftResolved = com.charmnight.linkgraph.codegen.ProjectPathNormalizer.resolvePath(left, projectBasePath)
        val rightResolved = com.charmnight.linkgraph.codegen.ProjectPathNormalizer.resolvePath(right, projectBasePath)
        if (leftResolved != null && rightResolved != null) {
            return leftResolved == rightResolved
        }
        return left.replace('\\', '/') == right.replace('\\', '/')
    }
}

internal data class CodegenCapabilityInput(
    /** 代码生成上下文。 */
    val generationContext: GenerationContext,
    /** 生成计划。 */
    val plan: GenerationPlan?,
)
