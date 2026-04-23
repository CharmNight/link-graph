package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticDiagnosticSeverity
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.view.FactGraphProjector
import com.charmnight.linkgraph.ui.view.FlowchartProjector
import com.charmnight.linkgraph.ui.view.ResourceRelationProjector

class AnalysisOutcomeFactory(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val factGraphProjector: FactGraphProjector = FactGraphProjector(graphAssembler),
    private val flowchartProjector: FlowchartProjector = FlowchartProjector(graphAssembler),
    private val resourceRelationProjector: ResourceRelationProjector = ResourceRelationProjector(graphAssembler),
) {
    fun create(
        analysisResult: SemanticAnalysisResult,
        displayMode: AnalysisDisplayMode,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): AnalysisOutcome {
        val factGraphView = factGraphProjector.project(analysisResult, projectionPolicy)
        val flowchartView = flowchartProjector.project(analysisResult, projectionPolicy)
        val resourceRelationView = resourceRelationProjector.project(analysisResult, projectionPolicy)
        val (fullGraph, visibleGraph, anchorNodeId) = when (displayMode) {
            AnalysisDisplayMode.FACT_GRAPH -> Triple(
                factGraphView.fullGraph,
                factGraphView.visibleGraph,
                factGraphView.anchorNodeId,
            )
            AnalysisDisplayMode.FLOWCHART -> Triple(
                flowchartView.fullGraph,
                flowchartView.visibleGraph,
                flowchartView.anchorNodeId,
            )
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> Triple(
                resourceRelationView.fullGraph,
                resourceRelationView.visibleGraph,
                resourceRelationView.anchorNodeId,
            )
        }
        val (hiddenNodeCount, hiddenEdgeCount, truncated) = when (displayMode) {
            AnalysisDisplayMode.FLOWCHART -> Triple(
                flowchartView.summary.hiddenNodeCount,
                flowchartView.summary.hiddenEdgeCount,
                flowchartView.summary.truncated,
            )
            else -> {
                val hiddenNodes = (fullGraph.nodes.map { it.id }.toSet() - visibleGraph.nodes.map { it.id }.toSet()).size
                val hiddenEdges = (fullGraph.edges.map { it.id }.toSet() - visibleGraph.edges.map { it.id }.toSet()).size
                Triple(hiddenNodes, hiddenEdges, hiddenNodes > 0 || hiddenEdges > 0)
            }
        }
        val primaryDiagnostic = selectPrimaryFeedbackDiagnostic(analysisResult.diagnostics)
        return AnalysisOutcome(
            displayMode = displayMode,
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            selectedMethodSignature = (analysisResult.subject as? CodeSubjectHandle)?.methodSignature
                ?: resolveAnchoredMethodSignature(analysisResult),
            displayName = analysisResult.subject.displayName,
            feedbackLevel = resolveFeedbackLevel(primaryDiagnostic, truncated),
            feedbackMessage = primaryDiagnostic?.message
                ?: buildFeedbackMessage(analysisResult.subject.displayName, displayMode, fullGraph, visibleGraph, truncated),
            projectionStats = AnalysisProjectionStats(
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                truncated = truncated,
            ),
            factGraphView = factGraphView,
            flowchartView = flowchartView,
            resourceRelationView = resourceRelationView,
        )
    }

    private fun resolveAnchoredMethodSignature(analysisResult: SemanticAnalysisResult): String? {
        val methodsById = analysisResult.semanticUnits
            .filterIsInstance<MethodLikeUnit>()
            .associateBy { unit -> unit.id }
        return analysisResult.anchors
            .asSequence()
            .mapNotNull { anchor -> anchor.targetUnitId?.let(methodsById::get)?.signature }
            .firstOrNull()
            ?: analysisResult.semanticUnits.filterIsInstance<MethodLikeUnit>().firstOrNull()?.signature
    }

    private fun selectPrimaryFeedbackDiagnostic(
        diagnostics: List<SemanticDiagnostic>,
    ): SemanticDiagnostic? {
        return diagnostics.firstOrNull { diagnostic -> diagnostic.severity == SemanticDiagnosticSeverity.ERROR }
            ?: diagnostics.firstOrNull { diagnostic -> diagnostic.severity == SemanticDiagnosticSeverity.WARNING }
    }

    private fun resolveFeedbackLevel(
        diagnostic: SemanticDiagnostic?,
        truncated: Boolean,
    ): com.charmnight.linkgraph.ui.OperationFeedbackLevel {
        return when (diagnostic?.severity) {
            SemanticDiagnosticSeverity.ERROR -> com.charmnight.linkgraph.ui.OperationFeedbackLevel.ERROR
            SemanticDiagnosticSeverity.WARNING -> com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING
            else -> if (truncated) {
                com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING
            } else {
                com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS
            }
        }
    }

    private fun buildFeedbackMessage(
        displayName: String,
        displayMode: AnalysisDisplayMode,
        fullGraph: GraphDocument,
        visibleGraph: GraphDocument,
        truncated: Boolean,
    ): String {
        val prefix = when (displayMode) {
            AnalysisDisplayMode.FACT_GRAPH -> "已加载事实链路"
            AnalysisDisplayMode.FLOWCHART -> "已加载流程图"
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> "已加载资源关系图"
        }
        return if (truncated) {
            "$prefix：$displayName（画布展示 ${visibleGraph.nodes.size} 个节点，完整结果 ${fullGraph.nodes.size} 个节点）"
        } else {
            "$prefix：$displayName（${fullGraph.nodes.size} 个节点）"
        }
    }
}
