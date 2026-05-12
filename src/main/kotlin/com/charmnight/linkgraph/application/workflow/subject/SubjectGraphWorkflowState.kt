package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

internal class SubjectGraphWorkflowState {
    @Volatile
    var projectionSettings: InteractiveProjectionSettings = InteractiveProjectionSettings()

    @Volatile
    var requestedDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FLOWCHART

    @Volatile
    var lastAnalyzedSubjectHandle: SubjectHandle? = null

    @Volatile
    var lastSemanticAnalysisResult: SemanticAnalysisResult? = null

    fun clearLastAnalysisCache() {
        lastAnalyzedSubjectHandle = null
        lastSemanticAnalysisResult = null
    }

    fun resetProjectionSettings() {
        projectionSettings = InteractiveProjectionSettings()
    }

    fun prepareRequestedDisplayMode(displayMode: AnalysisDisplayMode): Boolean {
        if (requestedDisplayMode == displayMode) {
            return false
        }
        requestedDisplayMode = displayMode
        return true
    }
}
