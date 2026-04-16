package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.semantic.model.FlowActionUnit
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
import kotlin.test.assertFalse

class FlowchartProjectorTest {
    @Test
    fun projectUsesFullGraphAsVisibleGraphEvenWhenProjectionBudgetIsTight() {
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
                    id = "action:validate",
                    title = "validate()",
                    actionKind = "ACTION",
                ),
                FlowActionUnit(
                    id = "action:save",
                    title = "save()",
                    actionKind = "ACTION",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:submit", "action:validate"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:validate", "action:save"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val view = FlowchartProjector().project(
            analysisResult = result,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 1, maxVisibleEdges = 0),
        )

        assertEquals(view.fullGraph, view.visibleGraph)
        assertFalse(view.summary.truncated)
        assertEquals(0, view.summary.hiddenNodeCount)
        assertEquals(0, view.summary.hiddenEdgeCount)
    }
}
