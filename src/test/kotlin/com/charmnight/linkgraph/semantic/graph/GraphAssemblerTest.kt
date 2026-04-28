package com.charmnight.linkgraph.semantic.graph

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowEdgeRole
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MergeUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphAssemblerTest {
    @Test
    fun factsAndFlowchartShouldProjectFromSameSemanticTruth() {
        val result = sampleAnalysisResult()
        val assembler = GraphAssembler()
        val invocationId = result.semanticUnits.filterIsInstance<InvocationUnit>().single().id

        val factGraph = assembler.assemble(result, AnalysisDisplayMode.FACT_GRAPH)
        val flowchart = assembler.assemble(result, AnalysisDisplayMode.FLOWCHART)
        val resourceView = assembler.assemble(result, AnalysisDisplayMode.RESOURCE_RELATION_VIEW)

        assertTrue(factGraph.nodes.any { node -> node.type == NodeType.METHOD && node.title == "OrderService.submit" })
        assertTrue(factGraph.nodes.any { node -> node.type == NodeType.DOC_PAGE })
        assertTrue(factGraph.edges.any { edge -> edge.type == EdgeType.CALL })

        assertTrue(flowchart.nodes.any { node -> node.type == NodeType.TERMINAL })
        assertTrue(flowchart.nodes.any { node -> node.type == NodeType.MERGE })
        assertTrue(flowchart.edges.any { edge -> edge.type == EdgeType.CONTROL_FLOW })
        assertTrue(flowchart.nodes.any { node -> node.id == invocationId })
        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == "action:validate" &&
                    edge.toNodeId == invocationId
            },
        )
        assertTrue(flowchart.nodes.none { node -> node.id == "resource:doc" })
        assertTrue(flowchart.nodes.none { node -> node.id == "method:order-repository-save" })
        assertTrue(flowchart.edges.none { edge -> edge.type == EdgeType.CALL })

        assertEquals(2, resourceView.nodes.size)
        assertTrue(resourceView.edges.any { edge -> edge.type == EdgeType.LINKS_DOC })
        assertTrue(resourceView.nodes.none { node -> node.type == NodeType.FLOW_ACTION })
        assertTrue(resourceView.edges.none { edge -> edge.type == EdgeType.CONTROL_FLOW })
    }

    @Test
    fun factGraphShouldCarryMethodDocIntoProjectedNode() {
        val sample = sampleAnalysisResult()
        val methodId = sample.anchors.single().targetUnitId
        val analysisResult = sample.copy(
            semanticUnits = sample.semanticUnits.map { unit ->
                if (unit is MethodLikeUnit && unit.id == methodId) {
                    unit.copy(doc = "提交订单入口，负责参数校验与下游编排。")
                } else {
                    unit
                }
            },
        )

        val factGraph = GraphAssembler().assemble(analysisResult, AnalysisDisplayMode.FACT_GRAPH)

        assertEquals(
            "提交订单入口，负责参数校验与下游编排。",
            factGraph.nodes.first { node -> node.id == methodId }.doc,
        )
    }

    @Test
    fun flowchartShouldRespectExplicitControlFlowEdges() {
        val entryMethod = MethodLikeUnit(
            id = "method:file-download",
            title = "CommonController.fileDownload",
            signature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,boolean):void",
        )
        val guardScope = com.charmnight.linkgraph.semantic.model.FlowScopeUnit(
            id = "scope:guard",
            title = "if (!FileUtils.checkAllowDownload(fileName))",
            scopeKind = "IF",
        )
        val guardThrow = FlowActionUnit(
            id = "action:guard-throw",
            title = "throw new Exception(...)",
            actionKind = "ACTION",
        )
        val buildRealName = FlowActionUnit(
            id = "action:real-name",
            title = "String realFileName = System.currentTimeMillis() + fileName.substring(...)",
            actionKind = "ACTION",
        )
        val buildFilePath = FlowActionUnit(
            id = "action:file-path",
            title = "String filePath = RuoYiConfig.getDownloadPath() + fileName",
            actionKind = "ACTION",
        )
        val writeResponse = FlowActionUnit(
            id = "action:write-response",
            title = "FileUtils.writeBytes(filePath, response.getOutputStream())",
            actionKind = "ACTION",
        )
        val result = SemanticAnalysisResult(
            subject = sampleAnalysisResult().subject,
            anchors = listOf(SemanticAnchor(id = "anchor-download", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(
                entryMethod,
                guardScope,
                guardThrow,
                buildRealName,
                buildFilePath,
                writeResponse,
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, entryMethod.id, guardScope.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, guardScope.id, guardThrow.id, "TRUE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, guardScope.id, buildRealName.id, "FALSE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, buildRealName.id, buildFilePath.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, buildFilePath.id, writeResponse.id),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val flowchart = GraphAssembler().assemble(result, AnalysisDisplayMode.FLOWCHART)

        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == entryMethod.id &&
                    edge.toNodeId == guardScope.id
            },
        )
        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == guardScope.id &&
                    edge.toNodeId == buildRealName.id &&
                    edge.label == "FALSE"
                },
        )
        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == buildRealName.id &&
                    edge.toNodeId == buildFilePath.id
            },
        )
        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == buildFilePath.id &&
                    edge.toNodeId == writeResponse.id
            },
        )
        assertTrue(
            flowchart.edges.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == guardScope.id &&
                    edge.toNodeId == guardThrow.id &&
                    edge.label == "TRUE"
                },
        )
        assertTrue(
            flowchart.edges.none { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == entryMethod.id &&
                    edge.toNodeId == buildFilePath.id
            },
        )
        assertTrue(
            flowchart.edges.none { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == entryMethod.id &&
                    edge.toNodeId == writeResponse.id
            },
        )
        assertEquals(
            "TERMINAL",
            flowchart.nodes.first { node -> node.id == guardThrow.id }.metadata["flowchart.kind"],
        )
    }

    @Test
    fun flowchartProjectionShouldCarryOwnerMethodAndSourceMetadata() {
        val subject = sampleAnalysisResult().subject
        val entryMethod = MethodLikeUnit(
            id = "method:merge-sample-build",
            title = "MergeSample.build",
            signature = "com.example.MergeSample.build(boolean):void",
        )
        val branchAction = FlowActionUnit(
            id = "action:assign-base",
            title = "base = 1",
            actionKind = "ACTION",
        )
        val mergeUnit = MergeUnit(
            id = "merge:after-if",
            title = "汇合",
        )
        val postBranchAction = FlowActionUnit(
            id = "action:normalize-base",
            title = "normalize(base)",
            actionKind = "ACTION",
        )
        val analysisResult = SemanticAnalysisResult(
            subject = subject,
            anchors = listOf(SemanticAnchor(id = "anchor-build", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(
                entryMethod,
                branchAction,
                mergeUnit,
                postBranchAction,
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTAINS, entryMethod.id, branchAction.id),
                SemanticRelation(SemanticRelationKind.CONTAINS, entryMethod.id, mergeUnit.id),
                SemanticRelation(SemanticRelationKind.CONTAINS, entryMethod.id, postBranchAction.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, entryMethod.id, branchAction.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, branchAction.id, mergeUnit.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, mergeUnit.id, postBranchAction.id),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = listOf(
                com.charmnight.linkgraph.semantic.model.SourceMapping(
                    sourcePath = "src/main/java/com/example/MergeSample.java",
                    sourceRange = SourceRange(
                        startOffset = 120,
                        endOffset = 146,
                        startLine = 8,
                        endLine = 8,
                    ),
                    targetUnitId = postBranchAction.id,
                ),
            ),
        )

        val flowchart = GraphAssembler().assemble(analysisResult, AnalysisDisplayMode.FLOWCHART)
        val projectedNode = flowchart.nodes.first { node -> node.id == postBranchAction.id }

        assertEquals(
            "com.example.MergeSample.build(boolean):void",
            projectedNode.metadata["flow.ownerMethod"],
        )
        assertEquals(
            "com.example.MergeSample.build(boolean):void",
            projectedNode.metadata["flow.anchorMethod"],
        )
        assertEquals(
            "src/main/java/com/example/MergeSample.java",
            projectedNode.metadata["source.filePath"],
        )
        assertEquals("120", projectedNode.metadata["source.startOffset"])
        assertEquals("146", projectedNode.metadata["source.endOffset"])
    }

    @Test
    fun flowchartShouldPreserveSemanticNodeAndEdgeOrderForElkModelOrder() {
        val subject = sampleAnalysisResult().subject
        val entryMethod = MethodLikeUnit(
            id = "method:z-entry",
            title = "OrderService.submit",
            signature = "com.example.OrderService.submit():void",
        )
        val decision = com.charmnight.linkgraph.semantic.model.FlowScopeUnit(
            id = "scope:z-if",
            title = "if (valid)",
            scopeKind = "IF",
        )
        val trueBranch = FlowActionUnit(
            id = "action:z-true",
            title = "persist()",
            actionKind = "ACTION",
        )
        val falseBranch = FlowActionUnit(
            id = "action:a-false",
            title = "reject()",
            actionKind = "ACTION",
        )
        val merge = MergeUnit(
            id = "merge:z-join",
            title = "汇合",
        )
        val result = SemanticAnalysisResult(
            subject = subject,
            anchors = listOf(SemanticAnchor(id = "anchor-order", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(
                entryMethod,
                decision,
                trueBranch,
                falseBranch,
                merge,
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, entryMethod.id, decision.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, decision.id, trueBranch.id, "TRUE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, decision.id, falseBranch.id, "FALSE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, trueBranch.id, merge.id, "TRUE"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, falseBranch.id, merge.id, "FALSE"),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val flowchart = GraphAssembler().assemble(result, AnalysisDisplayMode.FLOWCHART)

        assertEquals(
            listOf(
                entryMethod.id,
                decision.id,
                trueBranch.id,
                falseBranch.id,
                merge.id,
            ),
            flowchart.nodes.map { node -> node.id },
        )
        assertEquals(
            listOf(
                "${entryMethod.id}->${decision.id}:",
                "${decision.id}->${trueBranch.id}:TRUE",
                "${decision.id}->${falseBranch.id}:FALSE",
                "${trueBranch.id}->${merge.id}:TRUE",
                "${falseBranch.id}->${merge.id}:FALSE",
            ),
            flowchart.edges.map { edge -> "${edge.fromNodeId}->${edge.toNodeId}:${edge.label ?: ""}" },
        )
    }

    @Test
    fun flowchartShouldProjectLoopScopesAsDecisionNodes() {
        val subject = sampleAnalysisResult().subject
        val entryMethod = MethodLikeUnit(
            id = "method:loop-entry",
            title = "LoopSample.render",
            signature = "com.example.LoopSample.render():void",
        )
        val foreachScope = com.charmnight.linkgraph.semantic.model.FlowScopeUnit(
            id = "scope:foreach",
            title = "for (item : items)",
            scopeKind = "FOREACH",
        )
        val bodyAction = FlowActionUnit(
            id = "action:body",
            title = "process(item)",
            actionKind = "ACTION",
        )
        val afterAction = FlowActionUnit(
            id = "action:after",
            title = "finish()",
            actionKind = "ACTION",
        )
        val result = SemanticAnalysisResult(
            subject = subject,
            anchors = listOf(SemanticAnchor(id = "anchor-loop", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(entryMethod, foreachScope, bodyAction, afterAction),
            relations = listOf(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = entryMethod.id,
                    toUnitId = foreachScope.id,
                    flowEdgeRole = FlowEdgeRole.ENTRY,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = foreachScope.id,
                    toUnitId = bodyAction.id,
                    label = "TRUE",
                    flowEdgeRole = FlowEdgeRole.LOOP_BODY,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = bodyAction.id,
                    toUnitId = foreachScope.id,
                    label = "LOOP_BACK",
                    flowEdgeRole = FlowEdgeRole.LOOP_BACK,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = foreachScope.id,
                    toUnitId = afterAction.id,
                    label = "FALSE",
                    flowEdgeRole = FlowEdgeRole.LOOP_EXIT,
                ),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val flowchart = GraphAssembler().assemble(result, AnalysisDisplayMode.FLOWCHART)
        val projectedLoop = flowchart.nodes.first { node -> node.id == foreachScope.id }
        val loopBodyEdge = flowchart.edges.first { edge -> edge.fromNodeId == foreachScope.id && edge.toNodeId == bodyAction.id }
        val loopExitEdge = flowchart.edges.first { edge -> edge.fromNodeId == foreachScope.id && edge.toNodeId == afterAction.id }
        val loopBackEdge = flowchart.edges.first { edge -> edge.fromNodeId == bodyAction.id && edge.toNodeId == foreachScope.id }

        assertEquals("DECISION", projectedLoop.metadata["flowchart.kind"])
        assertEquals("LOOP_PRE_TEST", projectedLoop.metadata["flow.scopeCategory"])
        assertEquals("FOREACH", projectedLoop.metadata["flow.scopeKind"])
        assertEquals(FlowScopeCategory.LOOP_PRE_TEST.name, projectedLoop.metadata["flow.scopeCategory"])
        assertEquals(FlowEdgeRole.LOOP_BODY.name, loopBodyEdge.metadata["flow.edgeRole"])
        assertEquals(FlowEdgeRole.LOOP_EXIT.name, loopExitEdge.metadata["flow.edgeRole"])
        assertEquals(FlowEdgeRole.LOOP_BACK.name, loopBackEdge.metadata["flow.edgeRole"])
    }

    @Test
    fun flowchartShouldProjectPostTestLoopsWithExplicitScopeCategoryAndEdgeRoles() {
        val subject = sampleAnalysisResult().subject
        val entryMethod = MethodLikeUnit(
            id = "method:post-loop-entry",
            title = "LoopSample.flush",
            signature = "com.example.LoopSample.flush():void",
        )
        val doWhileScope = com.charmnight.linkgraph.semantic.model.FlowScopeUnit(
            id = "scope:do-while",
            title = "do-while (pending())",
            scopeKind = "DO_WHILE",
            scopeCategory = FlowScopeCategory.LOOP_POST_TEST,
        )
        val bodyAction = FlowActionUnit(
            id = "action:drain",
            title = "drain()",
            actionKind = "ACTION",
        )
        val afterAction = FlowActionUnit(
            id = "action:complete",
            title = "complete()",
            actionKind = "ACTION",
        )
        val result = SemanticAnalysisResult(
            subject = subject,
            anchors = listOf(SemanticAnchor(id = "anchor-post-loop", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(entryMethod, doWhileScope, bodyAction, afterAction),
            relations = listOf(
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = entryMethod.id,
                    toUnitId = bodyAction.id,
                    flowEdgeRole = FlowEdgeRole.ENTRY,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = bodyAction.id,
                    toUnitId = doWhileScope.id,
                    flowEdgeRole = FlowEdgeRole.NORMAL,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = doWhileScope.id,
                    toUnitId = bodyAction.id,
                    label = "TRUE",
                    flowEdgeRole = FlowEdgeRole.LOOP_BACK,
                ),
                SemanticRelation(
                    kind = SemanticRelationKind.CONTROL_FLOW,
                    fromUnitId = doWhileScope.id,
                    toUnitId = afterAction.id,
                    label = "FALSE",
                    flowEdgeRole = FlowEdgeRole.LOOP_EXIT,
                ),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val flowchart = GraphAssembler().assemble(result, AnalysisDisplayMode.FLOWCHART)
        val projectedLoop = flowchart.nodes.first { node -> node.id == doWhileScope.id }
        val loopBackEdge = flowchart.edges.first { edge -> edge.fromNodeId == doWhileScope.id && edge.toNodeId == bodyAction.id }
        val loopExitEdge = flowchart.edges.first { edge -> edge.fromNodeId == doWhileScope.id && edge.toNodeId == afterAction.id }

        assertEquals(FlowScopeCategory.LOOP_POST_TEST.name, projectedLoop.metadata["flow.scopeCategory"])
        assertEquals(FlowEdgeRole.LOOP_BACK.name, loopBackEdge.metadata["flow.edgeRole"])
        assertEquals(FlowEdgeRole.LOOP_EXIT.name, loopExitEdge.metadata["flow.edgeRole"])
    }

    private fun sampleAnalysisResult(): SemanticAnalysisResult {
        val subject = ResourceSubjectHandle(
            subjectId = "resource-markdown:order-flow-md",
            sourcePath = "docs/order-flow.md",
            sourceRange = SourceRange(startOffset = 0, endOffset = 20, startLine = 1, endLine = 1),
            displayName = "order-flow.md",
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
        )
        val entryMethod = MethodLikeUnit(
            id = "method:order-service-submit",
            title = "OrderService.submit",
            signature = "com.example.OrderService.submit(java.lang.String):void",
        )
        val invokeSave = InvocationUnit(
            id = "invoke:save",
            title = "调用 OrderRepository.save",
            targetSignature = "com.example.OrderRepository.save(com.example.Order):void",
        )
        val saveMethod = MethodLikeUnit(
            id = "method:order-repository-save",
            title = "OrderRepository.save",
            signature = "com.example.OrderRepository.save(com.example.Order):void",
        )
        val docUnit = ResourceUnit(
            id = "resource:doc",
            title = "order-flow.md",
            resourceKind = "MARKDOWN_PAGE",
        )
        return SemanticAnalysisResult(
            subject = subject,
            anchors = listOf(SemanticAnchor(id = "anchor-submit", targetUnitId = entryMethod.id, label = "入口")),
            semanticUnits = listOf(
                entryMethod,
                FlowActionUnit(id = "action:validate", title = "校验订单", actionKind = "ACTION"),
                invokeSave,
                saveMethod,
                TerminalUnit(id = "terminal:return", title = "返回", terminalKind = "RETURN"),
                MergeUnit(id = "merge:join", title = "汇合"),
                docUnit,
            ),
            relations = listOf(
                SemanticRelation(SemanticRelationKind.CONTAINS, entryMethod.id, "action:validate"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, entryMethod.id, "action:validate"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:validate", invokeSave.id),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, invokeSave.id, "merge:join"),
                SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "merge:join", "terminal:return"),
                SemanticRelation(SemanticRelationKind.INVOKES, invokeSave.id, saveMethod.id),
                SemanticRelation(SemanticRelationKind.DOCUMENTS, docUnit.id, entryMethod.id),
            ),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )
    }
}
