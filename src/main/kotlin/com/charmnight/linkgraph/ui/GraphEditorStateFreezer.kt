package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.PreparedCodeEdit
import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEdgeEditInput
import com.charmnight.linkgraph.application.model.GraphNodeEditInput
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphSummary
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.architecture.ClassDiagramSummary
import com.charmnight.linkgraph.architecture.ProjectStructureRelationGroup
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphBeautificationStep
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.presentation.GraphHiddenBucket
import com.charmnight.linkgraph.presentation.GraphPresentationControls
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.InvestigationEvidenceDelta
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcome
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.AssistantContextSnapshot
import com.charmnight.linkgraph.workbench.AssistantResultStore
import com.charmnight.linkgraph.workbench.AssistantResultStoreEntry
import com.charmnight.linkgraph.workbench.AssistantSessionState
import com.charmnight.linkgraph.workbench.AssistantTurnRef

/**
 * 把图谱编辑器的运行时状态冻结成可持久化、可对比的不可变快照。
 *
 * 通过递归调用各领域子状态的同名 freeze 函数，将内部可能为可变集合（Map、List、Set）的字段
 * 复制成快照集合，避免后续编辑操作意外改动已存档的状态。
 */
internal fun GraphEditorStateSnapshot.freeze(): GraphEditorStateSnapshot {
    val domainStates = domainStates()
    val workspaceState = domainStates.workspace
    val graphViewsState = domainStates.graphViews
    val assistantState = domainStates.assistant
    val reviewState = domainStates.review
    val generationState = domainStates.generation
    return copy(
        semanticFactGraph = workspaceState.semanticFactGraph.freeze(),
        workspaceBaseGraph = workspaceState.workspaceBaseGraph.freeze(),
        workspaceGraph = workspaceState.workspaceGraph.freeze(),
        designBaselineGraph = workspaceState.designBaselineGraph?.freeze(),
        trustedNavigationNodes = workspaceState.trustedNavigationNodes.mapValues { (_, node) -> node.freeze() }.toMap(),
        factGraphView = graphViewsState.factGraphView.copy(
            visibleGraph = graphViewsState.factGraphView.visibleGraph.freeze(),
            fullGraph = graphViewsState.factGraphView.fullGraph.freeze(),
        ),
        flowchartView = graphViewsState.flowchartView.copy(
            visibleGraph = graphViewsState.flowchartView.visibleGraph.freeze(),
            fullGraph = graphViewsState.flowchartView.fullGraph.freeze(),
        ),
        resourceRelationView = graphViewsState.resourceRelationView.copy(
            visibleGraph = graphViewsState.resourceRelationView.visibleGraph.freeze(),
            fullGraph = graphViewsState.resourceRelationView.fullGraph.freeze(),
        ),
        architectureGraphView = graphViewsState.architectureGraphView.freeze(),
        classDiagramView = graphViewsState.classDiagramView.freeze(),
        sceneStates = graphViewsState.sceneStates.mapValues { (_, sceneState) -> sceneState.freeze() }.toMap(),
        draftWorkbenchState = generationState.draftWorkbenchState.freeze(),
        draftPatchPreview = generationState.draftPatchPreview?.freeze(),
        draftPatchUndoState = generationState.draftPatchUndoState?.let { undoState ->
            undoState.copy(
                graphBeforeApply = undoState.graphBeforeApply.freeze(),
                patchPreview = undoState.patchPreview?.freeze(),
            )
        },
        lastDraftPatchApplyResult = generationState.lastDraftPatchApplyResult?.copy(
            appliedNodeIds = generationState.lastDraftPatchApplyResult.appliedNodeIds.toList(),
            appliedEdgeIds = generationState.lastDraftPatchApplyResult.appliedEdgeIds.toList(),
            appliedTargets = generationState.lastDraftPatchApplyResult.appliedTargets.toList(),
        ),
        qaResult = reviewState.qaResult?.freeze(),
        qaRequestRecoveryState = reviewState.qaRequestRecoveryState.freeze(),
        runtimeArtifactSummaries = assistantState.runtimeArtifactSummaries.mapValues { (_, summaries) -> summaries.toList() }.toMap(),
        diffReviewResult = reviewState.diffReviewResult?.freeze(),
        graphBeautificationResult = reviewState.graphBeautificationResult?.freeze(),
        diff = workspaceState.diff?.freeze(),
        diffGraph = workspaceState.diffGraph?.freeze(),
        mermaidIssues = workspaceState.mermaidIssues.toList(),
        syncPreviewItems = workspaceState.syncPreviewItems.toList(),
        lastGraphEditTransaction = workspaceState.lastGraphEditTransaction?.freeze(),
        lastGraphEditRejection = workspaceState.lastGraphEditRejection?.freeze(),
        generationPlan = generationState.generationPlan?.freeze(),
        draftValidationState = generationState.draftValidationState?.freeze(),
        generationPlanDiscussionSession = generationState.generationPlanDiscussionSession?.freeze(),
        generatedCodeDrafts = generationState.generatedCodeDrafts.map { draft -> draft.freeze() },
        generatedCodeDraftWarnings = generationState.generatedCodeDraftWarnings.toList(),
        generatedCodeDraftWriteReport = generationState.generatedCodeDraftWriteReport?.freeze(),
        codeEligibilityDecision = generationState.codeEligibilityDecision?.copy(
            blockingThreadIds = generationState.codeEligibilityDecision.blockingThreadIds.toList(),
            unresolvedThreadIds = generationState.codeEligibilityDecision.unresolvedThreadIds.toList(),
        ),
        assistantSessionState = assistantState.sessionState.freeze(),
        assistantResultStore = assistantState.resultStore.freeze(),
    )
}

