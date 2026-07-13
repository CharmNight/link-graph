package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphBinding
import com.charmnight.linkgraph.model.GraphConfidence
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditApplierTest {
    private val applier = GraphEditApplier()

    @Test
    fun updatesExistingNodesWithoutChangingNodePositionAndAppendsNewNodesInOperationOrder() {
        val oldA = node("node-a", "A")
        val oldB = node("node-b", "B")
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(workspaceGraph = GraphDocument(nodes = listOf(oldA, oldB))),
            request = request(
                GraphEditOperation.UpsertNode(oldB.copy(title = "B2")),
                GraphEditOperation.UpsertNode(node("node-c", "C")),
                GraphEditOperation.UpsertNode(node("node-aa", "AA")),
            ),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("node-a", "node-b", "node-c", "node-aa"), applied.graph.nodes.map { it.id })
        assertEquals("B2", applied.graph.nodes.first { it.id == "node-b" }.title)
        assertTrue(applierResultHasNoIssues(applied))
    }

    @Test
    fun updatesExistingEdgesWithoutChangingEdgePositionAndAppendsNewEdgesInOperationOrder() {
        val nodeA = node("node-a")
        val nodeB = node("node-b")
        val nodeC = node("node-c")
        val edgeAB = edge("edge-ab", "node-a", "node-b", label = "old")
        val edgeBC = edge("edge-bc", "node-b", "node-c", label = "old")
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB, nodeC), edges = listOf(edgeAB, edgeBC)),
            ),
            request = request(
                GraphEditOperation.UpsertEdge(edgeBC.copy(label = "updated")),
                GraphEditOperation.UpsertEdge(edge("edge-ac", "node-a", "node-c")),
                GraphEditOperation.UpsertEdge(edge("edge-aa", "node-a", "node-a")),
            ),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("edge-ab", "edge-bc", "edge-ac", "edge-aa"), applied.graph.edges.map { it.id })
        assertEquals("updated", applied.graph.edges.first { it.id == "edge-bc" }.label)
        assertTrue(applierResultHasNoIssues(applied))
    }

    @Test
    fun deletingNodeCascadesIncidentEdgesAndPreservesPatchField() {
        val patch = com.charmnight.linkgraph.model.GraphPatch(operations = emptyList())
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(
                    nodes = listOf(node("node-a"), node("node-b"), node("node-c")),
                    edges = listOf(
                        edge("edge-ab", "node-a", "node-b"),
                        edge("edge-bc", "node-b", "node-c"),
                        edge("edge-ac", "node-a", "node-c"),
                    ),
                    patch = patch,
                ),
            ),
            request = request(GraphEditOperation.RemoveNode("node-b")),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("node-a", "node-c"), applied.graph.nodes.map { it.id })
        assertEquals(listOf("edge-ac"), applied.graph.edges.map { it.id })
        assertEquals(patch, applied.graph.patch)
    }

    @Test
    fun stripsNavigationFieldsFromNewNodes() {
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(),
            request = request(
                GraphEditOperation.UpsertNode(
                    node("node-new").copy(
                        location = "/tmp/escape.java:1:1",
                        signature = "java.lang.System.exit(int):void",
                    ),
                ),
            ),
            resolution = GraphEditResolution.identity(),
        )

        val newNode = applied.graph.nodes.single()
        assertNull(newNode.location)
        assertNull(newNode.signature)
        assertEquals(GraphProvenance.USER_DRAFT, newNode.provenance)
        assertEquals(GraphConfidence.DECLARED, newNode.confidence)
        assertEquals(GraphBinding.DESIGN_ONLY, newNode.binding)
    }

    @Test
    fun sanitizerEmptyOutputDoesNotWriteRawNode() {
        // 构造一个会让消毒器返回空节点列表的应用器：模拟消毒器拒绝写入
        val rejectingSanitizer = object : FrontendGraphMutationSanitizer() {
            override fun sanitize(
                snapshot: WorkflowEditorSnapshot,
                graph: GraphDocument,
            ): GraphDocument = graph.copy(nodes = emptyList())
        }
        val rejectingApplier = GraphEditApplier(frontendGraphMutationSanitizer = rejectingSanitizer)

        val existing = node("node-existing", "Existing")
        val maliciousNode = node("node-raw").copy(
            location = "/tmp/escape.java:1:1",
            signature = "java.lang.System.exit(int):void",
        )
        val applied = rejectingApplier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(nodes = listOf(existing)),
            ),
            request = request(GraphEditOperation.UpsertNode(maliciousNode)),
            resolution = GraphEditResolution.identity(),
        )

        // ① 不修改 nodesById：原节点仍在，恶意节点未被写入
        assertEquals(listOf("node-existing"), applied.graph.nodes.map { it.id })
        // ② 返回结果包含 issue
        assertEquals(1, applied.issues.size)
        // ③ issue code 与新增常量匹配
        assertEquals(GraphEditIssueCode.SANITIZER_REJECTED_NODE, applied.issues.single().code)
        assertEquals("node-raw", applied.issues.single().targetId)
        assertEquals(0, applied.issues.single().operationIndex)
    }

    @Test
    fun trustedMetadataProtectedFromFrontendOverwrite() {
        // 可信节点已带 jvm.class.kind=CLASS、signature 等不可信前端不应覆盖的字段
        val trustedNode = node("node-trusted", "Trusted").copy(
            metadata = mapOf(
                "jvm.class.kind" to "CLASS",
                "signature" to "com.example.Real.signature():void",
                "source.path" to "/trusted/Real.java",
                "presentation.color" to "trusted-color",
            ),
        )
        // 模拟 WorkspaceEditorSnapshot 暴露 trustedNavigationNodes
        val snapshot = WorkflowEditorSnapshot(
            workspaceGraph = GraphDocument(nodes = listOf(trustedNode)),
            trustedNavigationNodes = linkedMapOf(trustedNode.id to trustedNode),
        )
        // 前端构造的 upsert：试图把 signature、jvm.class.kind 改成恶意值
        // 注意：FrontendGraphMutationSanitizer 对已知节点会保留 trusted 的 title/inputs/outputs/doc，
        // 但元数据字段会从 node.metadata 直传，这里手动构造一个不消毒的应用器来精确测试合并逻辑
        val passthroughSanitizer = object : FrontendGraphMutationSanitizer() {
            override fun sanitize(
                snapshot: WorkflowEditorSnapshot,
                graph: GraphDocument,
            ): GraphDocument = graph
        }
        val applier = GraphEditApplier(frontendGraphMutationSanitizer = passthroughSanitizer)
        val maliciousPayload = node("node-trusted", "Trusted").copy(
            metadata = mapOf(
                "jvm.class.kind" to "INTERFACE",
                "signature" to "java.lang.System.exit(int):void",
                "source.path" to "/untrusted/Escape.java",
                "presentation.color" to "frontend-color",
                "layout.x" to "100",
            ),
        )

        val applied = applier.apply(
            snapshot = snapshot,
            request = request(GraphEditOperation.UpsertNode(maliciousPayload)),
            resolution = GraphEditResolution.identity(),
        )

        val merged = applied.graph.nodes.single { it.id == "node-trusted" }.metadata
        // 受保护前缀：trusted 值胜出
        assertEquals("CLASS", merged["jvm.class.kind"], "jvm.class.kind 必须保持 trusted 值")
        assertEquals("com.example.Real.signature():void", merged["signature"], "signature 必须保持 trusted 值")
        assertEquals("/trusted/Real.java", merged["source.path"], "source.path 必须保持 trusted 值")
        // 非受保护前缀：前端值胜出
        assertEquals("frontend-color", merged["presentation.color"], "非受保护字段允许前端覆盖")
        assertEquals("100", merged["layout.x"], "layout.* 应允许前端写入")
    }

    @Test
    fun stripsTrustedMetadataAndFactSourceFromNewFrontendEdges() {
        val nodeA = node("node-a")
        val nodeB = node("node-b")
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB)),
            ),
            request = request(
                GraphEditOperation.UpsertEdge(
                    edge("edge-ab", "node-a", "node-b").copy(
                        provenance = GraphProvenance.CODE_ANALYSIS,
                        metadata = mapOf(
                            "source.path" to "/tmp/Escape.java",
                            "jvm.relation.kind" to "CALLS",
                            "signature.edge" to "evil",
                            "presentation.color" to "frontend-color",
                            "layout.x" to "100",
                        ),
                    ),
                ),
            ),
            resolution = GraphEditResolution.identity(),
        )

        val newEdge = applied.graph.edges.single()
        assertEquals(GraphProvenance.USER_DRAFT, newEdge.provenance)
        assertEquals(GraphConfidence.DECLARED, newEdge.confidence)
        assertEquals(GraphBinding.DESIGN_ONLY, newEdge.binding)
        assertEquals(
            mapOf(
                "presentation.color" to "frontend-color",
                "layout.x" to "100",
            ),
            newEdge.metadata,
        )
    }

    @Test
    fun existingEdgeTrustedMetadataAndSourceTagProtectedFromFrontendOverwrite() {
        val nodeA = node("node-a")
        val nodeB = node("node-b")
        val trustedEdge = edge("edge-ab", "node-a", "node-b", label = "old").copy(
            provenance = GraphProvenance.CODE_ANALYSIS,
            metadata = mapOf(
                "jvm.relation.kind" to "CALLS",
                "source.path" to "/trusted/Real.java",
                "presentation.color" to "trusted-color",
            ),
        )
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB), edges = listOf(trustedEdge)),
            ),
            request = request(
                GraphEditOperation.UpsertEdge(
                    edge("edge-ab", "node-a", "node-b", label = "updated").copy(
                        provenance = GraphProvenance.AI_DRAFT,
                        metadata = mapOf(
                            "jvm.relation.kind" to "OVERRIDDEN",
                            "source.path" to "/untrusted/Escape.java",
                            "presentation.color" to "frontend-color",
                            "layout.x" to "100",
                        ),
                    ),
                ),
            ),
            resolution = GraphEditResolution.identity(),
        )

        val merged = applied.graph.edges.single()
        assertEquals("updated", merged.label)
        assertEquals(GraphProvenance.CODE_ANALYSIS, merged.provenance)
        assertEquals("CALLS", merged.metadata["jvm.relation.kind"])
        assertEquals("/trusted/Real.java", merged.metadata["source.path"])
        assertEquals("frontend-color", merged.metadata["presentation.color"])
        assertEquals("100", merged.metadata["layout.x"])
    }

    private fun applierResultHasNoIssues(result: GraphEditApplierResult): Boolean = result.issues.isEmpty()

    private fun request(vararg operations: GraphEditOperation) =
        GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 1,
            operations = operations.toList(),
            source = GraphEditRequestSource.FRONTEND,
        )

    private fun node(id: String, title: String = id): GraphNode =
        GraphNode(id = id, type = NodeType.METHOD, title = title)

    private fun edge(id: String, from: String, to: String, label: String? = null): GraphEdge =
        GraphEdge(id = id, type = EdgeType.CALL, fromNodeId = from, toNodeId = to, label = label)
}
