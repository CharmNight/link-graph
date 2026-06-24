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

/**
 * 分析结果输出工厂：负责协调多个投影器，按展示模式组装最终的可视化输出。
 *
 * 会同时计算事实图、流程图、资源关系图三套投影，再根据当前展示模式挑选主图，
 * 并统一处理隐藏节点统计、反馈等级、状态文案等通用字段。
 */
class AnalysisOutcomeFactory(
    /** 负责把语义单元装配成图结构的底层装配器。 */
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    /** 事实图投影器，输出方法/资源/流程单元的静态关系。 */
    private val factGraphProjector: FactGraphProjector = FactGraphProjector(graphAssembler),
    /** 流程图投影器，输出从锚点出发的控制流裁剪结果。 */
    private val flowchartProjector: FlowchartProjector = FlowchartProjector(graphAssembler),
    /** 资源关系图投影器，输出方法与资源的归一化关系。 */
    private val resourceRelationProjector: ResourceRelationProjector = ResourceRelationProjector(graphAssembler),
    /** 可选的运行时追踪回调，用于把各阶段耗时记录到日志或诊断面板。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    /**
     * 根据语义分析结果与展示模式生成最终的可视化输出。
     *
     * 内部按顺序执行三种投影，统计隐藏节点/边并生成对应反馈文案，最终汇总为 [AnalysisOutcome]。
     */
    fun create(
        analysisResult: SemanticAnalysisResult,
        displayMode: AnalysisDisplayMode,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): AnalysisOutcome {
        /** 整体输出流程开始时间，用于统计总耗时。 */
        val totalStartedAt = System.nanoTime()
        /** 事实图投影阶段开始时间。 */
        val factStartedAt = System.nanoTime()
        /** 事实图投影结果，包含可见图、完整图与锚点。 */
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
        /** 流程图投影阶段开始时间。 */
        val flowStartedAt = System.nanoTime()
        /** 流程图投影结果，包含可见图、完整图、锚点与隐藏统计。 */
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
        /** 资源关系图投影阶段开始时间。 */
        val resourceStartedAt = System.nanoTime()
        /** 资源关系图投影结果。 */
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
        /** 根据展示模式挑选当前主视图的完整图、可见图与锚点。 */
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
        /** 根据展示模式计算当前主视图的隐藏节点数、隐藏边数与是否截断。 */
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
                /** 其它视图模式下通过比较可见图与完整图实时算出隐藏数。 */
                val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
                val hiddenNodes = hiddenCounts.hiddenNodeCount
                val hiddenEdges = hiddenCounts.hiddenEdgeCount
                Triple(hiddenNodes, hiddenEdges, hiddenCounts.truncated)
            }
        }
        /** 从诊断列表中选出用于驱动反馈等级的主诊断信息。 */
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

    /** 把指定阶段的耗时与详情通过运行时追踪回调输出。 */
    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        /** 当前阶段消耗的毫秒数。 */
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

    /** 把图的节点/边数量汇总成便于追踪输出的紧凑字符串。 */
    private fun graphSummary(graph: GraphDocument?): String {
        if (graph == null) {
            return "nodes=0, edges=0"
        }
        return "nodes=${graph.nodes.size}, edges=${graph.edges.size}"
    }

    /** 当主题对象未携带方法签名时，从锚点或第一个方法类单元推导出一个签名。 */
    private fun resolveAnchoredMethodSignature(analysisResult: SemanticAnalysisResult): String? {
        /** 当前语义单元集合中所有方法类单元的索引。 */
        val methodsById = analysisResult.semanticUnits
            .filterIsInstance<MethodLikeUnit>()
            .associateBy { unit -> unit.id }
        return analysisResult.anchors
            .asSequence()
            .mapNotNull { anchor -> anchor.targetUnitId?.let(methodsById::get)?.signature }
            .firstOrNull()
            ?: analysisResult.semanticUnits.filterIsInstance<MethodLikeUnit>().firstOrNull()?.signature
    }

    /** 从诊断列表中选出最高严重等级的诊断，用于驱动反馈等级与默认状态文案。 */
    private fun selectPrimaryFeedbackDiagnostic(
        diagnostics: List<SemanticDiagnostic>,
    ): SemanticDiagnostic? {
        return diagnostics.firstOrNull { diagnostic -> diagnostic.severity == SemanticDiagnosticSeverity.ERROR }
            ?: diagnostics.firstOrNull { diagnostic -> diagnostic.severity == SemanticDiagnosticSeverity.WARNING }
    }

    /** 根据主诊断与是否被截断决定反馈等级。 */
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

    /** 没有诊断错误时，根据展示模式与图规模生成默认的状态文案。 */
    private fun buildFeedbackMessage(
        displayName: String,
        displayMode: AnalysisDisplayMode,
        fullGraph: GraphDocument,
        visibleGraph: GraphDocument,
        truncated: Boolean,
    ): String {
        /** 根据展示模式选择的前缀文案。 */
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
