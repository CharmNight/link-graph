package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.edit.GraphEditApplier
import com.charmnight.linkgraph.application.edit.GraphEditPermissionPolicy
import com.charmnight.linkgraph.application.edit.GraphEditScriptValidator
import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner

sealed interface WorkspaceGraphUseCaseResult {
    data class Loaded(val graph: GraphDocument, val source: String) : WorkspaceGraphUseCaseResult
    data class EditRejected(val rejection: GraphEditRejected) : WorkspaceGraphUseCaseResult
    data class EditApplied(
        val expectedSnapshotRevision: Long,
        val graph: GraphDocument,
        val selectedMethodSignature: String?,
        val transaction: GraphEditTransaction,
    ) : WorkspaceGraphUseCaseResult
    data class LayoutChanged(
        val positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ) : WorkspaceGraphUseCaseResult
    data class MermaidImported(
        val mermaid: String,
        val graph: GraphDocument,
        val issues: List<MermaidIssue>,
    ) : WorkspaceGraphUseCaseResult
    data class MermaidExported(val exported: String) : WorkspaceGraphUseCaseResult
    data object MissingDiffInputs : WorkspaceGraphUseCaseResult
    data class DiffShown(val result: GraphDifferResult) : WorkspaceGraphUseCaseResult
    data class SyncPreviewReady(val items: List<SyncPreviewItem>) : WorkspaceGraphUseCaseResult
}

class WorkspaceGraphUseCase internal constructor(
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
    private val graphEditScriptValidator: GraphEditScriptValidator = GraphEditScriptValidator(),
    private val graphEditPermissionPolicy: GraphEditPermissionPolicy = GraphEditPermissionPolicy(),
) {
    private val graphEditApplier = GraphEditApplier(frontendGraphMutationSanitizer)

    fun loadGraph(graph: GraphDocument, source: String): WorkspaceGraphUseCaseResult.Loaded =
        WorkspaceGraphUseCaseResult.Loaded(graph, source)

    fun applyGraphEditRequest(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
    ): WorkspaceGraphUseCaseResult {
        if (snapshot.workspaceRevision != request.baseWorkspaceRevision) {
            return editRejected(
                snapshot,
                GraphEditIssue(
                    code = GraphEditIssueCode.STALE_BASE_REVISION,
                    message = "图编辑基准版本已过期，请重新读取当前图后重试。",
                    retryable = true,
                ),
            )
        }
        val permissionDecision = graphEditPermissionPolicy.evaluate(snapshot, request)
        if (permissionDecision.issues.isNotEmpty()) {
            return editRejected(snapshot, permissionDecision.issues)
        }
        val validationIssues = graphEditScriptValidator.validate(
            snapshot.workspaceGraph,
            request,
            permissionDecision.resolution,
        )
        if (validationIssues.isNotEmpty()) {
            return editRejected(snapshot, validationIssues)
        }
        val graph = graphEditApplier.apply(snapshot, request, permissionDecision.resolution)
        return WorkspaceGraphUseCaseResult.EditApplied(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = graph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            transaction = GraphEditTransaction(
                graphBeforeApply = snapshot.workspaceGraph,
                graphAfterApply = graph,
                request = request,
                appliedOperations = request.operations,
                source = request.source,
                workspaceRevisionBefore = snapshot.workspaceRevision,
                workspaceRevisionAfter = snapshot.workspaceRevision + 1,
            ),
        )
    }

    fun importMermaid(mermaid: String): WorkspaceGraphUseCaseResult.MermaidImported {
        val parseResult = mermaidImporter.import(mermaid)
        val issues = mermaidValidator.validate(parseResult.document, parseResult.issues)
        return WorkspaceGraphUseCaseResult.MermaidImported(mermaid, parseResult.document, issues)
    }

    fun changeLayout(
        snapshot: WorkflowEditorSnapshot,
        positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ): WorkspaceGraphUseCaseResult.LayoutChanged {
        if (snapshot.analysisDisplayMode == AnalysisDisplayMode.ARCHITECTURE_GRAPH ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.CLASS_DIAGRAM ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.REVIEW_GRAPH
        ) {
            return WorkspaceGraphUseCaseResult.LayoutChanged(emptyMap())
        }
        return WorkspaceGraphUseCaseResult.LayoutChanged(positions)
    }

    fun exportMermaid(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.MermaidExported {
        val document = snapshot.designBaselineGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
                snapshot.flowchartView.fullGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            } else {
                null
            }
            ?: currentVisibleGraph(snapshot)
        return WorkspaceGraphUseCaseResult.MermaidExported(mermaidExporter.export(document))
    }

    fun showDiffMode(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult {
        val codeGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() }
            ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        val designGraph = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        return WorkspaceGraphUseCaseResult.DiffShown(graphDiffer.diff(codeGraph, designGraph))
    }

    fun requestSyncPreview(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.SyncPreviewReady {
        val previewItems = when {
            snapshot.diffGraph != null && snapshot.diff != null -> syncPreviewPlanner.plan(snapshot.diffGraph, snapshot.diff)
            snapshot.semanticFactGraph.nodes.isNotEmpty() || snapshot.semanticFactGraph.edges.isNotEmpty() -> {
                val designBaseline = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.SyncPreviewReady(emptyList())
                val result = graphDiffer.diff(snapshot.semanticFactGraph, designBaseline)
                syncPreviewPlanner.plan(result.graph, result.diff)
            }
            else -> emptyList()
        }
        return WorkspaceGraphUseCaseResult.SyncPreviewReady(previewItems)
    }

    private fun editRejected(
        snapshot: WorkflowEditorSnapshot,
        issue: GraphEditIssue,
    ): WorkspaceGraphUseCaseResult.EditRejected = editRejected(snapshot, listOf(issue))

    private fun editRejected(
        snapshot: WorkflowEditorSnapshot,
        issues: List<GraphEditIssue>,
    ): WorkspaceGraphUseCaseResult.EditRejected =
        WorkspaceGraphUseCaseResult.EditRejected(
            GraphEditRejected(
                issues = issues,
                currentWorkspaceRevision = snapshot.workspaceRevision,
            ),
        )
}
