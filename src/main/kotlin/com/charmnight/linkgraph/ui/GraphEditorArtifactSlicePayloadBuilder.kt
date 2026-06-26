package com.charmnight.linkgraph.ui

internal class GraphEditorArtifactSlicePayloadBuilder(
    private val pageRenderer: GraphEditorPageRenderer,
) {
    fun build(
        snapshot: GraphEditorStateSnapshot,
        previousArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        artifactContents: Map<String, String>,
    ): LinkedHashMap<String, Any?> {
        val domainStates = snapshot.domainStates()
        val reviewState = domainStates.review
        val generationState = domainStates.generation
        val assistantState = domainStates.assistant
        return linkedMapOf<String, Any?>("snapshotRevision" to snapshot.snapshotRevision).apply {
            if (previousArtifactRefs.qaPromptPreviewArtifactId != artifactRefs.qaPromptPreviewArtifactId) {
                put("qaResult", reviewState.qaResult?.let { pageRenderer.patchResultToMap(it, artifactRefs.qaPromptPreviewArtifactId) })
                put("qaRequestState", pageRenderer.requestStateToMap(reviewState.qaRequestState, pageRenderer.hasPromptPreview(reviewState.qaResult?.promptPreview, artifactRefs.qaPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.diffReviewPromptPreviewArtifactId != artifactRefs.diffReviewPromptPreviewArtifactId) {
                put("diffReviewResult", reviewState.diffReviewResult?.let { pageRenderer.patchResultToMap(it, artifactRefs.diffReviewPromptPreviewArtifactId) })
                put("diffReviewRequestState", pageRenderer.requestStateToMap(reviewState.diffReviewRequestState, pageRenderer.hasPromptPreview(reviewState.diffReviewResult?.promptPreview, artifactRefs.diffReviewPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.beautificationPromptPreviewArtifactId != artifactRefs.beautificationPromptPreviewArtifactId) {
                put("graphBeautificationResult", reviewState.graphBeautificationResult?.let { pageRenderer.beautificationResultToMap(it, artifactRefs.beautificationPromptPreviewArtifactId) })
                put("graphBeautificationRequestState", pageRenderer.requestStateToMap(reviewState.graphBeautificationRequestState, pageRenderer.hasPromptPreview(reviewState.graphBeautificationResult?.promptPreview, artifactRefs.beautificationPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.generationPlanPromptPreviewArtifactId != artifactRefs.generationPlanPromptPreviewArtifactId) {
                put("generationPlan", generationState.generationPlan?.let { generationPlanToDto(it, artifactRefs.generationPlanPromptPreviewArtifactId) })
                put("generationPlanRequestState", pageRenderer.requestStateToMap(generationState.generationPlanRequestState, pageRenderer.hasPromptPreview(generationState.generationPlan?.promptPreview, artifactRefs.generationPlanPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.generationPlanDiscussionPromptPreviewArtifactId != artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) {
                put("generationPlanDiscussionSession", generationState.generationPlanDiscussionSession?.let { pageRenderer.generationPlanDiscussionSessionToMap(it, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId) })
                put("generationPlanDiscussionRequestState", pageRenderer.requestStateToMap(generationState.generationPlanDiscussionRequestState, pageRenderer.hasPromptPreview(generationState.generationPlanDiscussionSession?.promptPreview, artifactRefs.generationPlanDiscussionPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.generatedCodeDraftContentArtifactIds != artifactRefs.generatedCodeDraftContentArtifactIds) {
                put("generatedCodeDrafts", generationState.generatedCodeDrafts.map { draft ->
                    pageRenderer.generatedCodeDraftToMap(draft, artifactRefs.generatedCodeDraftContentArtifactIds[draft.id])
                })
            }
            if (previousArtifactRefs.generatedCodeDraftPromptPreviewArtifactId != artifactRefs.generatedCodeDraftPromptPreviewArtifactId) {
                put("generatedCodeDraftPromptPreviewArtifactId", artifactRefs.generatedCodeDraftPromptPreviewArtifactId)
                put("codeDraftRequestState", pageRenderer.requestStateToMap(generationState.codeDraftRequestState, pageRenderer.hasPromptPreview(generationState.generatedCodeDraftPromptPreview, artifactRefs.generatedCodeDraftPromptPreviewArtifactId)))
            }
            if (previousArtifactRefs.assistantResultArtifacts != artifactRefs.assistantResultArtifacts) {
                put("assistantResultStore", pageRenderer.assistantResultStoreToMap(assistantState.resultStore, artifactRefs.assistantResultArtifacts))
            }
            put("artifactContents", artifactContents)
        }
    }
}
