package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.PreparedCodeEdit
import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphEditOperation
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
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationStep
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.SourceSnippetContext
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

private fun GraphSceneState.freeze(): GraphSceneState {
    return copy(
        layoutState = layoutState.copy(positions = layoutState.positions.toMap()),
        collapsedNodeIds = collapsedNodeIds.toSet(),
    )
}

private fun GraphDocument.freeze(): GraphDocument {
    return copy(
        nodes = nodes.map { node -> node.freeze() },
        edges = edges.map { edge -> edge.freeze() },
        patch = patch?.freeze(),
    )
}

private fun GraphNode.freeze(): GraphNode {
    return copy(
        inputs = inputs.toList(),
        outputs = outputs.toList(),
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

private fun GraphEdge.freeze(): GraphEdge {
    return copy(
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

private fun GraphDiff.freeze(): GraphDiff {
    return copy(
        fields = fields.toList(),
        entries = entries.map { entry ->
            entry.copy(fields = entry.fields.toList())
        },
    )
}

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

private fun GraphEditRejected.freeze(): GraphEditRejected =
    copy(issues = issues.toList())

private fun GraphEditTransaction.freeze(): GraphEditTransaction =
    copy(
        graphBeforeApply = graphBeforeApply.freeze(),
        graphAfterApply = graphAfterApply.freeze(),
        request = request.copy(operations = request.operations.map { operation -> operation.freeze() }),
        appliedOperations = appliedOperations.map { operation -> operation.freeze() },
    )

private fun GraphEditOperation.freeze(): GraphEditOperation =
    when (this) {
        is GraphEditOperation.UpsertNode -> copy(node = node.freeze())
        is GraphEditOperation.RemoveNode -> copy()
        is GraphEditOperation.UpsertEdge -> copy(edge = edge.freeze())
        is GraphEditOperation.RemoveEdge -> copy()
    }

private fun ArchitectureGraphResult.freeze(): ArchitectureGraphResult {
    return copy(
        visibleGraph = visibleGraph.freeze(),
        fullGraph = fullGraph.freeze(),
        summary = summary.freeze(),
        projectionIndex = projectionIndex.freeze(),
        presentation = presentation.freeze(),
    )
}

private fun ArchitectureGraphSummary.freeze(): ArchitectureGraphSummary {
    return copy(
        indexed = indexed?.freeze(),
        projectStructureRelationGroups = projectStructureRelationGroups.map { group -> group.freeze() },
    )
}

private fun ProjectStructureRelationGroup.freeze(): ProjectStructureRelationGroup {
    return copy(
        relationKinds = relationKinds.toList(),
        sourceRelationIds = sourceRelationIds.toList(),
        sampleEvidenceRefs = sampleEvidenceRefs.toList(),
    )
}

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

private fun ClassDiagramSummary.freeze(): ClassDiagramSummary {
    return copy(indexed = indexed?.freeze())
}

private fun GraphProjectionIndex.freeze(): GraphProjectionIndex {
    return copy(
        nodeMappings = nodeMappings.mapValues { (_, mapping) -> mapping.freeze() }.toMap(),
        edgeMappings = edgeMappings.mapValues { (_, mapping) -> mapping.freeze() }.toMap(),
    )
}

private fun GraphProjectionNodeMapping.freeze(): GraphProjectionNodeMapping {
    return copy(
        canonicalNodeIds = canonicalNodeIds.toList(),
        editableCommandKinds = editableCommandKinds.toSet(),
    )
}

private fun GraphProjectionEdgeMapping.freeze(): GraphProjectionEdgeMapping {
    return copy(
        canonicalEdgeIds = canonicalEdgeIds.toList(),
        canonicalPathNodeIds = canonicalPathNodeIds.toList(),
        editableCommandKinds = editableCommandKinds.toSet(),
    )
}

private fun GraphViewPresentation.freeze(): GraphViewPresentation {
    return copy(
        lanes = lanes.toList(),
        hiddenBuckets = hiddenBuckets.map { bucket -> bucket.freeze() },
        controls = controls.freeze(),
    )
}

private fun GraphHiddenBucket.freeze(): GraphHiddenBucket {
    return copy(
        nodeIds = nodeIds.toList(),
        edgeIds = edgeIds.toList(),
    )
}

private fun GraphPresentationControls.freeze(): GraphPresentationControls {
    return copy(availableScopes = availableScopes.toList())
}

private fun IndexedGraphSummary.freeze(): IndexedGraphSummary {
    return copy(
        relationKinds = relationKinds.toSet(),
        freshness = freshness.copy(pendingFileSamples = freshness.pendingFileSamples.toList()),
        visibilityReasons = visibilityReasons.toList(),
    )
}

private fun ClassUsageSearchResult.freeze(): ClassUsageSearchResult {
    return copy(groups = groups.map { group -> group.freeze() })
}

private fun ClassUsageGroup.freeze(): ClassUsageGroup {
    return copy(usages = usages.toList())
}

private fun DraftWorkbenchState.freeze(): DraftWorkbenchState {
    return copy(
        draftChanges = draftChanges.map { entry -> entry.freeze() },
        draftNotes = draftNotes.map { entry -> entry.freeze() },
    )
}

private fun DraftWorkbenchEntry.freeze(): DraftWorkbenchEntry {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

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

private fun ResultEvidenceFinding.freeze(): ResultEvidenceFinding {
    return copy(references = references.toList())
}

private fun CandidateDraftChange.freeze(): CandidateDraftChange {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

private fun InvestigationThread.freeze(): InvestigationThread {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
    )
}

private fun InvestigationTurnOutcome.freeze(): InvestigationTurnOutcome {
    return copy(
        evidenceDelta = evidenceDelta.freeze(),
        observedNodeIds = observedNodeIds.toList(),
        observedFilePaths = observedFilePaths.toList(),
    )
}

private fun InvestigationEvidenceDelta.freeze(): InvestigationEvidenceDelta {
    return copy(
        addedNodeIds = addedNodeIds.toList(),
        addedFilePaths = addedFilePaths.toList(),
    )
}

private fun QaConversationSession.freeze(): QaConversationSession {
    return copy(
        messages = messages.toList(),
        candidateChanges = candidateChanges.map { change -> change.freeze() },
        investigationThreads = investigationThreads.map { thread -> thread.freeze() },
        turnOutcomes = turnOutcomes.map { outcome -> outcome.freeze() },
    )
}

private fun QaRequestRecoveryState.freeze(): QaRequestRecoveryState {
    return copy(
        lastSubmittedRequest = lastSubmittedRequest?.freeze(),
        lastFailedRequest = lastFailedRequest?.freeze(),
    )
}

private fun ReplayableQaRequest.freeze(): ReplayableQaRequest {
    return copy(
        selectedNodeIds = selectedNodeIds.toList(),
        baseSession = baseSession?.freeze(),
    )
}

private fun AssistantSessionState.freeze(): AssistantSessionState {
    return copy(
        context = context.freeze(),
        composer = composer.freeze(),
        nextResultSequence = nextResultSequence,
        turns = turns.map { turn -> turn.freeze() },
    )
}

private fun com.charmnight.linkgraph.workbench.AssistantComposerState.freeze(): com.charmnight.linkgraph.workbench.AssistantComposerState {
    return copy(target = target.freeze())
}

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

private fun AssistantResultStore.freeze(): AssistantResultStore {
    return copy(results = results.mapValues { (_, entry) -> entry.freeze() }.toMap())
}

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

private fun AssistantTurnRef.freeze(): AssistantTurnRef {
    return copy(context = context.freeze())
}

private fun AssistantContextSnapshot.freeze(): AssistantContextSnapshot {
    return copy(
        selectedNodeIds = selectedNodeIds.toList(),
        selectedDiffItemIds = selectedDiffItemIds.toList(),
    )
}

private fun GenerationPlan.freeze(): GenerationPlan {
    return copy(
        items = items.toList(),
        warnings = warnings.toList(),
    )
}

private fun DraftValidationState.freeze(): DraftValidationState {
    return copy(
        unresolvedThreadIds = unresolvedThreadIds.toList(),
        unresolvedThreads = unresolvedThreads.map { thread -> thread.freeze() },
    )
}

private fun GenerationPlanDiscussionSession.freeze(): GenerationPlanDiscussionSession {
    return copy(messages = messages.toList())
}

private fun GraphBeautificationResult.freeze(): GraphBeautificationResult {
    return copy(
        steps = steps.map { step -> step.freeze() },
        warnings = warnings.toList(),
    )
}

private fun GraphBeautificationStep.freeze(): GraphBeautificationStep {
    return copy(
        evidence = evidence.map { finding -> finding.freeze() },
        followUpQuestions = followUpQuestions.toList(),
        downstreamTargets = downstreamTargets.toList(),
    )
}

private fun GeneratedCodeDraftWriteReport.freeze(): GeneratedCodeDraftWriteReport {
    return copy(
        writtenFiles = writtenFiles.toList(),
        skippedFiles = skippedFiles.toList(),
        warnings = warnings.toList(),
    )
}

private fun PreparedCodeEdit.freeze(): PreparedCodeEdit {
    return copy(warnings = warnings.toList())
}

private fun GeneratedCodeDraft.freeze(): GeneratedCodeDraft {
    return copy(
        editOperations = editOperations.map { operation ->
            operation.copy(warnings = operation.warnings.toList())
        },
        editScopes = editScopes.map { scope ->
            scope.freeze()
        },
        preparedEdits = preparedEdits.map { edit -> edit.freeze() },
        warnings = warnings.toList(),
    )
}

private fun com.charmnight.linkgraph.llm.EditScope.freeze(): com.charmnight.linkgraph.llm.EditScope {
    return copy(
        allowedChangeKinds = allowedChangeKinds.toList(),
        supportingFindingIds = supportingFindingIds.toList(),
    )
}

private fun SourceSnippetContext.freeze(): SourceSnippetContext = copy()

private fun com.charmnight.linkgraph.llm.EvidenceTraceEntry.freeze(): com.charmnight.linkgraph.llm.EvidenceTraceEntry {
    return copy(mappingTrace = mappingTrace.toList())
}
