package com.charmnight.linkgraph.ui

/**
 * Bootstrap payload 组装器（P2-1 真正的架构分解）。
 *
 * 从 GraphEditorPageRenderer 抽出的独立 class，负责把 GraphEditorStateSnapshot
 * 的全部域状态序列化为单个 LinkedHashMap，作为前端 bootstrap JSON 的根对象。
 */
internal class BootstrapPayloadAssembler(
    private val renderer: GraphEditorPageRenderer,
) {
    fun assemble(
        snapshot: GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): LinkedHashMap<String, Any?> {
        val domainStates = snapshot.domainStates()
        val workspaceState = domainStates.workspace
        val graphViewsState = domainStates.graphViews
        val reviewState = domainStates.review
        val generationState = domainStates.generation
        val assistantState = domainStates.assistant
        val navigationState = domainStates.navigation
        val transportState = domainStates.transport
        val factSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_FACT] ?: GraphSceneState()
        val flowchartSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_FLOWCHART] ?: GraphSceneState()
        val resourceSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_RESOURCE_RELATION] ?: GraphSceneState()
        val architectureSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH] ?: GraphSceneState()
        val classDiagramSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_CLASS_DIAGRAM] ?: GraphSceneState()
        val reviewGraphSceneState = graphViewsState.sceneStates[GraphSceneId.WORKSPACE_REVIEW_GRAPH] ?: GraphSceneState()
        val payload = linkedMapOf<String, Any?>()
        payload["analysisDisplayMode"] = graphViewsState.analysisDisplayMode.name
        payload["currentSceneId"] = graphViewsState.currentSceneId.name
        payload["workspaceGraph"] = renderer.documentToMap(workspaceState.workspaceGraph, includeFullContent = true)
        payload["workspaceBaseGraph"] = renderer.documentToMap(workspaceState.workspaceBaseGraph, includeFullContent = true)
        payload["semanticFactGraph"] = renderer.documentToMap(workspaceState.semanticFactGraph, includeFullContent = false)
        payload["designBaselineGraph"] = workspaceState.designBaselineGraph?.let { renderer.documentToMap(it, includeFullContent = false) }
        payload["sceneStates"] = sceneStatesToDto(graphViewsState.sceneStates)
        payload["factGraphView"] = renderer.factGraphViewToMap(graphViewsState.factGraphView, factSceneState.layoutState)
        payload["flowchartView"] = renderer.flowchartViewToMap(graphViewsState.flowchartView, flowchartSceneState.layoutState)
        payload["resourceRelationView"] = renderer.resourceRelationViewToMap(graphViewsState.resourceRelationView, resourceSceneState.layoutState)
        payload["architectureGraphView"] = renderer.architectureGraphViewToMap(graphViewsState.architectureGraphView, architectureSceneState.layoutState)
        payload["classDiagramView"] = renderer.classDiagramViewToMap(graphViewsState.classDiagramView, classDiagramSceneState.layoutState)
        payload["reviewGraphView"] = renderer.reviewGraphViewToMap(graphViewsState.reviewGraphView, reviewGraphSceneState.layoutState)
        payload["indexedGraphRequestStates"] = graphViewsState.indexedGraphRequestStates.entries.associate { (view, requestState) -> view.name to renderer.requestStateToMap(requestState) }
        payload["draftPatchPreview"] = generationState.draftPatchPreview?.let { renderer.patchToDto(it) }
        payload["draftWorkbenchState"] = renderer.draftWorkbenchStateToMap(generationState.draftWorkbenchState)
        payload["canUndoDraftPatchApply"] = generationState.draftPatchUndoState != null
        payload["lastAppliedDraftPatchSummary"] = generationState.draftPatchUndoState?.patchPreview?.summary
        payload["lastDraftPatchApplyResult"] = generationState.lastDraftPatchApplyResult?.let { renderer.draftPatchApplyResultToMap(it) }
        payload["qaResult"] = reviewState.qaResult?.let { renderer.patchResultToMap(it, artifactRefs.qaPromptPreviewArtifactId) }
        payload["qaRequestState"] = renderer.requestStateToMap(reviewState.qaRequestState, renderer.hasPromptPreview(reviewState.qaResult?.promptPreview, artifactRefs.qaPromptPreviewArtifactId))
        payload["qaRequestRecoveryState"] = renderer.qaRequestRecoveryStateToDto(reviewState.qaRequestRecoveryState)
        payload["runtimeArtifactSummaries"] = assistantState.runtimeArtifactSummaries.mapValues { (_, summaries) ->
            summaries.map { s -> linkedMapOf<String, Any?>("artifactId" to s.artifactId, "artifactType" to s.artifactType, "title" to s.title, "description" to s.description) }
        }
        payload["diffReviewResult"] = reviewState.diffReviewResult?.let { renderer.patchResultToMap(it, artifactRefs.diffReviewPromptPreviewArtifactId) }
        payload["diffReviewRequestState"] = renderer.requestStateToMap(reviewState.diffReviewRequestState, renderer.hasPromptPreview(reviewState.diffReviewResult?.promptPreview, artifactRefs.diffReviewPromptPreviewArtifactId))
        payload["graphBeautificationResult"] = reviewState.graphBeautificationResult?.let { renderer.beautificationResultToMap(it, artifactRefs.beautificationPromptPreviewArtifactId) }
        payload["graphBeautificationRequestState"] = renderer.requestStateToMap(reviewState.graphBeautificationRequestState, renderer.hasPromptPreview(reviewState.graphBeautificationResult?.promptPreview, artifactRefs.beautificationPromptPreviewArtifactId))
        payload["mermaidIssues"] = workspaceState.mermaidIssues.map { i -> linkedMapOf<String, Any?>("category" to i.category.name, "code" to i.code, "message" to i.message, "line" to i.line, "nodeId" to i.nodeId, "edgeId" to i.edgeId) }
        payload["diffItems"] = workspaceState.diff?.entries.orEmpty().map { e -> linkedMapOf<String, Any?>("id" to e.elementId, "title" to renderer.resolveDiffTitle(e, workspaceState.workspaceGraph), "status" to e.status.name, "description" to (e.message ?: e.fields.joinToString())) }
        payload["syncPreviewItems"] = workspaceState.syncPreviewItems.map { i -> linkedMapOf<String, Any?>("id" to i.id, "title" to i.title, "description" to i.description, "risk" to i.risk.name) }
        payload["draftVersion"] = generationState.draftVersion
        payload["generationPlan"] = generationState.generationPlan?.let { p -> generationPlanToDto(p, artifactRefs.generationPlanPromptPreviewArtifactId) }
        payload["generationPlanDraftVersion"] = generationState.generationPlanDraftVersion
        payload["generationPlanRequestState"] = renderer.requestStateToMap(generationState.generationPlanRequestState, renderer.hasPromptPreview(generationState.generationPlan?.promptPreview, artifactRefs.generationPlanPromptPreviewArtifactId))
        payload["draftValidationState"] = generationState.draftValidationState?.let { renderer.draftValidationStateToMap(it) }
        payload["generationPlanDiscussionSession"] = generationState.generationPlanDiscussionSession?.let { s -> renderer.generationPlanDiscussionSessionToMap(s, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) }
        payload["generationPlanDiscussionRequestState"] = renderer.requestStateToMap(generationState.generationPlanDiscussionRequestState, renderer.hasPromptPreview(generationState.generationPlanDiscussionSession?.promptPreview, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId))
        payload["generatedCodeDrafts"] = generationState.generatedCodeDrafts.map { d -> renderer.generatedCodeDraftToMap(d, artifactRefs.generatedCodeDraftContentArtifactIds[d.id]) }
        payload["generatedCodeDraftVersion"] = generationState.generatedCodeDraftVersion
        payload["generatedCodeDraftWarnings"] = generationState.generatedCodeDraftWarnings
        payload["generatedCodeDraftSource"] = generationState.generatedCodeDraftSource?.name
        payload["generatedCodeDraftPromptPreviewArtifactId"] = artifactRefs.generatedCodeDraftPromptPreviewArtifactId
        payload["codeDraftRequestState"] = renderer.requestStateToMap(generationState.codeDraftRequestState, renderer.hasPromptPreview(generationState.generatedCodeDraftPromptPreview, artifactRefs.generatedCodeDraftPromptPreviewArtifactId))
        payload["codeEligibilityDecision"] = generationState.codeEligibilityDecision?.let { renderer.stageEligibilityDecisionToDto(it) }
        payload["generatedCodeDraftWriteReport"] = generationState.generatedCodeDraftWriteReport?.let { r -> linkedMapOf<String, Any?>("writtenFiles" to r.writtenFiles, "skippedFiles" to r.skippedFiles, "warnings" to r.warnings) }
        payload["semanticRevision"] = workspaceState.semanticRevision
        payload["workspaceRevision"] = workspaceState.workspaceRevision
        payload["snapshotRevision"] = transportState.snapshotRevision
        payload["sourceNavigationState"] = renderer.sourceNavigationStateToDto(navigationState.sourceNavigationState)
        payload["assistantSessionState"] = GraphEditorAssistantSessionRenderer.assistantSessionStateToDto(assistantState.sessionState)
        payload["assistantResultStore"] = renderer.assistantResultStoreToMap(assistantState.resultStore, artifactRefs.assistantResultArtifacts)
        payload["lastMessageType"] = transportState.lastMessageType
        payload["lastGraphSource"] = transportState.lastGraphSource
        payload["operationFeedback"] = transportState.operationFeedback?.let { f -> linkedMapOf<String, Any?>("level" to f.level.name, "message" to f.message) }
        return payload
    }
}
