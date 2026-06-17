package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.application.model.GraphEditResult
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCase
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCaseResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner

internal class GraphWorkspaceWorkflow(
    private val snapshotProvider: EditorSnapshotProvider,
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    private val eventSink: GraphEditorApplicationEventSink,
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val copyToClipboard: (String) -> Boolean,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    private val useCase = WorkspaceGraphUseCase(
        mermaidImporter = mermaidImporter,
        mermaidValidator = mermaidValidator,
        mermaidExporter = mermaidExporter,
        graphDiffer = graphDiffer,
        syncPreviewPlanner = syncPreviewPlanner,
        frontendGraphMutationSanitizer = frontendGraphMutationSanitizer,
    )

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        val result = useCase.loadGraph(graph, source)
        eventSink.emit(GraphEditorApplicationEvent.WorkspaceGraphLoaded(result.graph, result.source))
    }

    fun handleGraphEditRequest(request: GraphEditRequest): GraphEditResult {
        return when (val result = useCase.applyGraphEditRequest(snapshotProvider.snapshot(), request)) {
            is WorkspaceGraphUseCaseResult.EditRejected -> {
                eventSink.emit(GraphEditorApplicationEvent.GraphEditRejected(result.rejection))
                GraphEditResult.Rejected(result.rejection)
            }
            is WorkspaceGraphUseCaseResult.EditApplied -> {
                workspaceGraphCommitter.commitWorkspaceGraph(
                expectedSnapshotRevision = result.expectedSnapshotRevision,
                graph = result.graph,
                selectedMethodSignature = result.selectedMethodSignature,
                syncBrowser = false,
                    graphEditTransaction = result.transaction,
            )
                GraphEditResult.Applied(result.graph, result.transaction)
            }
            else -> error("unsupported graph edit use case result: ${result::class.simpleName}")
        }
    }

    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        val result = useCase.changeLayout(snapshotProvider.snapshot(), positions)
        if (result.positions.isEmpty()) {
            return
        }
        eventSink.emit(GraphEditorApplicationEvent.WorkspaceLayoutChanged(result.positions))
    }

    fun importMermaid(mermaid: String): GraphDocument {
        val result = useCase.importMermaid(mermaid)
        eventSink.emit(GraphEditorApplicationEvent.MermaidImported(result.mermaid, result.graph, result.issues))
        return result.graph
    }

    fun exportMermaid(): String {
        val result = useCase.exportMermaid(snapshotProvider.snapshot())
        return result.exported.also { exported ->
            eventSink.emit(GraphEditorApplicationEvent.MermaidExported(exported, copyToClipboard(exported)))
        }
    }

    fun showDiffMode(): GraphDifferResult? {
        return when (val result = useCase.showDiffMode(snapshotProvider.snapshot())) {
            WorkspaceGraphUseCaseResult.MissingDiffInputs -> {
                eventSink.emit(
                    GraphEditorApplicationEvent.Feedback(
                        level = ApplicationFeedbackLevel.WARNING,
                        message = "缺少代码图或设计基线，无法打开代码对比。",
                    ),
                )
                null
            }
            is WorkspaceGraphUseCaseResult.DiffShown -> result.result.also { diffResult ->
                eventSink.emit(GraphEditorApplicationEvent.DiffModeShown(diffResult.graph, diffResult.diff))
                eventSink.emit(
                    GraphEditorApplicationEvent.Feedback(
                        level = ApplicationFeedbackLevel.INFO,
                        message = "已打开代码对比。",
                    ),
                )
            }
            else -> null
        }
    }

    fun requestSyncPreview(): List<SyncPreviewItem> {
        val result = useCase.requestSyncPreview(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.SyncPreviewReady(result.items))
        eventSink.emit(
            GraphEditorApplicationEvent.Feedback(
                level = ApplicationFeedbackLevel.INFO,
                message = if (result.items.isEmpty()) {
                    "没有可同步的变更。"
                } else {
                    "已打开同步预览。"
                },
            ),
        )
        return result.items
    }
}
