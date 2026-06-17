package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.projection.business.FactGraphProjector
import com.charmnight.linkgraph.projection.business.FlowchartProjector
import com.charmnight.linkgraph.projection.business.ResourceRelationProjector
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticDiagnosticSeverity
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import java.util.Locale

class AnalysisOutcomeFactory(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val factGraphProjector: FactGraphProjector = FactGraphProjector(graphAssembler),
    private val flowchartProjector: FlowchartProjector = FlowchartProjector(graphAssembler),
    private val resourceRelationProjector: ResourceRelationProjector = ResourceRelationProjector(graphAssembler),
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    fun create(
        analysisResult: SemanticAnalysisResult,
        displayMode: AnalysisDisplayMode,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): AnalysisOutcome {
        val totalStartedAt = System.nanoTime()
        val factStartedAt = System.nanoTime()
        val factGraphView = factGraphProjector.project(analysisResult, projectionPolicy)
        traceStage(
            stage = "analysis.outcome.factProjector",
            startedAtNanos = factStartedAt,
        ) {
            listOf(
                "visible=${graphSummary(factGraphView.visibleGraph)}",
                "full=${graphSummary(factGraphView.fullGraph)}",
            )
        }
        val flowStartedAt = System.nanoTime()
        val flowchartView = flowchartProjector.project(analysisResult, projectionPolicy)
        traceStage(
            stage = "analysis.outcome.flowchartProjector",
            startedAtNanos = flowStartedAt,
        ) {
            listOf(
                "visible=${graphSummary(flowchartView.visibleGraph)}",
                "full=${graphSummary(flowchartView.fullGraph)}",
                "truncated=${flowchartView.summary.truncated}",
            )
        }
        val resourceStartedAt = System.nanoTime()
        val resourceRelationView = resourceRelationProjector.project(analysisResult, projectionPolicy)
        traceStage(
            stage = "analysis.outcome.resourceRelationProjector",
            startedAtNanos = resourceStartedAt,
        ) {
            listOf(
                "visible=${graphSummary(resourceRelationView.visibleGraph)}",
                "full=${graphSummary(resourceRelationView.fullGraph)}",
            )
        }
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
            AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            AnalysisDisplayMode.CLASS_DIAGRAM,
            AnalysisDisplayMode.REVIEW_GRAPH,
            -> Triple(
                factGraphView.fullGraph,
                factGraphView.visibleGraph,
                factGraphView.anchorNodeId,
            )
        }
        val (hiddenNodeCount, hiddenEdgeCount, truncated) = when (displayMode) {
            AnalysisDisplayMode.FLOWCHART -> Triple(
                flowchartView.summary.hiddenNodeCount,
                flowchartView.summary.hiddenEdgeCount,
                flowchartView.summary.truncated,
            )
            AnalysisDisplayMode.FACT_GRAPH -> Triple(
                factGraphView.summary.hiddenNodeCount,
                factGraphView.summary.hiddenEdgeCount,
                factGraphView.summary.truncated,
            )
            else -> {
                val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
                val hiddenNodes = hiddenCounts.hiddenNodeCount
                val hiddenEdges = hiddenCounts.hiddenEdgeCount
                Triple(hiddenNodes, hiddenEdges, hiddenCounts.truncated)
            }
        }
        val primaryDiagnostic = selectPrimaryFeedbackDiagnostic(analysisResult.diagnostics)
        val outcome = AnalysisOutcome(
            displayMode = displayMode,
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            selectedMethodSignature = (analysisResult.subject as? CodeSubjectHandle)?.methodSignature
                ?: resolveAnchoredMethodSignature(analysisResult),
            displayName = analysisResult.subject.displayName,
            feedbackLevel = resolveFeedbackLevel(primaryDiagnostic, truncated),
            statusMessage = primaryDiagnostic?.message
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
        traceStage(
            stage = "analysis.outcome.total",
            startedAtNanos = totalStartedAt,
        ) {
            listOf(
                "mode=${outcome.displayMode}",
                "visible=${graphSummary(outcome.visibleGraph)}",
                "full=${graphSummary(outcome.fullGraph)}",
                "semanticUnits=${analysisResult.semanticUnits.size}",
                "relations=${analysisResult.relations.size}",
            )
        }
        return outcome
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        val durationMs = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000.0
        trace {
            buildString {
                append("渲染链路 trace: stage=")
                append(stage)
                append(", durationMs=")
                append(String.format(Locale.ROOT, "%.2f", durationMs))
                details().filter { it.isNotBlank() }.forEach { detail ->
                    append(", ")
                    append(detail)
                }
            }
        }
    }

    private fun graphSummary(graph: GraphDocument?): String {
        if (graph == null) {
            return "nodes=0, edges=0"
        }
        return "nodes=${graph.nodes.size}, edges=${graph.edges.size}"
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
    ): ApplicationFeedbackLevel {
        return when (diagnostic?.severity) {
            SemanticDiagnosticSeverity.ERROR -> ApplicationFeedbackLevel.ERROR
            SemanticDiagnosticSeverity.WARNING -> ApplicationFeedbackLevel.WARNING
            else -> if (truncated) {
                ApplicationFeedbackLevel.WARNING
            } else {
                ApplicationFeedbackLevel.SUCCESS
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
            AnalysisDisplayMode.ARCHITECTURE_GRAPH -> "已加载项目结构"
            AnalysisDisplayMode.CLASS_DIAGRAM -> "已加载类图"
            AnalysisDisplayMode.REVIEW_GRAPH -> "已加载 Review Graph"
        }
        return if (truncated) {
            "$prefix：$displayName（画布展示 ${visibleGraph.nodes.size} 个节点，完整结果 ${fullGraph.nodes.size} 个节点）"
        } else {
            "$prefix：$displayName（${fullGraph.nodes.size} 个节点）"
        }
    }
}
