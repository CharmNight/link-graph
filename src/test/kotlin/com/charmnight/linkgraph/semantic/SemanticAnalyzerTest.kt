package com.charmnight.linkgraph.semantic

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticBoundary
import com.charmnight.linkgraph.semantic.model.SemanticDiagnostic
import com.charmnight.linkgraph.semantic.model.SemanticDiagnosticSeverity
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticAnalyzerTest {
    @Test
    fun 分析器应当把主体分发给匹配的Provider() {
        val subject = ResourceSubjectHandle(
            subjectId = "resource-markdown:order-flow-md",
            sourcePath = "docs/order-flow.md",
            sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
            displayName = "order-flow.md",
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
        )
        val provider = object : SemanticProvider {
            override fun supports(handle: com.charmnight.linkgraph.semantic.subject.SubjectHandle): Boolean {
                return handle is ResourceSubjectHandle && handle.kind == ResourceSubjectKind.MARKDOWN_PAGE
            }

            override fun analyze(
                handle: com.charmnight.linkgraph.semantic.subject.SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                return SemanticAnalysisResult(
                    subject = handle,
                    anchors = listOf(
                        SemanticAnchor(
                            id = "anchor-doc",
                            targetUnitId = "unit-order-place",
                            label = "文档锚点",
                        ),
                    ),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = "unit-order-place",
                            title = "OrderService.place",
                            signature = "com.example.OrderService.place(java.lang.String):void",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(
                            kind = SemanticRelationKind.DOCUMENTS,
                            fromUnitId = "resource-doc",
                            toUnitId = "unit-order-place",
                        ),
                    ),
                    diagnostics = listOf(
                        SemanticDiagnostic(
                            severity = SemanticDiagnosticSeverity.INFO,
                            code = "document-anchor",
                            message = "已识别文档锚点。",
                        ),
                    ),
                    boundaries = listOf(
                        SemanticBoundary(
                            title = "文档页边界",
                            reason = "仅分析当前文档页。",
                            kind = "DOCUMENT_PAGE_BOUNDARY",
                        ),
                    ),
                    sourceMappings = listOf(
                        SourceMapping(
                            sourcePath = "docs/order-flow.md",
                            sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
                            targetUnitId = "unit-order-place",
                        ),
                    ),
                )
            }
        }

        val analyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )

        val result = analyzer.analyze(
            handle = subject,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(),
        )

        assertEquals(subject, result.subject)
        assertEquals(1, result.semanticUnits.size)
        assertEquals(1, result.relations.size)
    }
}
