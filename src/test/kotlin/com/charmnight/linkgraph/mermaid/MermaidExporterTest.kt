package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MermaidExporterTest {
    @Test
    fun exporterSanitizesComplexMethodLabelsToReadableMermaidText() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:format-order",
                    type = NodeType.METHOD,
                    title = "QaService.format",
                    signature = "com.example.QaService.format(java.lang.String,java.util.Map):java.lang.String",
                    inputs = listOf(
                        "java.lang.String",
                        "java.util.Map<java.lang.String,java.lang.Object>",
                    ),
                    outputs = listOf("java.lang.String"),
                    doc = "格式化 [订单] {上下文} | 需要保留可读性",
                ),
                GraphNode(
                    id = "doc:review",
                    type = NodeType.DOC_PAGE,
                    title = "Review(\"A->B\")",
                    doc = "说明里包含 [] {} () <> | 和双引号 \"",
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:format->review",
                    type = EdgeType.LINKS_DOC,
                    fromNodeId = "method:format-order",
                    toNodeId = "doc:review",
                    label = "问答建议 [A->B] {保留}",
                ),
            ),
        )

        val exported = MermaidExporter().export(document)

        assertTrue(exported.contains("graph TD"))
        assertTrue(exported.contains("%% LG_NODE N1|"))
        assertTrue(exported.contains("%% LG_EDGE "))
        assertFalse(exported.contains("[订单]"))
        assertFalse(exported.contains("{上下文}"))
        assertFalse(exported.contains("Review(\"A->B\")"))
        assertFalse(exported.contains("问答建议 [A->B] {保留}"))
        assertTrue(exported.contains("格式化 ［订单］ ｛上下文｝ ｜ 需要保留可读性"))
        assertTrue(exported.contains("Review（＂A-〉B＂）"))
        assertTrue(exported.contains("问答建议 ［A-〉B］ ｛保留｝"))
    }

    @Test
    fun exporterGroupsNodesIntoReadableDirectionSubgraphsWhenLayoutMetadataExists() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "caller",
                    type = NodeType.METHOD,
                    title = "OrderFacade.submit",
                    signature = "com.example.OrderFacade.submit():void",
                    metadata = mapOf(
                        "layout.direction" to "UPSTREAM",
                        "layout.depth" to "1",
                    ),
                ),
                GraphNode(
                    id = "anchor",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place():void",
                    metadata = mapOf(
                        "layout.direction" to "CURRENT",
                        "layout.depth" to "0",
                    ),
                ),
                GraphNode(
                    id = "callee",
                    type = NodeType.SQL,
                    title = "insert into orders",
                    metadata = mapOf(
                        "layout.direction" to "DOWNSTREAM",
                        "layout.depth" to "1",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge-caller",
                    type = EdgeType.CALL,
                    fromNodeId = "caller",
                    toNodeId = "anchor",
                ),
                GraphEdge(
                    id = "edge-callee",
                    type = EdgeType.MAPS_TO_SQL,
                    fromNodeId = "anchor",
                    toNodeId = "callee",
                ),
            ),
        )

        val exported = MermaidExporter().export(document)

        assertTrue(exported.contains("subgraph 上游"))
        assertTrue(exported.contains("subgraph 当前"))
        assertTrue(exported.contains("subgraph 下游"))
        assertTrue(exported.contains("\nend\n"))
        assertTrue(exported.contains("N1"))
        assertTrue(exported.contains("N2"))
        assertTrue(exported.contains("N3"))
    }

    @Test
    fun exporterPreservesFlowScopeMetadataAndContainmentEdgesInComments() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-process",
                    type = NodeType.METHOD,
                    title = "OrderService.process",
                    signature = "com.example.OrderService.process():void",
                ),
                GraphNode(
                    id = "flow:if-line-active",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (line.isActive())",
                    signature = "branch body · Line",
                    doc = "仅处理激活行。",
                    metadata = mapOf(
                        "flow.kind" to "IF",
                        "layout.direction" to "DOWNSTREAM",
                        "layout.depth" to "1",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "contains:process->if",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = "method:order-service-process",
                    toNodeId = "flow:if-line-active",
                    metadata = mapOf("callOrder" to "1"),
                ),
            ),
        )

        val exported = MermaidExporter().export(document)

        assertTrue(exported.contains("nodeType=FLOW_SCOPE"))
        assertTrue(exported.contains("flow.kind=IF"))
        assertTrue(exported.contains("edgeType=CONTAINS_FLOW"))
        assertTrue(exported.contains("callOrder=1"))
        assertTrue(exported.contains("流程作用域"))
    }
}
