package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.testing.testSnapshot
import com.charmnight.linkgraph.testing.toToolGraphSnapshot
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.semantic.outcome.FlowchartSummary
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QaEvidenceAnchorResolverTest {
    @Test
    fun resolvesProjectedFlowchartNodeBackToCanonicalSourceNode() {
        val projectedNode = GraphNode(
            id = "flow-action:trigger-guard",
            type = NodeType.FLOW_ACTION,
            title = "if (enabled)",
            metadata = mapOf("flowchart.kind" to "PROCESS"),
        )
        val realMethodNode = GraphNode(
            id = "method:scheduled-cleanup",
            type = NodeType.METHOD,
            title = "CleanupJob.run",
            signature = "com.example.CleanupJob.run():void",
            metadata = mapOf(
                "source.filePath" to "src/main/java/com/example/CleanupJob.java",
                "source.startLine" to "12",
                "source.endLine" to "18",
            ),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
            workspaceGraph = GraphDocument(nodes = listOf(projectedNode)),
            semanticFactGraph = GraphDocument(nodes = listOf(realMethodNode)),
            flowchartView = FlowchartViewDocument(
                visibleGraph = GraphDocument(nodes = listOf(projectedNode)),
                fullGraph = GraphDocument(nodes = listOf(projectedNode)),
                anchorNodeId = projectedNode.id,
                projectionIndex = GraphProjectionIndex(
                    nodeMappings = mapOf(
                        projectedNode.id to GraphProjectionNodeMapping(
                            projectedNodeId = projectedNode.id,
                            mappingKind = GraphProjectionMappingKind.PATH_ALIAS,
                            canonicalNodeIds = listOf(realMethodNode.id),
                        ),
                    ),
                ),
                summary = FlowchartSummary(nodeCount = 1, branchCount = 0, exceptionPathCount = 0),
            ),
        ).toToolGraphSnapshot()

        val resolution = QaEvidenceAnchorResolver().resolve(snapshot, nodeId = projectedNode.id)

        assertEquals(realMethodNode.id, resolution.node?.id)
        assertEquals(projectedNode.id, resolution.requestedNodeId)
        assertEquals(realMethodNode.id, resolution.resolvedNodeId)
        assertTrue(resolution.mappingTrace.any { it.contains("projectionIndex:${projectedNode.id}->${realMethodNode.id}") })
        assertTrue(resolution.mappingTrace.any { it.contains("semanticFactGraph:${realMethodNode.id}") })
    }

    @Test
    fun resolvesSymbolSignatureToMethodNodeBeforeInvocationCallsiteNode() {
        val targetSignature = "com.example.UploadServiceImpl.uploadFile():boolean"
        val invocationNode = GraphNode(
            id = "invoke:controller-upload-to-service",
            type = NodeType.FLOW_ACTION,
            title = "调用 UploadServiceImpl.uploadFile",
            signature = targetSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to "com.example.FileUploadController.uploadFile():void",
                "source.filePath" to "src/main/java/com/example/FileUploadController.java",
                "source.startLine" to "40",
                "source.endLine" to "40",
            ),
        )
        val methodNode = GraphNode(
            id = "method:upload-service-impl-upload-file",
            type = NodeType.METHOD,
            title = "UploadServiceImpl.uploadFile",
            signature = targetSignature,
            metadata = mapOf(
                "source.filePath" to "src/main/java/com/example/UploadServiceImpl.java",
                "source.startLine" to "12",
                "source.endLine" to "16",
            ),
        )
        val snapshot = ToolGraphSnapshot(
            workspaceGraph = GraphDocument(nodes = listOf(invocationNode, methodNode)),
        )

        val resolution = QaEvidenceAnchorResolver().resolve(snapshot, symbolSignature = targetSignature)

        assertEquals(methodNode.id, resolution.node?.id)
        assertEquals(methodNode.id, resolution.resolvedNodeId)
    }

    @Test
    fun resolvesSymbolSignatureToMethodNodeAcrossDocumentsBeforeCurrentGraphInvocationCallsite() {
        val targetSignature = "com.example.UploadServiceImpl.uploadFile():boolean"
        val invocationNode = GraphNode(
            id = "invoke:controller-upload-to-service",
            type = NodeType.FLOW_ACTION,
            title = "调用 UploadServiceImpl.uploadFile",
            signature = targetSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to "com.example.FileUploadController.uploadFile():void",
                "source.filePath" to "src/main/java/com/example/FileUploadController.java",
                "source.startLine" to "40",
                "source.endLine" to "40",
            ),
        )
        val methodNode = GraphNode(
            id = "method:upload-service-impl-upload-file",
            type = NodeType.METHOD,
            title = "UploadServiceImpl.uploadFile",
            signature = targetSignature,
            metadata = mapOf(
                "source.filePath" to "src/main/java/com/example/UploadServiceImpl.java",
                "source.startLine" to "12",
                "source.endLine" to "16",
            ),
        )
        val snapshot = ToolGraphSnapshot(
            factGraphView = ToolGraphView(
                visibleGraph = GraphDocument(nodes = listOf(invocationNode)),
                fullGraph = GraphDocument(nodes = listOf(invocationNode)),
            ),
            workspaceGraph = GraphDocument(nodes = listOf(methodNode)),
            semanticFactGraph = GraphDocument(nodes = listOf(methodNode)),
        )

        val resolution = QaEvidenceAnchorResolver().resolve(snapshot, symbolSignature = targetSignature)

        assertEquals(methodNode.id, resolution.node?.id)
        assertEquals(methodNode.id, resolution.resolvedNodeId)
    }
}
