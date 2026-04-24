package com.charmnight.linkgraph.semantic.model

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticAnalysisResultTest {
    @Test
    fun 语义结果必须保留主体锚点边界诊断与映射() {
        val result = SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-markdown:order-flow-md",
                sourcePath = "docs/order-flow.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 32, startLine = 1, endLine = 2),
                displayName = "order-flow.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(
                SemanticAnchor(
                    id = "anchor-1",
                    targetUnitId = "unit-method-place-order",
                    label = "当前主体锚点",
                ),
            ),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "unit-method-place-order",
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                ),
            ),
            relations = listOf(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTAINS,
                    fromUnitId = "subject:docs/order-flow.md",
                    toUnitId = "unit-method-place-order",
                ),
            ),
            diagnostics = listOf(
                SemanticDiagnostic(
                    severity = SemanticDiagnosticSeverity.INFO,
                    code = "anchor-detected",
                    message = "已从文档中识别到方法锚点。",
                ),
            ),
            boundaries = listOf(
                SemanticBoundary(
                    title = "文档主体边界",
                    reason = "当前主体只覆盖当前文档页。",
                    kind = "DOCUMENT_PAGE_BOUNDARY",
                ),
            ),
            sourceMappings = listOf(
                SourceMapping(
                    sourcePath = "docs/order-flow.md",
                    sourceRange = SourceRange(startOffset = 3, endOffset = 18, startLine = 1, endLine = 1),
                    targetUnitId = "unit-method-place-order",
                ),
            ),
        )

        assertEquals(1, result.anchors.size)
        assertEquals(1, result.semanticUnits.size)
        assertEquals(1, result.relations.size)
        assertEquals(1, result.diagnostics.size)
        assertEquals(1, result.boundaries.size)
        assertEquals(1, result.sourceMappings.size)
    }
}
