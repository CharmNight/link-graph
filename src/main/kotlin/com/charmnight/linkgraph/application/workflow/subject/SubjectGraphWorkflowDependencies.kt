package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal data class SubjectGraphWorkflowDependencies(
    val project: Project,
    val snapshotProvider: EditorSnapshotProvider,
    val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    val subjectLocatorProvider: () -> SubjectLocator,
    val semanticAnalyzerProvider: () -> SemanticAnalyzer,
    val analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    val codeSubjectHandleFactory: CodeSubjectHandleFactory,
    val workspaceGraphCommitter: WorkspaceGraphCommitter,
    val eventSink: GraphEditorApplicationEventSink,
    val invalidateQaRequests: () -> Unit,
    val logGraphDiagnostics: (String, GraphDocument?) -> Unit,
    val runtimeTrace: ((() -> String) -> Unit)?,
    val logger: Logger,
) {
    fun emit(event: GraphEditorApplicationEvent) {
        eventSink.emit(event)
    }

    fun emitFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preserveLastMessageType: Boolean = false,
    ) {
        emit(GraphEditorApplicationEvent.Feedback(level, message, preserveLastMessageType))
    }
}
