package com.charmnight.linkgraph.usage

import com.charmnight.linkgraph.projection.business.ClassUsageGraphProjector
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.architecture.ClassDiagramSummary
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassUsageGraphProjectorTest {
    @Test
    fun projectsStandaloneUsageGraphWithoutClassDiagramBase() {
        val usageResult = orderServiceUsageResult("jvm:class:com-example-order-service")

        val projected = ClassUsageGraphProjector().projectStandalone(usageResult)

        assertEquals("jvm:class:com-example-order-service", projected.anchorNodeId)
        assertNotNull(projected.usage)
        assertEquals("STRUCTURE_ONLY", projected.summary.relationCompleteness)
        assertEquals("com.example.OrderService", projected.summary.anchorTypeQualifiedName)
        assertEquals(2, projected.visibleGraph.nodes.size)
        assertEquals(projected.visibleGraph.nodes.map { it.id }.toSet(), projected.projectionIndex.nodeMappings.keys)
        assertEquals(projected.visibleGraph.edges.map { it.id }.toSet(), projected.projectionIndex.edgeMappings.keys)
        val targetNode = projected.visibleGraph.nodes.single { node -> node.id == "jvm:class:com-example-order-service" }
        assertEquals("OrderService", targetNode.title)
        assertEquals("ANCHOR", targetNode.metadata["presentation.role"])
        val usageEdge = projected.visibleGraph.edges.single()
        assertEquals("CLASS_USAGE", usageEdge.metadata["classDiagram.relation.role"])
    }

    @Test
    fun projectsStandaloneUsageGraphWithTargetAndOwnerNodes() {
        val usageResult = orderServiceUsageResult("jvm:class:com-example-order-service")

        val projected = ClassUsageGraphProjector().projectStandalone(usageResult)

        assertNotNull(projected.usage)
        assertEquals(2, projected.visibleGraph.nodes.size)
        val ownerNode = projected.visibleGraph.nodes.single { node -> node.id == "jvm:class:com-example-order-controller" }
        assertEquals("OrderController", ownerNode.title)
        assertEquals("CALLER", ownerNode.metadata["presentation.role"])
        assertEquals("2", ownerNode.metadata["classUsage.count"])
        val usageEdge = projected.visibleGraph.edges.single()
        assertEquals("jvm:class:com-example-order-controller", usageEdge.fromNodeId)
        assertEquals("jvm:class:com-example-order-service", usageEdge.toNodeId)
        assertEquals("CLASS_USAGE", usageEdge.metadata["classDiagram.relation.role"])
        assertEquals("usage", usageEdge.metadata["classDiagram.relation.label"])
        assertEquals("2", usageEdge.metadata["classUsage.count"])
        assertTrue(projected.summary.relationCount >= 1)
    }

    private fun orderServiceUsageResult(targetNodeId: String): ClassUsageSearchResult =
        ClassUsageSearchResult(
            target = ClassUsageTarget(
                nodeId = targetNodeId,
                qualifiedName = "com.example.OrderService",
                displayName = "OrderService",
            ),
            groups = listOf(
                ClassUsageGroup(
                    id = "usage-owner:order-controller",
                    ownerNodeId = "jvm:class:com-example-order-controller",
                    ownerKind = ClassUsageOwnerKind.CLASS,
                    title = "OrderController",
                    qualifiedName = "com.example.OrderController",
                    filePath = "src/main/java/com/example/OrderController.java",
                    usages = listOf(
                        ClassUsageEntry(
                            id = "usage:1",
                            ownerId = "usage-owner:order-controller",
                            kind = ClassUsageKind.FIELD_TYPE,
                            filePath = "src/main/java/com/example/OrderController.java",
                            line = 6,
                            column = 13,
                            text = "private OrderService orderService;",
                        ),
                        ClassUsageEntry(
                            id = "usage:2",
                            ownerId = "usage-owner:order-controller",
                            kind = ClassUsageKind.METHOD_PARAMETER,
                            filePath = "src/main/java/com/example/OrderController.java",
                            line = 9,
                            column = 21,
                            text = "void submit(OrderService service)",
                        ),
                    ),
                ),
            ),
            summary = ClassUsageSummary(
                targetNodeId = targetNodeId,
                targetQualifiedName = "com.example.OrderService",
                groupCount = 1,
                usageCount = 2,
                visibleGroupCount = 1,
                visibleUsageCount = 2,
            ),
        )
}
