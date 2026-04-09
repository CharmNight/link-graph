package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
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
        assertEquals(1, outcome.projectionStats.hiddenNodeCount)
        assertEquals(1, outcome.projectionStats.hiddenEdgeCount)
        assertTrue(outcome.feedbackMessage.contains("order-flow.md"))
    }
}
