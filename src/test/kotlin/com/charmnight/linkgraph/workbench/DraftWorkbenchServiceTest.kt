package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphProvenance
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraftWorkbenchServiceTest {
    @Test
    fun `confirming candidate change writes only to draft layer and marks graph as changed`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-condition",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-upload-condition",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "修改条件判断",
                targetNodeIds = listOf("method:upload-condition"),
                beforeState = "if (a > 10)",
                afterState = "if (a < 100)",
                reason = "原判断条件错误。",
                impactSummary = "会影响上传分支。",
                claimType = "EXPLANATION_NOTE",
            ),
            baseGraph = baseGraph,
        )

        assertEquals(1, result.draftChanges.size)
        assertEquals(DraftEntryKind.CHANGE, result.draftChanges.first().kind)
        assertEquals("change-upload-condition", result.draftChanges.first().sourceChangeId)
        assertTrue(result.graphChanged)
    }

    @Test
    fun `confirming a code fact change with explanatory afterState keeps the real decision title stable`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "flow-scope:delete-guard",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-delete-guard",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "调整删除判断",
                targetNodeIds = listOf("flow-scope:delete-guard"),
                beforeState = "if (delete)",
                afterState = "已观察到源码中的删除逻辑是 if (delete)，但当前图中没有该条件节点的真实控制流 ID。",
                reason = "需要显式判断布尔值。",
                impactSummary = "影响删除分支的进入条件。",
                claimType = "CODE_FACT",
            ),
            baseGraph = baseGraph,
        )

        val patch = result.draftChanges.single().graphPatch
        assertEquals(GraphPatchAction.UPDATE_NODE, patch?.operations?.singleOrNull()?.action)
        assertEquals("flow-scope:delete-guard", patch?.operations?.singleOrNull()?.node?.id)
        assertEquals("if (delete)", patch?.operations?.singleOrNull()?.node?.title)
        assertTrue(patch?.operations?.singleOrNull()?.node?.doc?.contains("已观察到源码中的删除逻辑是 if (delete)") == true)
        assertTrue(patch?.addedNodeIds?.isEmpty() == true)
    }

    @Test
    fun `normalizing an update patch preserves if scope decision shape when candidate metadata says process`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:delete-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf(
                        "flow.kind" to "IF",
                        "flowchart.kind" to "DECISION",
                    ),
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-delete-if",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "收紧删除判断",
                targetNodeIds = listOf("scope:delete-if"),
                beforeState = "if (delete)",
                afterState = "if (Boolean.TRUE.equals(delete))",
                reason = "delete 是包装类型。",
                impactSummary = "影响删除分支。",
                claimType = "CODE_FACT",
                graphPatch = GraphPatch(
                    summary = "更新删除判断",
                    operations = listOf(
                        GraphPatchOperation(
                            id = "patch-update-delete-if",
                            action = GraphPatchAction.UPDATE_NODE,
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = "scope:delete-if",
                            node = GraphNode(
                                id = "scope:delete-if",
                                type = NodeType.FLOW_SCOPE,
                                title = "if (Boolean.TRUE.equals(delete))",
                                provenance = GraphProvenance.AI_DRAFT,
                                metadata = mapOf("flowchart.kind" to "PROCESS"),
                            ),
                        ),
                    ),
                ),
            ),
            baseGraph = baseGraph,
        )

        val normalizedNode = result.draftChanges.single().graphPatch?.operations?.singleOrNull()?.node
        assertEquals("DECISION", normalizedNode?.metadata?.get("flowchart.kind"))
    }

    @Test
    fun `confirming an explanation note still creates an annotation node`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-download-note",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "补充下载说明",
                targetNodeIds = listOf("method:file-download"),
                afterState = "这里只是解释需求，不对应真实流程改动",
                reason = "帮助阅读链路。",
                impactSummary = "不改变真实控制流。",
                claimType = "EXPLANATION_NOTE",
            ),
            baseGraph = baseGraph,
        )

        val patch = result.draftChanges.single().graphPatch
        assertEquals(GraphPatchAction.ADD_ANNOTATION, patch?.operations?.firstOrNull()?.action)
        assertEquals("draft-note:change-download-note", patch?.operations?.firstOrNull()?.node?.id)
    }

    @Test
    fun `explicit update intent updates the declared node instead of guessing another target`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:file-download-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-delete-guard",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "收紧删除条件",
                targetNodeIds = listOf("scope:file-download-if"),
                beforeState = "if (delete)",
                afterState = "if (Boolean.TRUE.equals(delete))",
                reason = "需要显式 true 判断。",
                impactSummary = "影响删除分支进入条件。",
                claimType = "CODE_FACT",
                patchIntent = CandidatePatchIntent(
                    mode = CandidatePatchIntentMode.UPDATE_EXISTING_NODE,
                    targetNodeId = "scope:file-download-if",
                ),
            ),
            baseGraph = baseGraph,
        )

        val patch = result.draftChanges.single().graphPatch
        assertEquals(GraphPatchAction.UPDATE_NODE, patch?.operations?.singleOrNull()?.action)
        assertEquals("scope:file-download-if", patch?.operations?.singleOrNull()?.elementId)
        assertEquals("if (Boolean.TRUE.equals(delete))", patch?.operations?.singleOrNull()?.node?.title)
    }

    @Test
    fun `explicit insert action intent splits the declared control flow edge`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "action:write-bytes",
                    type = NodeType.FLOW_ACTION,
                    title = "FileUtils.writeBytes(filePath, response.getOutputStream())",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf("flowchart.kind" to "PROCESS"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-write",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "action:write-bytes",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-insert-file-check",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "在写出响应前补充路径规范化",
                targetNodeIds = listOf("action:write-bytes"),
                afterState = "normalizeFilePath(filePath)",
                reason = "先规范化路径再继续写出。",
                impactSummary = "新增一个前置处理动作。",
                claimType = "STRUCTURAL_SUGGESTION",
                patchIntent = CandidatePatchIntent(
                    mode = CandidatePatchIntentMode.INSERT_NEW_ACTION,
                    attachEdgeId = "edge:entry-write",
                ),
            ),
            baseGraph = baseGraph,
        )

        val patch = result.draftChanges.single().graphPatch
        assertNotNull(patch)
        assertEquals(listOf("edge:entry-write"), patch.removedEdgeIds)
        assertEquals(1, patch.addedNodeIds.size)
        assertEquals(2, patch.addedEdgeIds.size)
        assertEquals(GraphPatchAction.DELETE_EDGE, patch.operations[0].action)
        assertEquals(GraphPatchAction.ADD_NODE, patch.operations[1].action)
        assertEquals(NodeType.FLOW_ACTION, patch.operations[1].node?.type)
        assertEquals("normalizeFilePath(filePath)", patch.operations[1].node?.title)
    }

    @Test
    fun `explicit insert decision intent splits the declared control flow edge and creates true false branches`() {
        val service = DraftWorkbenchService()
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "action:delete-file",
                    type = NodeType.FLOW_ACTION,
                    title = "FileUtils.deleteFile(filePath)",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf(
                        "flowchart.kind" to "PROCESS",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
                GraphNode(
                    id = "terminal:return",
                    type = NodeType.TERMINAL,
                    title = "return",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                    metadata = mapOf(
                        "flowchart.kind" to "TERMINAL",
                        "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-delete",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "action:delete-file",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphEdge(
                    id = "edge:delete-return",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "action:delete-file",
                    toNodeId = "terminal:return",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-insert-file-exists-guard",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "在删除前增加文件存在性判断",
                targetNodeIds = listOf("action:delete-file", "terminal:return"),
                afterState = "if (fileExists(filePath))",
                reason = "不存在时直接跳过删除。",
                impactSummary = "新增一个显式决策节点。",
                claimType = "STRUCTURAL_SUGGESTION",
                patchIntent = CandidatePatchIntent(
                    mode = CandidatePatchIntentMode.INSERT_NEW_DECISION,
                    attachEdgeId = "edge:entry-delete",
                    falseBranchTargetNodeId = "terminal:return",
                ),
            ),
            baseGraph = baseGraph,
        )

        val patch = result.draftChanges.single().graphPatch
        assertNotNull(patch)
        assertEquals(listOf("edge:entry-delete"), patch.removedEdgeIds)
        assertEquals(1, patch.addedNodeIds.size)
        val insertedDecision = patch.operations.firstOrNull { operation -> operation.action == GraphPatchAction.ADD_NODE }?.node
        assertEquals(NodeType.FLOW_SCOPE, insertedDecision?.type)
        assertEquals("if (fileExists(filePath))", insertedDecision?.title)
        assertEquals("DECISION", insertedDecision?.metadata?.get("flowchart.kind"))
        val addedEdges = patch.operations.filter { operation -> operation.action == GraphPatchAction.ADD_EDGE }
        assertEquals(3, addedEdges.size)
        assertTrue(addedEdges.any { operation -> operation.edge?.label == "TRUE" && operation.edge.toNodeId == "action:delete-file" })
        assertTrue(addedEdges.any { operation -> operation.edge?.label == "FALSE" && operation.edge.toNodeId == "terminal:return" })
    }
}
