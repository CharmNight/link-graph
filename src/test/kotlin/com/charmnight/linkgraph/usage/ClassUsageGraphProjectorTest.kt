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

    /**
     * 验证：trusted 基础视图的 `jvm.class.kind` 等受保护元数据在叠加 overlay 后保持不变。
     *
     * 旧实现 `metadata + overlay.metadata`（未做前缀过滤）会让 overlay 携带的同名 key 覆盖 trusted 值。
     * 现在过滤掉非 allowlist 前缀（classUsage./presentation./layout.），trusted 字段得以保留。
     */
    @Test
    fun preservesTrustedJvmClassKindWhenOverlyingUsage() {
        val usageResult = orderServiceUsageResult("jvm:class:com-example-order-service")
        // 构造一个带 trusted jvm.class.kind 的基础视图
        val trustedOwnerNode = GraphNode(
            id = "jvm:class:com-example-order-controller",
            type = NodeType.CLASS,
            title = "OrderController",
            metadata = mapOf(
                "jvm.class.kind" to "CLASS",
                "source.path" to "src/main/java/com/example/OrderController.java",
                "presentation.color" to "trusted-color",
            ),
        )
        val trustedAnchor = GraphNode(
            id = "jvm:class:com-example-order-service",
            type = NodeType.CLASS,
            title = "OrderService",
            metadata = mapOf("jvm.class.kind" to "CLASS"),
        )
        val baseGraph = GraphDocument(nodes = listOf(trustedAnchor, trustedOwnerNode))
        // 直接拿 standalone 投影后的视图作为 "已带 trusted jvm.class.kind" 的基础视图
        val standalone = ClassUsageGraphProjector().projectStandalone(usageResult)
        // 把 trusted 节点替换进 visibleGraph（确保基础视图真的带有 trusted 元数据）
        val baseView = standalone.copy(
            visibleGraph = baseGraph,
            fullGraph = baseGraph,
        )

        val projected = ClassUsageGraphProjector().project(baseView, usageResult)
        val mergedOwner = projected.visibleGraph.nodes.single { it.id == "jvm:class:com-example-order-controller" }

        // trusted 元数据保留
        assertEquals("CLASS", mergedOwner.metadata["jvm.class.kind"], "jvm.class.kind 必须保留 trusted 值")
        assertEquals(
            "src/main/java/com/example/OrderController.java",
            mergedOwner.metadata["source.path"],
            "source.path 必须保留 trusted 值",
        )
        // overlay 写入的 counts 应生效
        assertEquals("2", mergedOwner.metadata["classUsage.count"], "classUsage.count 应来自 overlay")
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
