package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvocationExpansionTargetGraphBuilderTest {
    @Test
    fun buildReturnsUnprojectedFlowchartGraphForExpansionMerge() {
        val graph = InvocationExpansionTargetGraphBuilder().build(expansionAnalysisResult())

        assertEquals(
            listOf("action:save", "invoke:audit", "method:create-info"),
            graph.nodes.map { node -> node.id }.sorted(),
        )
        assertTrue(graph.nodes.any { node -> node.id == "invoke:audit" && node.type == NodeType.FLOW_ACTION })
    }

    private fun expansionAnalysisResult(): SemanticAnalysisResult =
        SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "subject:create-info",
                sourcePath = "src/SystemService.java",
                sourceRange = SourceRange(0, 10),
                displayName = "SystemService.createInfo",
                kind = ResourceSubjectKind.XML_RESOURCE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor:create-info", targetUnitId = "method:create-info")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:create-info",
                    title = "SystemService.createInfo",
                    signature = "com.example.SystemService.createInfo():void",
                ),
                FlowActionUnit(
                    id = "action:save",
                    title = "saveInfo()",
                    actionKind = "ACTION",
                ),
                InvocationUnit(
                    id = "invoke:audit",
                    title = "auditInfo()",
                    targetSignature = "com.example.SystemService.auditInfo():void",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:create-info", "action:save"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:save", "invoke:audit"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )
}
