package com.charmnight.linkgraph.ui

/**
 * Bootstrap payload 组装器（P2-1 真正的架构分解，P2-6 完整 DTO 化）。
 *
 * 从 GraphEditorPageRenderer 抽出的独立 class，负责把 GraphEditorStateSnapshot
 * 的全部域状态序列化为单个 [BootstrapPayloadDto]，
 * 作为前端 bootstrap JSON 的根对象（Gson 反射序列化为 JSON）。
 */
internal class BootstrapPayloadAssembler(
    private val renderer: GraphEditorPageRenderer,
) {
    fun assemble(
        snapshot: GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): BootstrapPayloadDto {
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
        return BootstrapPayloadDto(
            analysisDisplayMode = graphViewsState.analysisDisplayMode.name,
            currentSceneId = graphViewsState.currentSceneId.name,
            workspaceGraph = renderer.documentToDto(workspaceState.workspaceGraph, includeFullContent = true),
            workspaceBaseGraph = renderer.documentToDto(workspaceState.workspaceBaseGraph, includeFullContent = true),
            semanticFactGraph = renderer.documentToDto(workspaceState.semanticFactGraph, includeFullContent = false),
            designBaselineGraph = workspaceState.designBaselineGraph?.let { renderer.documentToDto(it, includeFullContent = false) },
            sceneStates = sceneStatesToDto(graphViewsState.sceneStates),
            factGraphView = renderer.factGraphViewToDto(graphViewsState.factGraphView, factSceneState.layoutState),
            flowchartView = renderer.flowchartViewToDto(graphViewsState.flowchartView, flowchartSceneState.layoutState),
            resourceRelationView = renderer.resourceRelationViewToDto(graphViewsState.resourceRelationView, resourceSceneState.layoutState),
            architectureGraphView = renderer.architectureGraphViewToDto(graphViewsState.architectureGraphView, architectureSceneState.layoutState),
            classDiagramView = renderer.classDiagramViewToDto(graphViewsState.classDiagramView, classDiagramSceneState.layoutState),
            reviewGraphView = renderer.reviewGraphViewToDto(graphViewsState.reviewGraphView, reviewGraphSceneState.layoutState),
            indexedGraphRequestStates = graphViewsState.indexedGraphRequestStates.entries.associate { (view, requestState) -> view.name to renderer.requestStateToDto(requestState) },
            draftPatchPreview = generationState.draftPatchPreview?.let { renderer.patchToDto(it) },
            draftWorkbenchState = renderer.draftWorkbenchStateToDto(generationState.draftWorkbenchState),
            canUndoDraftPatchApply = generationState.draftPatchUndoState != null,
            lastAppliedDraftPatchSummary = generationState.draftPatchUndoState?.patchPreview?.summary,
            lastDraftPatchApplyResult = generationState.lastDraftPatchApplyResult?.let { renderer.draftPatchApplyResultToDto(it) },
            qaResult = reviewState.qaResult?.let { renderer.patchResultToDto(it, artifactRefs.qaPromptPreviewArtifactId) },
            qaRequestState = renderer.requestStateToDto(reviewState.qaRequestState, renderer.hasPromptPreview(reviewState.qaResult?.promptPreview, artifactRefs.qaPromptPreviewArtifactId)),
            qaRequestRecoveryState = renderer.qaRequestRecoveryStateToDto(reviewState.qaRequestRecoveryState),
            runtimeArtifactSummaries = assistantState.runtimeArtifactSummaries.mapValues { (_, summaries) ->
                summaries.map { s -> RuntimeArtifactSummaryDto(s.artifactId, s.artifactType, s.title, s.description) }
            },
            diffReviewResult = reviewState.diffReviewResult?.let { renderer.patchResultToDto(it, artifactRefs.diffReviewPromptPreviewArtifactId) },
            diffReviewRequestState = renderer.requestStateToDto(reviewState.diffReviewRequestState, renderer.hasPromptPreview(reviewState.diffReviewResult?.promptPreview, artifactRefs.diffReviewPromptPreviewArtifactId)),
            graphBeautificationResult = reviewState.graphBeautificationResult?.let { renderer.beautificationResultToDto(it, artifactRefs.beautificationPromptPreviewArtifactId) },
            graphBeautificationRequestState = renderer.requestStateToDto(reviewState.graphBeautificationRequestState, renderer.hasPromptPreview(reviewState.graphBeautificationResult?.promptPreview, artifactRefs.beautificationPromptPreviewArtifactId)),
            mermaidIssues = workspaceState.mermaidIssues.map { i -> MermaidIssueDto(i.category.name, i.code, i.message, i.line, i.nodeId, i.edgeId) },
            diffItems = workspaceState.diff?.entries.orEmpty().map { e -> DiffItemDto(e.elementId, renderer.resolveDiffTitle(e, workspaceState.workspaceGraph), e.status.name, e.message ?: e.fields.joinToString()) },
            syncPreviewItems = workspaceState.syncPreviewItems.map { i -> SyncPreviewItemDto(i.id, i.title, i.description, i.risk.name) },
            draftVersion = generationState.draftVersion,
            generationPlan = generationState.generationPlan?.let { p -> generationPlanToDto(p, artifactRefs.generationPlanPromptPreviewArtifactId) },
            generationPlanDraftVersion = generationState.generationPlanDraftVersion,
            generationPlanRequestState = renderer.requestStateToDto(generationState.generationPlanRequestState, renderer.hasPromptPreview(generationState.generationPlan?.promptPreview, artifactRefs.generationPlanPromptPreviewArtifactId)),
            draftValidationState = generationState.draftValidationState?.let { renderer.draftValidationStateToDto(it) },
            generationPlanDiscussionSession = generationState.generationPlanDiscussionSession?.let { s -> renderer.generationPlanDiscussionSessionToDto(s, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) },
            generationPlanDiscussionRequestState = renderer.requestStateToDto(generationState.generationPlanDiscussionRequestState, renderer.hasPromptPreview(generationState.generationPlanDiscussionSession?.promptPreview, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId)),
            generatedCodeDrafts = generationState.generatedCodeDrafts.map { d -> renderer.generatedCodeDraftToDto(d, artifactRefs.generatedCodeDraftContentArtifactIds[d.id]) },
            generatedCodeDraftVersion = generationState.generatedCodeDraftVersion,
            generatedCodeDraftWarnings = generationState.generatedCodeDraftWarnings,
            generatedCodeDraftSource = generationState.generatedCodeDraftSource?.name,
            generatedCodeDraftPromptPreviewArtifactId = artifactRefs.generatedCodeDraftPromptPreviewArtifactId,
            codeDraftRequestState = renderer.requestStateToDto(generationState.codeDraftRequestState, renderer.hasPromptPreview(generationState.generatedCodeDraftPromptPreview, artifactRefs.generatedCodeDraftPromptPreviewArtifactId)),
            codeEligibilityDecision = generationState.codeEligibilityDecision?.let { renderer.stageEligibilityDecisionToDto(it) },
            generatedCodeDraftWriteReport = generationState.generatedCodeDraftWriteReport?.let { r -> GeneratedCodeDraftWriteReportDto(r.writtenFiles, r.skippedFiles, r.warnings) },
            semanticRevision = workspaceState.semanticRevision,
            workspaceRevision = workspaceState.workspaceRevision,
            snapshotRevision = transportState.snapshotRevision,
            sourceNavigationState = renderer.sourceNavigationStateToDto(navigationState.sourceNavigationState),
            assistantSessionState = GraphEditorAssistantSessionRenderer.assistantSessionStateToDto(assistantState.sessionState),
            assistantResultStore = renderer.assistantResultStoreToDto(assistantState.resultStore, artifactRefs.assistantResultArtifacts),
            lastMessageType = transportState.lastMessageType,
            lastGraphSource = transportState.lastGraphSource,
            operationFeedback = transportState.operationFeedback?.let { f -> OperationFeedbackDto(f.level.name, f.message) },
        )
    }
}
