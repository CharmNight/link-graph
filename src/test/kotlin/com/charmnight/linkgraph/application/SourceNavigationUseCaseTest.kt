package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.usecase.SourceNavigationUseCase
import com.charmnight.linkgraph.application.usecase.SourceNavigationUseCaseResult
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SourceNavigationUseCaseTest {
    @Test
    fun returnsMissingTrustedNodeWhenIndexCannotResolveNode() {
        val result = SourceNavigationUseCase { _, _ -> null }
            .requestSourceNavigation(WorkflowEditorSnapshot(), "missing")

        val missing = assertIs<SourceNavigationUseCaseResult.MissingTrustedNode>(result)
        assertEquals("missing", missing.nodeId)
    }

    @Test
    fun returnsReadyForMethodSignatureNode() {
        val node = GraphNode(
            id = "method:run",
            type = NodeType.METHOD,
            title = "run",
            signature = "com.example.Service.run():void",
        )

        val result = SourceNavigationUseCase { _, _ -> node }
            .requestSourceNavigation(WorkflowEditorSnapshot(), node.id)

        val ready = assertIs<SourceNavigationUseCaseResult.Ready>(result)
        assertSame(node, ready.node)
    }

    @Test
    fun rejectsNodeWithoutSourceAnchor() {
        val node = GraphNode(id = "config:feature", type = NodeType.CONFIG_ITEM, title = "feature.flag")

        val result = SourceNavigationUseCase { _, _ -> node }
            .requestSourceNavigation(WorkflowEditorSnapshot(), node.id)

        val notNavigable = assertIs<SourceNavigationUseCaseResult.NotNavigable>(result)
        assertSame(node, notNavigable.node)
    }

    @Test
    fun returnsReadyForAggregateNodeWithSourceNavigationAnchor() {
        val node = GraphNode(
            id = "component:orders",
            type = NodeType.COMPONENT,
            title = "orders",
            metadata = SourceNavigationAnchors.metadata(
                nodeId = "class:com.example.orders.OrderService",
                filePath = "src/main/java/com/example/orders/OrderService.java",
                startLine = 12,
                reason = "architecture-member-class:component:orders",
            ),
        )

        val result = SourceNavigationUseCase { _, _ -> node }
            .requestSourceNavigation(WorkflowEditorSnapshot(), node.id)

        val ready = assertIs<SourceNavigationUseCaseResult.Ready>(result)
        assertSame(node, ready.node)
    }
}
