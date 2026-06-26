package com.charmnight.linkgraph.ui

internal class GraphEditorArtifactSlicePayloadBuilder(
    private val pageRenderer: GraphEditorPageRenderer,
) {
    fun build(
        snapshot: GraphEditorStateSnapshot,
        previousArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        artifactContents: Map<String, String>,
    ): ArtifactSlicePayloadDto {
        val domainStates = snapshot.domainStates()
        val reviewState = domainStates.review
        val generationState = domainStates.generation
        val assistantState = domainStates.assistant

        var qaResult: PatchResultDto? = null
        var qaRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.qaPromptPreviewArtifactId != artifactRefs.qaPromptPreviewArtifactId) {
            qaResult = reviewState.qaResult?.let { pageRenderer.patchResultToDto(it, artifactRefs.qaPromptPreviewArtifactId) }
            qaRequestState = pageRenderer.requestStateToDto(reviewState.qaRequestState, pageRenderer.hasPromptPreview(reviewState.qaResult?.promptPreview, artifactRefs.qaPromptPreviewArtifactId))
        }

        var diffReviewResult: PatchResultDto? = null
        var diffReviewRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.diffReviewPromptPreviewArtifactId != artifactRefs.diffReviewPromptPreviewArtifactId) {
            diffReviewResult = reviewState.diffReviewResult?.let { pageRenderer.patchResultToDto(it, artifactRefs.diffReviewPromptPreviewArtifactId) }
            diffReviewRequestState = pageRenderer.requestStateToDto(reviewState.diffReviewRequestState, pageRenderer.hasPromptPreview(reviewState.diffReviewResult?.promptPreview, artifactRefs.diffReviewPromptPreviewArtifactId))
        }

        var graphBeautificationResult: BeautificationResultDto? = null
        var graphBeautificationRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.beautificationPromptPreviewArtifactId != artifactRefs.beautificationPromptPreviewArtifactId) {
            graphBeautificationResult = reviewState.graphBeautificationResult?.let { pageRenderer.beautificationResultToDto(it, artifactRefs.beautificationPromptPreviewArtifactId) }
            graphBeautificationRequestState = pageRenderer.requestStateToDto(reviewState.graphBeautificationRequestState, pageRenderer.hasPromptPreview(reviewState.graphBeautificationResult?.promptPreview, artifactRefs.beautificationPromptPreviewArtifactId))
        }

        var generationPlan: GenerationPlanDto? = null
        var generationPlanRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.generationPlanPromptPreviewArtifactId != artifactRefs.generationPlanPromptPreviewArtifactId) {
            generationPlan = generationState.generationPlan?.let { generationPlanToDto(it, artifactRefs.generationPlanPromptPreviewArtifactId) }
            generationPlanRequestState = pageRenderer.requestStateToDto(generationState.generationPlanRequestState, pageRenderer.hasPromptPreview(generationState.generationPlan?.promptPreview, artifactRefs.generationPlanPromptPreviewArtifactId))
        }

        var generationPlanDiscussionSession: GenerationPlanDiscussionSessionDto? = null
        var generationPlanDiscussionRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.generationPlanDiscussionPromptPreviewArtifactId != artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) {
            generationPlanDiscussionSession = generationState.generationPlanDiscussionSession?.let { pageRenderer.generationPlanDiscussionSessionToDto(it, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) }
            generationPlanDiscussionRequestState = pageRenderer.requestStateToDto(generationState.generationPlanDiscussionRequestState, pageRenderer.hasPromptPreview(generationState.generationPlanDiscussionSession?.promptPreview, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId))
        }

        var generatedCodeDrafts: List<GeneratedCodeDraftDto>? = null
        if (previousArtifactRefs.generatedCodeDraftContentArtifactIds != artifactRefs.generatedCodeDraftContentArtifactIds) {
            generatedCodeDrafts = generationState.generatedCodeDrafts.map { draft ->
                pageRenderer.generatedCodeDraftToDto(draft, artifactRefs.generatedCodeDraftContentArtifactIds[draft.id])
            }
        }

        var generatedCodeDraftPromptPreviewArtifactId: String? = null
        var codeDraftRequestState: AsyncRequestStateDto? = null
        if (previousArtifactRefs.generatedCodeDraftPromptPreviewArtifactId != artifactRefs.generatedCodeDraftPromptPreviewArtifactId) {
            generatedCodeDraftPromptPreviewArtifactId = artifactRefs.generatedCodeDraftPromptPreviewArtifactId
            codeDraftRequestState = pageRenderer.requestStateToDto(generationState.codeDraftRequestState, pageRenderer.hasPromptPreview(generationState.generatedCodeDraftPromptPreview, artifactRefs.generatedCodeDraftPromptPreviewArtifactId))
        }

        var assistantResultStore: Map<String, AssistantResultEntryDto>? = null
        if (previousArtifactRefs.assistantResultArtifacts != artifactRefs.assistantResultArtifacts) {
            assistantResultStore = pageRenderer.assistantResultStoreToDto(assistantState.resultStore, artifactRefs.assistantResultArtifacts)
        }

        return ArtifactSlicePayloadDto(
            snapshotRevision = snapshot.snapshotRevision,
            qaResult = qaResult,
            qaRequestState = qaRequestState,
            diffReviewResult = diffReviewResult,
            diffReviewRequestState = diffReviewRequestState,
            graphBeautificationResult = graphBeautificationResult,
            graphBeautificationRequestState = graphBeautificationRequestState,
            generationPlan = generationPlan,
            generationPlanRequestState = generationPlanRequestState,
            generationPlanDiscussionSession = generationPlanDiscussionSession,
            generationPlanDiscussionRequestState = generationPlanDiscussionRequestState,
            generatedCodeDrafts = generatedCodeDrafts,
            generatedCodeDraftPromptPreviewArtifactId = generatedCodeDraftPromptPreviewArtifactId,
            codeDraftRequestState = codeDraftRequestState,
            assistantResultStore = assistantResultStore,
            artifactContents = artifactContents,
        )
    }
}
