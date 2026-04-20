package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowchartProjectorTest {
    @Test
    fun projectReadableFlowchartViewExcludesDraftAnnotationNodesAndDocumentEdges() {
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "scope:delete-if",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    sourceTag = GraphSourceTag.FACT,
                    metadata = mapOf("flowchart.kind" to "DECISION"),
                ),
                GraphNode(
                    id = "draft-note:change-delete",
                    type = NodeType.DOC_PAGE,
                    title = "确认删除前是否要 exists 校验",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                    metadata = mapOf("draft.role" to "change-note"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:file-download->delete-if",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:delete-if",
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "draft-edge:delete-if->change-note",
                    type = EdgeType.LINKS_DOC,
                    fromNodeId = "scope:delete-if",
                    toNodeId = "draft-note:change-delete",
                    sourceTag = GraphSourceTag.DRAFT_AI,
                ),
            ),
        )

        val view = projectReadableFlowchartView(
            graph = graph,
            anchorNodeId = "method:file-download",
        )

        assertEquals(
            listOf("method:file-download", "scope:delete-if"),
            view.visibleGraph.nodes.map { it.id },
        )
        assertEquals(
            listOf("control:file-download->delete-if"),
            view.visibleGraph.edges.map { it.id },
        )
    }

    @Test
    fun projectCollapsesGuardAndInvocationProjectionIntoReadableVisibleGraph() {
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
                    id = "action:guard-condition",
                    title = "!FileUtils.checkAllowDownload(fileName)",
                    actionKind = "CONDITION",
                ),
                InvocationUnit(
                    id = "invoke:checkAllowDownload",
                    title = "调用 FileUtils.checkAllowDownload",
                    targetSignature = "com.example.FileUtils.checkAllowDownload(java.lang.String):boolean",
                ),
                FlowScopeUnit(
                    id = "scope:guard",
                    title = "if (!FileUtils.checkAllowDownload(fileName))",
                    scopeKind = "IF",
                    scopeCategory = FlowScopeCategory.BRANCH,
                ),
                FlowActionUnit(
                    id = "action:writeBytes",
                    title = "FileUtils.writeBytes(filePath, response.toString())",
                    actionKind = "ACTION",
                ),
                InvocationUnit(
                    id = "invoke:writeBytes",
                    title = "调用 FileUtils.writeBytes",
                    targetSignature = "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
                ),
                TerminalUnit(
                    id = "terminal:return",
                    title = "返回",
                    terminalKind = "RETURN",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:submit", "action:guard-condition"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:guard-condition", "invoke:checkAllowDownload"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "invoke:checkAllowDownload", "scope:guard"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "scope:guard", "action:writeBytes", label = "FALSE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:writeBytes", "invoke:writeBytes"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "invoke:writeBytes", "terminal:return"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val view = FlowchartProjector().project(
            analysisResult = result,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 12, maxVisibleEdges = 12),
        )

        val visibleNodesById = view.visibleGraph.nodes.associateBy { node -> node.id }
        assertTrue(view.fullGraph.nodes.size > view.visibleGraph.nodes.size)
        assertEquals(4, view.visibleGraph.nodes.size)
        assertNull(visibleNodesById["action:guard-condition"])
        assertNull(visibleNodesById["invoke:checkAllowDownload"])
        assertNull(visibleNodesById["invoke:writeBytes"])
        assertEquals(
            "action:guard-condition,invoke:checkAllowDownload",
            visibleNodesById["scope:guard"]?.metadata?.get("flowchart.projectedFromNodeIds"),
        )
        assertEquals(
            "invoke:writeBytes",
            visibleNodesById["action:writeBytes"]?.metadata?.get("flowchart.projectedFromNodeIds"),
        )
        assertEquals(
            "调用 FileUtils.writeBytes",
            visibleNodesById["action:writeBytes"]?.title,
        )
        assertEquals(
            "FileUtils.writeBytes(filePath, response.toString())",
            visibleNodesById["action:writeBytes"]?.metadata?.get("flowchart.projectedActionTitle"),
        )
        assertEquals(
            "com.example.FileUtils.writeBytes(java.lang.String,java.lang.String):void",
            visibleNodesById["action:writeBytes"]?.signature,
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.fromNodeId == "method:submit" && edge.toNodeId == "scope:guard"
            },
        )
        assertTrue(
            view.visibleGraph.edges.any { edge ->
                edge.fromNodeId == "action:writeBytes" && edge.toNodeId == "terminal:return"
            },
        )
        assertTrue(view.summary.truncated)
        assertEquals(3, view.summary.hiddenNodeCount)
        assertFalse(view.summary.hiddenEdgeCount == 0)
    }

    @Test
    fun projectHonorsProjectionPolicyForLargeReadableFlowcharts() {
        val stepCount = 18
        val flowUnits = (1..stepCount).map { index ->
            FlowActionUnit(
                id = "action:step-$index",
                title = "执行步骤 $index",
                actionKind = "ACTION",
            )
        }
        val result = SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-doc:large-flow",
                sourcePath = "docs/large-flow.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 10, startLine = 1, endLine = 1),
                displayName = "large-flow.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor-main", targetUnitId = "method:submit", label = "入口")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:submit",
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit():void",
                ),
                *flowUnits.toTypedArray(),
                TerminalUnit(
                    id = "terminal:return",
                    title = "返回",
                    terminalKind = "RETURN",
                ),
            ),
            relations = buildList {
                add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:submit", "action:step-1"))
                (1 until stepCount).forEach { index ->
                    add(
                        SemanticRelation(
                            SemanticRelationKind.CONTROL_FLOW,
                            "action:step-$index",
                            "action:step-${index + 1}",
                        ),
                    )
                }
                add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:step-$stepCount", "terminal:return"))
            },
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val view = FlowchartProjector().project(
            analysisResult = result,
            projectionPolicy = ProjectionPolicy(
                maxVisibleNodes = 6,
                maxVisibleEdges = 5,
            ),
        )

        assertEquals(stepCount + 2, view.fullGraph.nodes.size)
        assertTrue(view.visibleGraph.nodes.size <= 6)
        assertTrue(view.visibleGraph.edges.size <= 5)
        assertTrue(view.summary.truncated)
        assertEquals(view.fullGraph.nodes.size - view.visibleGraph.nodes.size, view.summary.hiddenNodeCount)
    }
}
