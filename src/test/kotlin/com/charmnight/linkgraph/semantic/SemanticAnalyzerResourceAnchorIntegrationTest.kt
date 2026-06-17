package com.charmnight.linkgraph.semantic

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SemanticAnalyzerResourceAnchorIntegrationTest : BasePlatformTestCase() {
    fun testExactMarkdownAnchorShouldMergeResourceAndResolvedCodeSemantic() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    String submit(String value) {
                        return normalize(value);
                    }

                    String submit(Long value) {
                        return store(value);
                    }

                    String normalize(String value) {
                        return value.trim();
                    }

                    String store(Long value) {
                        return String.valueOf(value);
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口：com.example.OrderService.sub<caret>mit(java.lang.Long):java.lang.String
            """.trimIndent(),
        )

        val handle = assertInstanceOf(
            CaretSubjectLocator().locate(project, myFixture.editor),
            ResourceSubjectHandle::class.java,
        )
        val analyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    CodeSemanticProvider(),
                    MarkdownSemanticProvider(),
                ),
            ),
            project = project,
        )

        val result = analyzer.analyze(
            handle = handle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(
                maxDownstreamDepth = 6,
                maxUpstreamDepth = 2,
                maxInvocationsPerUnit = 16,
            ),
        )
        val outcome = AnalysisOutcomeFactory().create(
            analysisResult = result,
            displayMode = AnalysisDisplayMode.FACT_GRAPH,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 64, maxVisibleEdges = 128),
        )

        assertTrue(result.relations.any { relation -> relation.kind.name == "DOCUMENTS" })
        assertTrue(outcome.visibleGraph.nodes.any { node ->
            node.type == NodeType.DOC_PAGE && node.title == "order-flow.md"
        })
        assertTrue(outcome.visibleGraph.nodes.any { node ->
            node.type == NodeType.METHOD &&
                node.signature == "com.example.OrderService.submit(java.lang.Long):java.lang.String"
        })
        assertTrue(outcome.visibleGraph.nodes.any { node ->
            node.type == NodeType.METHOD && node.title == "OrderService.store"
        })
        assertFalse(outcome.visibleGraph.nodes.any { node ->
            node.type == NodeType.METHOD && node.title == "OrderService.normalize"
        })
    }

    fun testAmbiguousMarkdownAnchorShouldFallbackToResourceOnlyWithWarningMetadata() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
                package com.example;

                class OrderService {
                    String submit(String value) {
                        return normalize(value);
                    }

                    String submit(Long value) {
                        return store(value);
                    }

                    String normalize(String value) {
                        return value.trim();
                    }

                    String store(Long value) {
                        return String.valueOf(value);
                    }
                }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口：com.example.OrderService.sub<caret>mit
            """.trimIndent(),
        )

        val handle = assertInstanceOf(
            CaretSubjectLocator().locate(project, myFixture.editor),
            ResourceSubjectHandle::class.java,
        )
        val analyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    CodeSemanticProvider(),
                    MarkdownSemanticProvider(),
                ),
            ),
            project = project,
        )

        val result = analyzer.analyze(
            handle = handle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(
                maxDownstreamDepth = 6,
                maxUpstreamDepth = 2,
                maxInvocationsPerUnit = 16,
            ),
        )
        val outcome = AnalysisOutcomeFactory().create(
            analysisResult = result,
            displayMode = AnalysisDisplayMode.FACT_GRAPH,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 64, maxVisibleEdges = 128),
        )

        assertEquals(ApplicationFeedbackLevel.WARNING, outcome.feedbackLevel)
        assertTrue(outcome.statusMessage.contains("候选"))
        assertEquals(1, outcome.visibleGraph.nodes.size)
        val docNode = outcome.visibleGraph.nodes.single()
        assertEquals(NodeType.DOC_PAGE, docNode.type)
        assertEquals("AMBIGUOUS", docNode.metadata["linkGraph.anchorResolutionState"])
        assertTrue(docNode.metadata["linkGraph.anchorResolutionHint"].orEmpty().contains("候选"))
        assertTrue(
            docNode.metadata["linkGraph.anchorCandidates"]
                .orEmpty()
                .contains("com.example.OrderService.submit(java.lang.String):java.lang.String"),
        )
        assertTrue(
            docNode.metadata["linkGraph.anchorCandidates"]
                .orEmpty()
                .contains("com.example.OrderService.submit(java.lang.Long):java.lang.String"),
        )
    }
}
