package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidValidatorTest {
    @Test
    fun detectsDuplicateNodeIdAsStructureIssue() {
        val mermaid = """
            graph TD
            D1["CLASS|OrderController"]
            D1["METHOD|OrderService.place(java.lang.String):void|signature=OrderService.place(java.lang.String):void"]
            D1 -- CALL --> D1
        """.trimIndent()

        val parseResult = MermaidImporter().import(mermaid)
        val issues = MermaidValidator().validate(parseResult.document, parseResult.issues)

        assertTrue(issues.any { it.category == MermaidIssue.Category.STRUCTURE && it.code == "duplicate-node-id" })
    }

    @Test
    fun reportsSemanticIssuesForMissingMethodSignatureHttpPathAndMqTopic() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(id = "M1", type = NodeType.METHOD, title = "OrderService.place"),
                GraphNode(id = "H1", type = NodeType.HTTP_ENDPOINT, title = "POST /api/orders"),
                GraphNode(id = "Q1", type = NodeType.MQ_TOPIC, title = "order.created"),
            ),
        )

        val issues = MermaidValidator().validate(document)

        assertTrue(issues.any { it.category == MermaidIssue.Category.SEMANTIC && it.code == "missing-method-signature" && it.nodeId == "M1" })
        assertTrue(issues.any { it.category == MermaidIssue.Category.SEMANTIC && it.code == "missing-http-path" && it.nodeId == "H1" })
        assertTrue(issues.any { it.category == MermaidIssue.Category.SEMANTIC && it.code == "missing-mq-topic" && it.nodeId == "Q1" })
    }

    @Test
    fun reportsBindingPlaceholderIssuesForUnmatchedAndMultiCandidate() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "M1",
                    type = NodeType.METHOD,
                    title = "OrderService.place(java.lang.String):void",
                    signature = "OrderService.place(java.lang.String):void",
                    metadata = mapOf("binding" to "UNMATCHED"),
                ),
                GraphNode(
                    id = "M2",
                    type = NodeType.METHOD,
                    title = "OrderService.cancel(java.lang.String):void",
                    signature = "OrderService.cancel(java.lang.String):void",
                    metadata = mapOf("binding" to "MULTI_CANDIDATE"),
                ),
                GraphNode(
                    id = "M3",
                    type = NodeType.METHOD,
                    title = "OrderService.refund(java.lang.String):void",
                    signature = "OrderService.refund(java.lang.String):void",
                    metadata = mapOf("binding" to "CONFLICTED"),
                ),
            ),
        )

        val issues = MermaidValidator().validate(document)
        val bindingIssues = issues.filter { it.category == MermaidIssue.Category.BINDING }

        assertEquals(3, bindingIssues.size)
        assertTrue(bindingIssues.any { it.code == "binding-unmatched" && it.nodeId == "M1" })
        assertTrue(bindingIssues.any { it.code == "binding-multi-candidate" && it.nodeId == "M2" })
        assertTrue(bindingIssues.any { it.code == "binding-conflicted" && it.nodeId == "M3" })
    }
}
