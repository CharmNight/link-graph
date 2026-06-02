package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisOutcomeFactoryTest {
    @Test
    fun shouldBuildOutcomeAndProjectionStatsFromSemanticResult() {
        val result = SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-doc:order-flow",
                sourcePath = "docs/order-flow.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
                displayName = "order-flow.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor-main", targetUnitId = "method:submit", label = "入口")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:submit",
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit(java.lang.String):void",
                ),
                MethodLikeUnit(
                    id = "method:save",
                    title = "OrderRepository.save",
                    signature = "com.example.OrderRepository.save(com.example.Order):void",
                ),
                MethodLikeUnit(
                    id = "method:publish",
                    title = "OrderPublisher.publish",
                    signature = "com.example.OrderPublisher.publish(com.example.Order):void",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.INVOKES, "method:submit", "method:save"),
                SemanticRelation(SemanticRelationKind.INVOKES, "method:submit", "method:publish"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val outcome = AnalysisOutcomeFactory().create(
            analysisResult = result,
            displayMode = AnalysisDisplayMode.FACT_GRAPH,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 2, maxVisibleEdges = 1),
        )

        assertEquals(AnalysisDisplayMode.FACT_GRAPH, outcome.displayMode)
        assertEquals("order-flow.md", outcome.displayName)
        assertEquals("method:submit", outcome.anchorNodeId)
        assertTrue(outcome.fullGraph.nodes.size > outcome.visibleGraph.nodes.size)
        assertTrue(outcome.projectionStats.truncated)
        assertEquals(2, outcome.projectionStats.hiddenNodeCount)
        assertEquals(2, outcome.projectionStats.hiddenEdgeCount)
        assertTrue(outcome.statusMessage.contains("order-flow.md"))
    }

    @Test
    fun shouldExposeReadableFlowchartProjectionStatsFromFlowchartView() {
        val result = SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-doc:demo-flow",
                sourcePath = "docs/demo-flow.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
                displayName = "demo-flow.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor-main", targetUnitId = "method:submit", label = "入口")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:submit",
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit():void",
                ),
                FlowActionUnit(
                    id = "action:guard-condition",
                    title = "!FileUtils.checkAllowDownload(fileName)",
                    actionKind = "CONDITION",
                ),
                InvocationUnit(
                    id = "invoke:checkAllowDownload",
                    title = "调用 FileUtils.checkAllowDownload",
                    targetSignature = "com.example.FileUtils.checkAllowDownload(java.lang.String):boolean",
                ),
                FlowScopeUnit(
                    id = "scope:guard",
                    title = "if (!FileUtils.checkAllowDownload(fileName))",
                    scopeKind = "IF",
                    scopeCategory = FlowScopeCategory.BRANCH,
                ),
                FlowActionUnit(
                    id = "action:writeBytes",
                    title = "FileUtils.writeBytes(filePath, response.toString())",
                    actionKind = "ACTION",
                ),
                InvocationUnit(
                    id = "invoke:writeBytes",
                    title = "调用 FileUtils.writeBytes",
                    targetSignature = "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
                ),
                TerminalUnit(
                    id = "terminal:return",
                    title = "返回",
                    terminalKind = "RETURN",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:submit", "action:guard-condition"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:guard-condition", "invoke:checkAllowDownload"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "invoke:checkAllowDownload", "scope:guard"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "scope:guard", "action:writeBytes", label = "FALSE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:writeBytes", "invoke:writeBytes"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "invoke:writeBytes", "terminal:return"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val outcome = AnalysisOutcomeFactory().create(
            analysisResult = result,
            displayMode = AnalysisDisplayMode.FLOWCHART,
            projectionPolicy = ProjectionPolicy(),
        )

        assertTrue(outcome.fullGraph.nodes.size > outcome.visibleGraph.nodes.size)
        assertTrue(outcome.projectionStats.truncated)
        assertEquals(3, outcome.projectionStats.hiddenNodeCount)
        assertEquals(outcome.flowchartView?.summary?.hiddenEdgeCount, outcome.projectionStats.hiddenEdgeCount)
    }

    @Test
    fun emitsProjectionStageTraceWhenRuntimeTraceIsProvided() {
        val traceMessages = mutableListOf<String>()
        val result = SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-doc:trace-flow",
                sourcePath = "docs/trace-flow.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
                displayName = "trace-flow.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor-main", targetUnitId = "method:submit", label = "入口")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:submit",
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit():void",
                ),
            ),
            relations = emptyList(),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        AnalysisOutcomeFactory(
            runtimeTrace = { message -> traceMessages += message() },
        ).create(
            analysisResult = result,
            displayMode = AnalysisDisplayMode.FACT_GRAPH,
            projectionPolicy = ProjectionPolicy(),
        )

        assertTrue(traceMessages.any { it.contains("stage=analysis.outcome.factProjector") })
        assertTrue(traceMessages.any { it.contains("stage=analysis.outcome.flowchartProjector") })
        assertTrue(traceMessages.any { it.contains("stage=analysis.outcome.resourceRelationProjector") })
        assertTrue(traceMessages.any { it.contains("stage=analysis.outcome.total") })
    }
}