/** 冻结单个图场景状态：把布局位置和折叠节点集合转成不可变 Map/Set。 */
private fun GraphSceneState.freeze(): GraphSceneState {
    return copy(
        layoutState = layoutState.copy(positions = layoutState.positions.toMap()),
        collapsedNodeIds = collapsedNodeIds.toSet(),
        invocationExpansionState = invocationExpansionState.freeze(),
    )
}

private fun InvocationExpansionSceneState.freeze(): InvocationExpansionSceneState =
    copy(
        activeExpansionPath = activeExpansionPath.toList(),
        collapsedExpansionIds = collapsedExpansionIds.toSet(),
        activeSiblingByParentContext = activeSiblingByParentContext.toMap(),
    )

/** 冻结整张图：节点、边与挂载的补丁都递归冻结为不可变副本。 */
private fun GraphDocument.freeze(): GraphDocument {
    return copy(
        nodes = nodes.map { node -> node.freeze() },
        edges = edges.map { edge -> edge.freeze() },
        patch = patch?.freeze(),
    )
}

/** 冻结单个节点：把输入输出、差异、证据、元数据等字段都转为不可变集合。 */
private fun GraphNode.freeze(): GraphNode {
    return copy(
        inputs = inputs.toList(),
        outputs = outputs.toList(),
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

/** 冻结单条边：把差异、证据、元数据转为不可变集合。 */
private fun GraphEdge.freeze(): GraphEdge {
    return copy(
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

/** 冻结图差异信息：把顶层字段与每条 diff 条目的字段列表转为不可变集合。 */
private fun GraphDiff.freeze(): GraphDiff {
    return copy(
        fields = fields.toList(),
        entries = entries.map { entry ->
            entry.copy(fields = entry.fields.toList())
        },
    )
}

/** 冻结图补丁：把每条操作、增删的节点和边 ID 列表冻结为不可变集合。 */
private fun GraphPatch.freeze(): GraphPatch {
    return copy(
        operations = operations.map { operation ->
            operation.copy(
                node = operation.node?.freeze(),
                edge = operation.edge?.freeze(),
                metadata = operation.metadata.toMap(),
            )
        },
        addedNodeIds = addedNodeIds.toList(),
        removedNodeIds = removedNodeIds.toList(),
        addedEdgeIds = addedEdgeIds.toList(),
        removedEdgeIds = removedEdgeIds.toList(),
    )
}

/** 冻结被拒绝的图编辑请求：把收集到的拒绝原因 issue 列表转为不可变副本。 */
private fun GraphEditRejected.freeze(): GraphEditRejected =
    copy(issues = issues.toList())

/** 冻结图编辑事务：连同应用前后的图副本、原始与已应用操作一起冻结。 */
private fun GraphEditTransaction.freeze(): GraphEditTransaction =
    copy(
        graphBeforeApply = graphBeforeApply.freeze(),
        graphAfterApply = graphAfterApply.freeze(),
        request = request.copy(operations = request.operations.map { operation -> operation.freeze() }),
        appliedOperations = appliedOperations.map { operation -> operation.freeze() },
    )

/** 冻结单条图编辑操作，按 upsert/remove 分支递归冻结携带的节点或边。 */
private fun GraphEditOperation.freeze(): GraphEditOperation =
    when (this) {
        is GraphEditOperation.UpsertNode -> copy(node = node.freeze())
        is GraphEditOperation.RemoveNode -> copy()
        is GraphEditOperation.UpsertEdge -> copy(edge = edge.freeze())
        is GraphEditOperation.RemoveEdge -> copy()
    }

private fun GraphNodeEditInput.freeze(): GraphNodeEditInput =
    copy(inputs = inputs.toList(), outputs = outputs.toList(), metadata = metadata.toMap())

private fun GraphEdgeEditInput.freeze(): GraphEdgeEditInput =
    copy(metadata = metadata.toMap())

/** 冻结架构图结果：连同可见图、完整图、摘要、投影索引与展示控制一起冻结。 */
private fun ArchitectureGraphResult.freeze(): ArchitectureGraphResult {
    return copy(
        visibleGraph = visibleGraph.freeze(),
        fullGraph = fullGraph.freeze(),
        summary = summary.freeze(),
        projectionIndex = projectionIndex.freeze(),
        presentation = presentation.freeze(),
    )
}

/** 冻结架构图摘要：冻结索引化摘要以及项目结构关系分组列表。 */
private fun ArchitectureGraphSummary.freeze(): ArchitectureGraphSummary {
    return copy(
        indexed = indexed?.freeze(),
        projectStructureRelationGroups = projectStructureRelationGroups.map { group -> group.freeze() },
    )
}

/** 冻结项目结构关系分组：把关系种类、来源关系 ID 和证据引用列表转为不可变集合。 */
private fun ProjectStructureRelationGroup.freeze(): ProjectStructureRelationGroup {
    return copy(
        relationKinds = relationKinds.toList(),
        sourceRelationIds = sourceRelationIds.toList(),
        sampleEvidenceRefs = sampleEvidenceRefs.toList(),
    )
}

/** 冻结类图结果：冻结可见图、完整图、摘要、投影索引、展示控制和类使用结果。 */
private fun ClassDiagramResult.freeze(): ClassDiagramResult {
    return copy(
        visibleGraph = visibleGraph.freeze(),
        fullGraph = fullGraph.freeze(),
        summary = summary.freeze(),
        projectionIndex = projectionIndex.freeze(),
        presentation = presentation.freeze(),
        usage = usage?.freeze(),
    )
}

/** 冻结类图摘要：递归冻结其携带的索引化摘要。 */
private fun ClassDiagramSummary.freeze(): ClassDiagramSummary {
    return copy(indexed = indexed?.freeze())
}

/** 冻结图投影索引：把节点映射和边映射的内部集合逐项冻结。 */
private fun GraphProjectionIndex.freeze(): GraphProjectionIndex {
    return copy(
        nodeMappings = nodeMappings.mapValues { (_, mapping) -> mapping.freeze() }.toMap(),
        edgeMappings = edgeMappings.mapValues { (_, mapping) -> mapping.freeze() }.toMap(),
    )
}

/** 冻结节点投影映射：把同源节点 ID 和可编辑命令种类集合转为不可变副本。 */
private fun GraphProjectionNodeMapping.freeze(): GraphProjectionNodeMapping {
    return copy(
        canonicalNodeIds = canonicalNodeIds.toList(),
        editableCommandKinds = editableCommandKinds.toSet(),
    )
}

/** 冻结边投影映射：把同源边 ID、同源路径节点 ID 与可编辑命令种类集合冻结。 */
private fun GraphProjectionEdgeMapping.freeze(): GraphProjectionEdgeMapping {
    return copy(
        canonicalEdgeIds = canonicalEdgeIds.toList(),
        canonicalPathNodeIds = canonicalPathNodeIds.toList(),
        editableCommandKinds = editableCommandKinds.toSet(),
    )
}

/** 冻结图视图展示：把泳道、隐藏桶和展示控件冻结为不可变结构。 */
private fun GraphViewPresentation.freeze(): GraphViewPresentation {
    return copy(
        lanes = lanes.toList(),
        hiddenBuckets = hiddenBuckets.map { bucket -> bucket.freeze() },
        controls = controls.freeze(),
    )
}

/** 冻结单个隐藏桶：把被折叠的节点 ID 和边 ID 列表转为不可变集合。 */
private fun GraphHiddenBucket.freeze(): GraphHiddenBucket {
    return copy(
        nodeIds = nodeIds.toList(),
        edgeIds = edgeIds.toList(),
    )
}

/** 冻结展示控件：把可选范围列表转为不可变集合。 */
private fun GraphPresentationControls.freeze(): GraphPresentationControls {
    return copy(availableScopes = availableScopes.toList())
}

/** 冻结索引图汇总信息：把关系种类、新鲜度样例与可见性原因列表冻结。 */
private fun IndexedGraphSummary.freeze(): IndexedGraphSummary {
    return copy(
        relationKinds = relationKinds.toSet(),
        freshness = freshness.copy(pendingFileSamples = freshness.pendingFileSamples.toList()),
        visibilityReasons = visibilityReasons.toList(),
    )
}

/** 冻结类使用查询结果：把每个使用分组递归冻结。 */
private fun ClassUsageSearchResult.freeze(): ClassUsageSearchResult {
    return copy(groups = groups.map { group -> group.freeze() })
}

/** 冻结单个使用分组：把组内使用条目列表转为不可变副本。 */
private fun ClassUsageGroup.freeze(): ClassUsageGroup {
    return copy(usages = usages.toList())
}

/** 冻结草稿工作台状态：把草稿变更与草稿备注条目递归冻结。 */
private fun DraftWorkbenchState.freeze(): DraftWorkbenchState {
    return copy(
        draftChanges = draftChanges.map { entry -> entry.freeze() },
        draftNotes = draftNotes.map { entry -> entry.freeze() },
    )
}

/** 冻结单条草稿条目：把目标步骤、节点 ID、证据、编辑作用域与关联补丁冻结。 */
private fun DraftWorkbenchEntry.freeze(): DraftWorkbenchEntry {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

/** 冻结图问答结果：递归冻结补丁、结论证据、候选变更、风险线程、会话等所有嵌套字段。 */
private fun GraphPatchResult.freeze(): GraphPatchResult {
    return copy(
        patch = patch?.freeze(),
        findings = findings.map { finding -> finding.freeze() },
        candidateChanges = candidateChanges.map { change -> change.freeze() },
        newCandidateChanges = newCandidateChanges.map { change -> change.freeze() },
        investigationThreads = investigationThreads.map { thread -> thread.freeze() },
        latestTurnOutcome = latestTurnOutcome?.freeze(),
        recentTurnOutcomes = recentTurnOutcomes.map { outcome -> outcome.freeze() },
        sourceContext = sourceContext.map { snippet -> snippet.freeze() },
        evidenceTrace = evidenceTrace.map { trace -> trace.freeze() },
        qaSession = qaSession?.freeze(),
        warnings = warnings.toList(),
    )
}

/** 冻结单条结论证据：把引用列表转为不可变集合。 */
private fun ResultEvidenceFinding.freeze(): ResultEvidenceFinding {
    return copy(references = references.toList())
}

/** 冻结候选变更：把目标步骤/节点、证据、编辑作用域与关联补丁冻结。 */
private fun CandidateDraftChange.freeze(): CandidateDraftChange {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

/** 冻结单个风险调查线程：把目标步骤/节点和证据列表冻结。 */
private fun InvestigationThread.freeze(): InvestigationThread {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
    )
}

/** 冻结一轮调查结果：冻结证据增量、已观察到的节点与文件列表。 */
private fun InvestigationTurnOutcome.freeze(): InvestigationTurnOutcome {
    return copy(
        evidenceDelta = evidenceDelta.freeze(),
        observedNodeIds = observedNodeIds.toList(),
        observedFilePaths = observedFilePaths.toList(),
    )
}

/** 冻结证据增量：把新增节点 ID 与文件路径列表转为不可变集合。 */
private fun InvestigationEvidenceDelta.freeze(): InvestigationEvidenceDelta {
    return copy(
        addedNodeIds = addedNodeIds.toList(),
        addedFilePaths = addedFilePaths.toList(),
    )
}

/** 冻结问答会话：把消息、候选变更、风险线程和每轮结果一并冻结。 */
private fun QaConversationSession.freeze(): QaConversationSession {
    return copy(
        messages = messages.toList(),
        candidateChanges = candidateChanges.map { change -> change.freeze() },
        investigationThreads = investigationThreads.map { thread -> thread.freeze() },
        turnOutcomes = turnOutcomes.map { outcome -> outcome.freeze() },
    )
}

/** 冻结问答请求恢复状态：递归冻结上次提交与上次失败的请求，便于断点续跑。 */
private fun QaRequestRecoveryState.freeze(): QaRequestRecoveryState {
    return copy(
        lastSubmittedRequest = lastSubmittedRequest?.freeze(),
        lastFailedRequest = lastFailedRequest?.freeze(),
    )
}

/** 冻结一次可重放的问答请求：冻结选中节点列表以及底层会话。 */
private fun ReplayableQaRequest.freeze(): ReplayableQaRequest {
    return copy(
        selectedNodeIds = selectedNodeIds.toList(),
        baseSession = baseSession?.freeze(),
    )
}

/** 冻结助手会话状态：递归冻结上下文、输入框目标以及每一轮的引用。 */
private fun AssistantSessionState.freeze(): AssistantSessionState {
    return copy(
        context = context.freeze(),
        composer = composer.freeze(),
        nextResultSequence = nextResultSequence,
        turns = turns.map { turn -> turn.freeze() },
    )
}

/** 冻结助手输入框状态：把当前编辑目标冻结为不可变副本。 */
private fun com.charmnight.linkgraph.workbench.AssistantComposerState.freeze(): com.charmnight.linkgraph.workbench.AssistantComposerState {
    return copy(target = target.freeze())
}

/** 冻结输入框目标：按目标种类（新任务、问答恢复、追问、风险调查等）冻结对应的可变列表。 */
private fun com.charmnight.linkgraph.workbench.AssistantComposerTarget.freeze(): com.charmnight.linkgraph.workbench.AssistantComposerTarget {
    return when (this) {
        com.charmnight.linkgraph.workbench.AssistantComposerTarget.NewTask -> this
        is com.charmnight.linkgraph.workbench.AssistantComposerTarget.QaRecovery ->
            copy(selectedNodeIds = selectedNodeIds.toList())
        is com.charmnight.linkgraph.workbench.AssistantComposerTarget.ExplanationFollowUp -> this
        is com.charmnight.linkgraph.workbench.AssistantComposerTarget.GenerationDiscussion -> this
        is com.charmnight.linkgraph.workbench.AssistantComposerTarget.RiskInvestigation ->
            copy(targetNodeIds = targetNodeIds.toList())
    }
}

/** 冻结助手结果存储：把每个结果条目递归冻结，便于整体快照。 */
private fun AssistantResultStore.freeze(): AssistantResultStore {
    return copy(results = results.mapValues { (_, entry) -> entry.freeze() }.toMap())
}

/** 冻结单条助手结果条目：把问答、讲解、计划、讨论、代码草稿、校验等子结果递归冻结。 */
private fun AssistantResultStoreEntry.freeze(): AssistantResultStoreEntry {
    return copy(
        failure = failure,
        qa = qa?.freeze(),
        explanation = explanation?.freeze(),
        generationPlan = generationPlan?.freeze(),
        generationDiscussionSession = generationDiscussionSession?.freeze(),
        codeDrafts = codeDrafts.map { draft -> draft.freeze() },
        codeDraftWarnings = codeDraftWarnings.toList(),
        check = check?.freeze(),
    )
}

/** 冻结对一次助手轮次的引用：把轮次对应的上下文快照冻结。 */
private fun AssistantTurnRef.freeze(): AssistantTurnRef {
    return copy(context = context.freeze())
}

/** 冻结助手上下文快照：把选中的节点和差异条目列表转为不可变集合。 */
private fun AssistantContextSnapshot.freeze(): AssistantContextSnapshot {
    return copy(
        selectedNodeIds = selectedNodeIds.toList(),
        selectedDiffItemIds = selectedDiffItemIds.toList(),
    )
}

/** 冻结生成计划：把计划项和警告列表转为不可变集合。 */
private fun GenerationPlan.freeze(): GenerationPlan {
    return copy(
        items = items.toList(),
        warnings = warnings.toList(),
    )
}

/** 冻结草稿校验状态：把未解决线程 ID 和线程明细冻结为不可变集合。 */
private fun DraftValidationState.freeze(): DraftValidationState {
    return copy(
        unresolvedThreadIds = unresolvedThreadIds.toList(),
        unresolvedThreads = unresolvedThreads.map { thread -> thread.freeze() },
    )
}

/** 冻结生成计划讨论会话：把对话消息冻结为不可变列表。 */
private fun GenerationPlanDiscussionSession.freeze(): GenerationPlanDiscussionSession {
    return copy(messages = messages.toList())
}

/** 冻结图讲解结果：递归冻结每一步讲解与警告列表。 */
private fun GraphBeautificationResult.freeze(): GraphBeautificationResult {
    return copy(
        steps = steps.map { step -> step.freeze() },
        warnings = warnings.toList(),
    )
}

/** 冻结单个讲解步骤：把证据、追问问题与下游目标列表冻结为不可变集合。 */
private fun GraphBeautificationStep.freeze(): GraphBeautificationStep {
    return copy(
        evidence = evidence.map { finding -> finding.freeze() },
        followUpQuestions = followUpQuestions.toList(),
        downstreamTargets = downstreamTargets.toList(),
    )
}

/** 冻结代码草稿写入报告：把已写入文件、跳过文件与警告列表冻结。 */
private fun GeneratedCodeDraftWriteReport.freeze(): GeneratedCodeDraftWriteReport {
    return copy(
        writtenFiles = writtenFiles.toList(),
        skippedFiles = skippedFiles.toList(),
        warnings = warnings.toList(),
    )
}

/** 冻结已准备的代码编辑：把准备阶段产生的警告列表转为不可变集合。 */
private fun PreparedCodeEdit.freeze(): PreparedCodeEdit {
    return copy(warnings = warnings.toList())
}

/** 冻结生成的代码草稿：递归冻结编辑操作、编辑作用域、已准备编辑和警告列表。 */
private fun GeneratedCodeDraft.freeze(): GeneratedCodeDraft {
    return copy(
        command = when (val command = command) {
            is com.charmnight.linkgraph.codegen.CodeDraftCommand.CreateFile -> command.copy()
            is com.charmnight.linkgraph.codegen.CodeDraftCommand.PatchExistingFile -> command.copy(
                operations = command.operations.map { operation ->
                    operation.copy(warnings = operation.warnings.toList())
                },
                scopes = command.scopes.map { scope -> scope.freeze() },
            )
        },
        preparedEdits = preparedEdits.map { edit -> edit.freeze() },
        warnings = warnings.toList(),
    )
}

/** 冻结代码编辑作用域：把允许的改动种类和支撑发现 ID 列表冻结。 */
private fun com.charmnight.linkgraph.agent.model.EditScope.freeze(): com.charmnight.linkgraph.agent.model.EditScope {
    return copy(
        allowedChangeKinds = allowedChangeKinds.toList(),
        supportingFindingIds = supportingFindingIds.toList(),
    )
}

/** 源码片段上下文所有字段均为不可变，调用 copy 返回自身或副本即可完成冻结。 */
private fun SourceSnippetContext.freeze(): SourceSnippetContext = copy()

/** 冻结证据轨迹条目：把投影节点到真实源码的映射轨迹冻结为不可变列表。 */
private fun com.charmnight.linkgraph.agent.model.EvidenceTraceEntry.freeze(): com.charmnight.linkgraph.agent.model.EvidenceTraceEntry {
    return copy(mappingTrace = mappingTrace.toList())
}
