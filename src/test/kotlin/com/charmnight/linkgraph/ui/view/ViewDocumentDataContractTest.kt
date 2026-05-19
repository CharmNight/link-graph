package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.testing.assertFactGraphViewDataContract
import com.charmnight.linkgraph.testing.assertFlowchartViewDataContract
import com.charmnight.linkgraph.testing.assertResourceRelationViewDataContract
import kotlin.test.Test
import kotlin.test.assertTrue

class ViewDocumentDataContractTest {
    @Test
    fun projectorsReturnSelfContainedViewDocumentsWithResolvableData() {
        val analysisResult = semanticAnalysisResult()

        val factView = FactGraphProjector().project(analysisResult)
        val flowchartView = FlowchartProjector().project(
            analysisResult = analysisResult,
            projectionPolicy = ProjectionPolicy(maxVisibleNodes = 8, maxVisibleEdges = 8),
        )
        val resourceRelationView = ResourceRelationProjector().project(analysisResult)

        assertFactGraphViewDataContract(factView, "projector.fact")
        assertFlowchartViewDataContract(flowchartView, "projector.flowchart")
        assertResourceRelationViewDataContract(resourceRelationView, "projector.resource")
        assertTrue(
            flowchartView.projectionIndex.nodeMappings.values.any { mapping ->
                mapping.canonicalNodeIds.contains("invoke:validate-customer")
            },
            "Readable flowchart projection must map projected nodes back to collapsed invocation symbols.",
        )
        assertTrue(
            resourceRelationView.visibleGraph.edges.any { edge -> edge.toNodeId == "sql:orders-insert" },
            "Resource relation view must expose real resource relationships, not just an empty operation shell.",
        )
    }

    private fun semanticAnalysisResult(): SemanticAnalysisResult =
        SemanticAnalysisResult(
            subject = ResourceSubjectHandle(
                subjectId = "resource-doc:orders",
                sourcePath = "docs/orders.md",
                sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
                displayName = "orders.md",
                kind = ResourceSubjectKind.MARKDOWN_PAGE,
            ),
            anchors = listOf(SemanticAnchor(id = "anchor-submit", targetUnitId = "method:submit", label = "submit")),
            semanticUnits = listOf(
                MethodLikeUnit(
                    id = "method:submit",
                    title = "OrderService.submit",
                    signature = "com.example.OrderService.submit():void",
                ),
                FlowActionUnit(
                    id = "action:guard-condition",
                    title = "customer == null",
                    actionKind = "CONDITION",
                ),
                InvocationUnit(
                    id = "invoke:validate-customer",
                    title = "调用 CustomerValidator.validate",
                    targetSignature = "com.example.CustomerValidator.validate():void",
                ),
                FlowScopeUnit(
                    id = "scope:customer-guard",
                    title = "if (customer == null)",
                    scopeKind = "IF",
                    scopeCategory = FlowScopeCategory.BRANCH,
                ),
                FlowActionUnit(
                    id = "action:insert-order",
                    title = "orderMapper.insert(order)",
                    actionKind = "ACTION",
                ),
                ResourceUnit(
                    id = "sql:orders-insert",
                    title = "OrderMapper.insert",
                    resourceKind = "MYBATIS_STATEMENT",
                    metadata = mapOf("resource.name" to "OrderMapper.insert"),
                ),
                TerminalUnit(
                    id = "terminal:return",
                    title = "返回",
                    terminalKind = "RETURN",
                ),
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTAINS, "method:submit", "action:guard-condition"),
                SemanticRelation(SemanticRelationKind.CONTAINS, "method:submit", "invoke:validate-customer"),
                SemanticRelation(SemanticRelationKind.CONTAINS, "method:submit", "scope:customer-guard"),
                SemanticRelation(SemanticRelationKind.CONTAINS, "method:submit", "action:insert-order"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:submit", "action:guard-condition"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:guard-condition", "invoke:validate-customer"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "invoke:validate-customer", "scope:customer-guard"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "scope:customer-guard", "action:insert-order", label = "FALSE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:insert-order", "terminal:return"),
                SemanticRelation(SemanticRelationKind.BINDS_TO, "method:submit", "sql:orders-insert", label = "MYBATIS_STATEMENT"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = listOf(
                SourceMapping(
                    sourcePath = "src/main/java/com/example/OrderService.java",
                    sourceRange = SourceRange(startOffset = 0, endOffset = 120, startLine = 3, endLine = 12),
                    targetUnitId = "method:submit",
                ),
                SourceMapping(
                    sourcePath = "src/main/java/com/example/OrderService.java",
                    sourceRange = SourceRange(startOffset = 40, endOffset = 70, startLine = 5, endLine = 5),
                    targetUnitId = "action:insert-order",
                ),
                SourceMapping(
                    sourcePath = "src/main/resources/mapper/OrderMapper.xml",
                    sourceRange = SourceRange(startOffset = 0, endOffset = 80, startLine = 4, endLine = 8),
                    targetUnitId = "sql:orders-insert",
                ),
            ),
        )
}
