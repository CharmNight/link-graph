package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.agent.model.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.review.ReviewGraphSummary
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanItem
import com.charmnight.linkgraph.agent.model.GenerationPlanSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanningContextFactoryTest {
    @Test
    fun buildGraphBeautificationContextUsesArchitectureGraphFullGraph() {
        val projectDir = Files.createTempDirectory("architecture-beautification-source")
        val sourceFile = projectDir.resolve("src/main/java/com/example/orders/OrderService.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
                package com.example.orders;

                public class OrderService {
                    public void submit() {
                        validateOrder();
                    }
                }
            """.trimIndent(),
        )
        val staleMethod = GraphNode(
            id = "method:legacy-flow",
            type = NodeType.METHOD,
            title = "LegacyFlow.run",
        )
        val moduleNode = GraphNode(
            id = "module:app",
            type = NodeType.MODULE,
            title = "app",
            metadata = mapOf("architecture.kind" to "MODULE"),
        )
        val packageNode = GraphNode(
            id = "package:orders",
            type = NodeType.PACKAGE,
            title = "com.example.orders",
            metadata = mapOf(
                "architecture.kind" to "PACKAGE",
                "architecture.sourceSample.count" to "1",
                "architecture.sourceSample.0.nodeId" to "class:com.example.orders.OrderService",
                "architecture.sourceSample.0.filePath" to sourceFile.toString(),
                "architecture.sourceSample.0.startLine" to "1",
                "architecture.sourceSample.0.endLine" to "7",
                "architecture.sourceSample.0.reason" to "architecture-member-class:package:orders",
            ),
        )
        val resourceNode = GraphNode(
            id = "resource:orders-db",
            type = NodeType.RESOURCE,
            title = "orders-db",
            metadata = mapOf("architecture.kind" to "RESOURCE"),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(moduleNode, packageNode),
            edges = listOf(
                GraphEdge(
                    id = "contains:app-orders",
                    type = EdgeType.USES_TYPE,
                    fromNodeId = moduleNode.id,
                    toNodeId = packageNode.id,
                    metadata = mapOf("architecture.relation.kind" to "CONTAINS"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = visibleGraph.nodes + resourceNode,
            edges = visibleGraph.edges + GraphEdge(
                id = "uses:orders-db",
                type = EdgeType.MAPS_TO_SQL,
                fromNodeId = packageNode.id,
                toNodeId = resourceNode.id,
                metadata = mapOf("architecture.relation.kind" to "USES_RESOURCE"),
            ),
        )
        val stateService = GraphEditorStateService()
        stateService.loadGraph(GraphDocument(nodes = listOf(staleMethod)), "currentMethod")
        stateService.loadArchitectureGraphView(
            ArchitectureGraphResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = moduleNode.id,
            ),
        )

        val context = planningContextFactory().buildGraphBeautificationContext(
            snapshot = stateService.snapshot().toWorkflowEditorSnapshot(),
            goal = "解释架构关系",
            preferredStyle = null,
            explanationFocus = "架构图",
            focusNodeId = packageNode.id,
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(packageNode.id, context.presentationContext.anchorNodeId)
        assertEquals(listOf(packageNode.id), context.presentationContext.selectedNodeIds)
        assertEquals(
            setOf(moduleNode.id, packageNode.id),
            context.presentationContext.graph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(moduleNode.id, packageNode.id, resourceNode.id),
            context.presentationContext.fullGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals("class:com.example.orders.OrderService", context.sourceContext.single().nodeId)
        assertTrue(context.sourceContext.single().snippet?.contains("validateOrder") == true)
        assertTrue(context.presentationContext.fullGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildQaGraphsUsesArchitectureGraphViewForFactAndEditableGraphs() {
        val staleMethod = GraphNode(
            id = "method:stale-flow",
            type = NodeType.METHOD,
            title = "StaleFlow.run",
        )
        val layerNode = GraphNode(
            id = "layer:application",
            type = NodeType.LAYER,
            title = "application",
            metadata = mapOf("architecture.kind" to "LAYER"),
        )
        val serviceNode = GraphNode(
            id = "service:order-service",
            type = NodeType.SERVICE,
            title = "OrderService",
            metadata = mapOf("architecture.kind" to "SERVICE"),
        )
        val packageNode = GraphNode(
            id = "package:orders",
            type = NodeType.PACKAGE,
            title = "com.example.orders",
            metadata = mapOf("architecture.kind" to "PACKAGE"),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(layerNode, serviceNode),
            edges = listOf(
                GraphEdge(
                    id = "contains:layer-service",
                    type = EdgeType.USES_TYPE,
                    fromNodeId = layerNode.id,
                    toNodeId = serviceNode.id,
                    metadata = mapOf("architecture.relation.kind" to "CONTAINS"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = listOf(layerNode, serviceNode, packageNode),
            edges = visibleGraph.edges + GraphEdge(
                id = "contains:service-package",
                type = EdgeType.USES_TYPE,
                fromNodeId = serviceNode.id,
                toNodeId = packageNode.id,
                metadata = mapOf("architecture.relation.kind" to "CONTAINS"),
            ),
        )
        val stateService = GraphEditorStateService()
        stateService.loadGraph(GraphDocument(nodes = listOf(staleMethod)), "currentMethod")
        stateService.loadArchitectureGraphView(
            ArchitectureGraphResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = layerNode.id,
            ),
        )

        val qaGraphs = planningContextFactory().buildQaGraphs(
            snapshot = stateService.snapshot().toWorkflowEditorSnapshot(),
            selectedNodeIds = listOf(serviceNode.id),
            collectSourceEvidence = false,
        )

        assertEquals(
            setOf(layerNode.id, serviceNode.id, packageNode.id),
            qaGraphs.factGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(layerNode.id, serviceNode.id),
            qaGraphs.editableGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(qaGraphs.factGraph.nodes.none { node -> node.id == staleMethod.id })
        assertTrue(qaGraphs.editableGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildGraphBeautificationContextUsesClassDiagramFullGraph() {
        val staleMethod = GraphNode(
            id = "method:legacy-flow",
            type = NodeType.METHOD,
            title = "LegacyFlow.run",
        )
        val controller = GraphNode(
            id = "class:order-controller",
            type = NodeType.CLASS,
            title = "OrderController",
            signature = "com.example.OrderController",
            doc = "订单入口控制器。",
            metadata = mapOf(
                "uml.kind" to "CLASS_DIAGRAM",
                "jvm.class.kind" to "CLASS",
                "uml.comment" to "订单入口控制器。",
            ),
        )
        val service = GraphNode(
            id = "interface:order-service",
            type = NodeType.INTERFACE,
            title = "OrderService",
            signature = "com.example.OrderService",
            metadata = mapOf(
                "uml.kind" to "CLASS_DIAGRAM",
                "jvm.class.kind" to "INTERFACE",
            ),
        )
        val statusEnum = GraphNode(
            id = "enum:order-status",
            type = NodeType.ENUM,
            title = "OrderStatus",
            signature = "com.example.OrderStatus",
            metadata = mapOf(
                "uml.kind" to "CLASS_DIAGRAM",
                "jvm.class.kind" to "ENUM",
            ),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(controller, service),
            edges = listOf(
                GraphEdge(
                    id = "uses:controller-service",
                    type = EdgeType.USES_TYPE,
                    fromNodeId = controller.id,
                    toNodeId = service.id,
                    metadata = mapOf("jvm.relation.kind" to "USES_TYPE"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = visibleGraph.nodes + statusEnum,
            edges = visibleGraph.edges + GraphEdge(
                id = "uses:controller-status",
                type = EdgeType.USES_TYPE,
                fromNodeId = controller.id,
                toNodeId = statusEnum.id,
                metadata = mapOf("jvm.relation.kind" to "USES_TYPE"),
            ),
        )
        val stateService = GraphEditorStateService()
        stateService.loadGraph(GraphDocument(nodes = listOf(staleMethod)), "currentMethod")
        stateService.loadClassDiagramView(
            ClassDiagramResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = controller.id,
            ),
        )

        val context = planningContextFactory().buildGraphBeautificationContext(
            snapshot = stateService.snapshot().toWorkflowEditorSnapshot(),
            goal = "解释类关系",
            preferredStyle = null,
            explanationFocus = "类图",
            focusNodeId = service.id,
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(service.id, context.presentationContext.anchorNodeId)
        assertEquals(listOf(service.id), context.presentationContext.selectedNodeIds)
        assertEquals(
            setOf(controller.id, service.id),
            context.presentationContext.graph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(controller.id, service.id, statusEnum.id),
            context.presentationContext.fullGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(context.presentationContext.fullGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildQaGraphsUsesClassDiagramViewForFactAndEditableGraphs() {
        val staleMethod = GraphNode(
            id = "method:stale-flow",
            type = NodeType.METHOD,
            title = "StaleFlow.run",
        )
        val baseClass = GraphNode(
            id = "class:base-handler",
            type = NodeType.CLASS,
            title = "BaseHandler",
            signature = "com.example.BaseHandler",
            metadata = mapOf("uml.kind" to "CLASS_DIAGRAM", "jvm.class.kind" to "CLASS"),
        )
        val handler = GraphNode(
            id = "class:order-handler",
            type = NodeType.CLASS,
            title = "OrderHandler",
            signature = "com.example.OrderHandler",
            metadata = mapOf("uml.kind" to "CLASS_DIAGRAM", "jvm.class.kind" to "CLASS"),
        )
        val service = GraphNode(
            id = "interface:order-service",
            type = NodeType.INTERFACE,
            title = "OrderService",
            signature = "com.example.OrderService",
            metadata = mapOf("uml.kind" to "CLASS_DIAGRAM", "jvm.class.kind" to "INTERFACE"),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(handler, service),
            edges = listOf(
                GraphEdge(
                    id = "implements:handler-service",
                    type = EdgeType.IMPLEMENTS,
                    fromNodeId = handler.id,
                    toNodeId = service.id,
                    metadata = mapOf("jvm.relation.kind" to "IMPLEMENTS"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = listOf(baseClass, handler, service),
            edges = visibleGraph.edges + GraphEdge(
                id = "extends:handler-base",
                type = EdgeType.EXTENDS,
                fromNodeId = handler.id,
                toNodeId = baseClass.id,
                metadata = mapOf("jvm.relation.kind" to "EXTENDS"),
            ),
        )
        val stateService = GraphEditorStateService()
        stateService.loadGraph(GraphDocument(nodes = listOf(staleMethod)), "currentMethod")
        stateService.loadClassDiagramView(
            ClassDiagramResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = handler.id,
            ),
        )

        val qaGraphs = planningContextFactory().buildQaGraphs(
            snapshot = stateService.snapshot().toWorkflowEditorSnapshot(),
            selectedNodeIds = listOf(handler.id),
            collectSourceEvidence = false,
        )

        assertEquals(
            setOf(baseClass.id, handler.id, service.id),
            qaGraphs.factGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(handler.id, service.id),
            qaGraphs.editableGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(qaGraphs.factGraph.nodes.none { node -> node.id == staleMethod.id })
        assertTrue(qaGraphs.editableGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildQaGraphsUsesReviewGraphViewForFactAndEditableGraphs() {
        val staleMethod = GraphNode(
            id = "method:stale-flow",
            type = NodeType.METHOD,
            title = "StaleFlow.run",
        )
        val changed = GraphNode(
            id = "class:order-service",
            type = NodeType.CLASS,
            title = "OrderService",
            signature = "com.example.review.OrderService",
            metadata = mapOf(
                "review.role" to "CHANGED",
                "source.filePath" to "src/main/java/com/example/review/OrderService.java",
                "source.startLine" to "3",
                "source.endLine" to "8",
            ),
        )
        val upstream = GraphNode(
            id = "class:order-controller",
            type = NodeType.CLASS,
            title = "OrderController",
            signature = "com.example.review.OrderController",
            metadata = mapOf("review.role" to "UPSTREAM"),
        )
        val downstream = GraphNode(
            id = "class:order-repository",
            type = NodeType.CLASS,
            title = "OrderRepository",
            signature = "com.example.review.OrderRepository",
            metadata = mapOf("review.role" to "DOWNSTREAM"),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(changed, upstream),
            edges = listOf(
                GraphEdge(
                    id = "review:upstream:${upstream.id}->${changed.id}",
                    type = EdgeType.USES_TYPE,
                    fromNodeId = upstream.id,
                    toNodeId = changed.id,
                    metadata = mapOf("review.edgeRole" to "UPSTREAM"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = listOf(changed, upstream, downstream),
            edges = visibleGraph.edges + GraphEdge(
                id = "review:downstream:${changed.id}->${downstream.id}",
                type = EdgeType.USES_TYPE,
                fromNodeId = changed.id,
                toNodeId = downstream.id,
                metadata = mapOf("review.edgeRole" to "DOWNSTREAM"),
            ),
        )
        val stateService = GraphEditorStateService()
        stateService.loadGraph(GraphDocument(nodes = listOf(staleMethod)), "currentMethod")
        stateService.loadReviewGraphView(
            ReviewGraphResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = changed.id,
                summary = ReviewGraphSummary(
                    changedSymbolCount = 1,
                    upstreamCount = 1,
                    downstreamCount = 1,
                ),
                projectionIndex = com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph(
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                ),
            ),
        )

        val qaGraphs = planningContextFactory().buildQaGraphs(
            snapshot = stateService.snapshot().toWorkflowEditorSnapshot(),
            selectedNodeIds = listOf(changed.id),
            collectSourceEvidence = false,
        )

        assertEquals(
            setOf(changed.id, upstream.id, downstream.id),
            qaGraphs.factGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(changed.id, upstream.id),
            qaGraphs.editableGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(qaGraphs.factGraph.nodes.none { node -> node.id == staleMethod.id })
        assertTrue(qaGraphs.editableGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildGraphBeautificationContextUsesReviewGraphFullGraph() {
        val staleMethod = GraphNode(
            id = "method:legacy-flow",
            type = NodeType.METHOD,
            title = "LegacyFlow.run",
        )
        val changed = GraphNode(
            id = "class:order-service",
            type = NodeType.CLASS,
            title = "OrderService",
            signature = "com.example.review.OrderService",
            metadata = mapOf("review.role" to "CHANGED"),
        )
        val upstream = GraphNode(
            id = "class:order-controller",
            type = NodeType.CLASS,
            title = "OrderController",
            signature = "com.example.review.OrderController",
            metadata = mapOf("review.role" to "UPSTREAM"),
        )
        val downstream = GraphNode(
            id = "class:order-repository",
            type = NodeType.CLASS,
            title = "OrderRepository",
            signature = "com.example.review.OrderRepository",
            metadata = mapOf("review.role" to "DOWNSTREAM"),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(changed, upstream),
            edges = listOf(
                GraphEdge(
                    id = "review:upstream:${upstream.id}->${changed.id}",
                    type = EdgeType.USES_TYPE,
                    fromNodeId = upstream.id,
                    toNodeId = changed.id,
                    metadata = mapOf("review.edgeRole" to "UPSTREAM"),
                ),
            ),
        )
        val fullGraph = visibleGraph.copy(
            nodes = listOf(changed, upstream, downstream),
            edges = visibleGraph.edges + GraphEdge(
                id = "review:downstream:${changed.id}->${downstream.id}",
                type = EdgeType.USES_TYPE,
                fromNodeId = changed.id,
                toNodeId = downstream.id,
                metadata = mapOf("review.edgeRole" to "DOWNSTREAM"),
            ),
        )
        val snapshot = testSnapshot(
            workspaceGraph = GraphDocument(nodes = listOf(staleMethod)),
            analysisDisplayMode = AnalysisDisplayMode.REVIEW_GRAPH,
            currentSceneId = com.charmnight.linkgraph.application.model.GraphSceneId.WORKSPACE_REVIEW_GRAPH,
            selectedNodeId = changed.id,
            reviewGraphView = ReviewGraphResult(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = changed.id,
                summary = ReviewGraphSummary(
                    changedSymbolCount = 1,
                    upstreamCount = 1,
                    downstreamCount = 1,
                ),
                projectionIndex = com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph(
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                ),
            ),
        )

        val context = planningContextFactory().buildGraphBeautificationContext(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            goal = "解释 Review Graph 影响范围",
            preferredStyle = null,
            explanationFocus = "Review Graph",
            focusNodeId = changed.id,
            followUp = null as GraphBeautificationFollowUpContext?,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(changed.id, context.presentationContext.anchorNodeId)
        assertEquals(listOf(changed.id), context.presentationContext.selectedNodeIds)
        assertEquals(
            setOf(changed.id, upstream.id),
            context.presentationContext.graph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf(changed.id, upstream.id, downstream.id),
            context.presentationContext.fullGraph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(context.presentationContext.fullGraph.nodes.none { node -> node.id == staleMethod.id })
    }

    @Test
    fun buildQaGraphsUsesInteractiveGraphWithExpandedInvocationContent() {
        val factGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                ),
            ),
        )
        val editableGraph = GraphDocument(
            nodes = factGraph.nodes + GraphNode(
                id = "scope:file-download-if",
                type = NodeType.FLOW_SCOPE,
                title = "if (delete)",
                metadata = mapOf(
                    "linkGraph.expansion.id" to "invocation:qa-expanded",
                    "linkGraph.expansion.sourceInvocationNodeId" to "method:file-download",
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:file-download-if",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:file-download-if",
                    metadata = mapOf(
                        "linkGraph.expansion.id" to "invocation:qa-expanded",
                        "linkGraph.expansion.sourceInvocationNodeId" to "method:file-download",
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = factGraph,
            referenceFactGraph = factGraph,
            workingGraph = editableGraph,
            workingGraphDirty = true,
        )

        val qaGraphs = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).buildQaGraphs(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            selectedNodeIds = emptyList(),
            collectSourceEvidence = false,
        )

        assertEquals(setOf("method:file-download"), qaGraphs.factGraph.nodes.map(GraphNode::id).toSet())
        assertEquals(editableGraph, qaGraphs.editableGraph)
    }

    @Test
    fun buildGraphBeautificationContextIncludesExpandedInvocationNodesFromWorkingGraph() {
        val callerSignature = "com.example.Caller.run():void"
        val targetSignature = "com.example.SystemService.createInfo():void"
        val expansionId = "invocation:expansion-1"
        val callerMethod = GraphNode(
            id = "method:caller",
            type = NodeType.METHOD,
            title = "Caller.run",
            signature = callerSignature,
            metadata = mapOf("flowchart.kind" to "ENTRY"),
        )
        val invocationNode = GraphNode(
            id = "invoke:create-info",
            type = NodeType.FLOW_ACTION,
            title = "systemService.createInfo()",
            signature = targetSignature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flow.ownerMethod" to callerSignature,
                "flowchart.kind" to "SUBROUTINE",
            ),
        )
        val expandedMethod = GraphNode(
            id = "method:create-info",
            type = NodeType.METHOD,
            title = "SystemService.createInfo",
            signature = targetSignature,
            metadata = mapOf(
                "flow.ownerMethod" to targetSignature,
                "flowchart.kind" to "ENTRY",
                "linkGraph.expansion.id" to expansionId,
                "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                "linkGraph.expansion.kind" to "INVOCATION",
            ),
        )
        val expandedAction = GraphNode(
            id = "action:save-info",
            type = NodeType.FLOW_ACTION,
            title = "saveInfo()",
            metadata = mapOf(
                "flow.kind" to "ACTION",
                "flow.ownerMethod" to targetSignature,
                "flowchart.kind" to "PROCESS",
                "linkGraph.expansion.id" to expansionId,
                "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                "linkGraph.expansion.kind" to "INVOCATION",
            ),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(callerMethod, invocationNode, expandedMethod, expandedAction),
            edges = listOf(
                GraphEdge(
                    id = "control:caller-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = callerMethod.id,
                    toNodeId = invocationNode.id,
                ),
                GraphEdge(
                    id = "call:invoke-to-expanded-method",
                    type = EdgeType.CALL,
                    fromNodeId = invocationNode.id,
                    toNodeId = expandedMethod.id,
                    metadata = mapOf("linkGraph.expansion.id" to expansionId),
                ),
                GraphEdge(
                    id = "control:expanded-method-to-save",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = expandedMethod.id,
                    toNodeId = expandedAction.id,
                    metadata = mapOf(
                        "linkGraph.expansion.id" to expansionId,
                        "linkGraph.expansion.sourceInvocationNodeId" to invocationNode.id,
                    ),
                ),
            ),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(callerMethod, invocationNode),
            edges = listOf(workingGraph.edges.first()),
        )
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = visibleGraph,
            workingGraph = workingGraph,
            selectedMethodSignature = callerSignature,
            selectedNodeId = callerMethod.id,
            workingGraphDirty = true,
        )

        val factory = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        )
        val context = factory.buildGraphBeautificationContext(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            goal = "解释展开后的调用链",
            preferredStyle = null,
            explanationFocus = null,
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(
            setOf(callerMethod.id, invocationNode.id, expandedMethod.id, expandedAction.id),
            context.presentationContext.graph.nodes.map(GraphNode::id).toSet(),
        )
        assertTrue(context.presentationContext.graph.edges.any { edge ->
            edge.fromNodeId == expandedMethod.id && edge.toNodeId == expandedAction.id
        })

        val focusedContext = factory.buildGraphBeautificationContext(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            goal = "解释展开后的调用链",
            preferredStyle = null,
            explanationFocus = null,
            focusNodeId = expandedMethod.id,
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(expandedMethod.id, focusedContext.presentationContext.anchorNodeId)
        assertEquals(listOf(expandedMethod.id), focusedContext.presentationContext.selectedNodeIds)
        assertTrue(focusedContext.presentationContext.graph.nodes.any { node -> node.id == expandedAction.id })
    }

    @Test
    fun buildGraphBeautificationContextKeepsAllStepSourceSnippetsBeyondPromptBudget() {
        val projectDir = Files.createTempDirectory("beautification-step-source-context")
        val sourceFile = projectDir.resolve("src/main/java/com/example/ExpandedService.java")
        Files.createDirectories(sourceFile.parent)
        val sourceLines = (1..15).map { index -> "step$index();" }
        Files.writeString(
            sourceFile,
            """
                package com.example;
                class ExpandedService {
                    void expanded() {
            ${sourceLines.joinToString("\n") { line -> "            $line" }}
                    }
                }
            """.trimIndent(),
        )
        val method = GraphNode(
            id = "method:expanded",
            type = NodeType.METHOD,
            title = "ExpandedService.expanded",
            signature = "com.example.ExpandedService.expanded():void",
            metadata = mapOf(
                "flowchart.kind" to "ENTRY",
                "source.filePath" to sourceFile.toString(),
                "source.startLine" to "3",
                "source.endLine" to "20",
            ),
        )
        val actionNodes = (1..15).map { index ->
            GraphNode(
                id = "action:step-$index",
                type = NodeType.FLOW_ACTION,
                title = "step$index()",
                metadata = mapOf(
                    "flow.kind" to "ACTION",
                    "flow.ownerMethod" to "com.example.ExpandedService.expanded():void",
                    "flowchart.kind" to "PROCESS",
                    "source.filePath" to sourceFile.toString(),
                    "source.startLine" to (index + 3).toString(),
                    "source.endLine" to (index + 3).toString(),
                ),
            )
        }
        val graph = GraphDocument(nodes = listOf(method) + actionNodes)
        val snapshot = testSnapshot(
            analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = graph,
            workingGraph = graph,
            selectedMethodSignature = "com.example.ExpandedService.expanded():void",
            selectedNodeId = "action:step-15",
            workingGraphDirty = true,
        )

        val context = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
            projectBasePathProvider = { projectDir.toString() },
        ).buildGraphBeautificationContext(
            snapshot = snapshot.toWorkflowEditorSnapshot(),
            goal = "解释展开后的调用链",
            preferredStyle = null,
            explanationFocus = null,
            focusNodeId = "action:step-15",
            followUp = null,
            granularity = com.charmnight.linkgraph.workbench.StepGranularity.BUSINESS,
        )

        assertEquals(12, context.sourceContext.size)
        assertTrue(context.sourceContext.any { snippet -> snippet.nodeId == "action:step-15" })
        assertEquals(16, context.stepSourceContext.size)
        assertTrue(context.stepSourceContext.any { snippet ->
            snippet.nodeId == "action:step-15" && snippet.snippet == "step15();"
        })
    }

    @Test
    fun computePlanningPayloadDoesNotPreloadGenerationSourceSnippetsFromConfirmedChangesAndPlanScopes() {
        val sourceFile = Files.createTempFile("generation-source-context", ".java")
        val sourceCode = """
            package com.example;

            class CommonController {
                public String fileDownload(String baseUrl) {
                    if (baseUrl.startsWith("/usr")) {
                        return baseUrl.replaceFirst("/usr", "/tmp");
                    }
                    return baseUrl;
                }

                public void uploadFile(String file) {
                    validate(file);
                }

                private void validate(String file) {
                }
            }
        """.trimIndent()
        Files.writeString(sourceFile, sourceCode)

        val downloadSnippet = """
            public String fileDownload(String baseUrl) {
                if (baseUrl.startsWith("/usr")) {
                    return baseUrl.replaceFirst("/usr", "/tmp");
                }
                return baseUrl;
            }
        """.trimIndent()
        val uploadSnippet = """
            public void uploadFile(String file) {
                validate(file);
            }
        """.trimIndent()
        val uploadStartOffset = sourceCode.indexOf(uploadSnippet)
        val uploadEndOffset = uploadStartOffset + uploadSnippet.length

        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    signature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startLine" to "4",
                        "source.endLine" to "9",
                    ),
                ),
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startOffset" to uploadStartOffset.toString(),
                        "source.endOffset" to uploadEndOffset.toString(),
                        "source.startLine" to "11",
                        "source.endLine" to "13",
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(
            workingGraph = graph,
            draftWorkbenchState = DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(
                        entryId = "draft-change-file-download",
                        kind = DraftEntryKind.CHANGE,
                        sourceChangeId = "change-file-download",
                        title = "修改 fileDownload",
                        targetNodeIds = listOf("method:file-download"),
                        beforeState = "原逻辑直接返回 baseUrl。",
                        afterState = "增加 /usr 到 /tmp 的改写。",
                        reason = "统一 Linux 临时目录。",
                        impactSummary = "影响下载路径。",
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-file-download",
                                targetNodeId = "method:file-download",
                                filePath = sourceFile.toString(),
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                                startLine = 4,
                                endLine = 9,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val generationPlan = GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = "修改 CommonController 的两个方法。",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-upload-file",
                    title = "修改 uploadFile",
                    description = "补上传校验。",
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = sourceFile.toString(),
                ),
            ),
        )

        val payload = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        ).computePlanningPayload(snapshot.toWorkflowEditorSnapshot(), generationPlanOverride = generationPlan)

        assertTrue(payload.sourceContext.isEmpty())
    }

    @Test
    fun computePlanningPayloadDoesNotReadProjectRelativePlanScopeAgainstProjectBasePath() {
        val projectDir = Files.createTempDirectory("planning-context-project-base")
        val sourceFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
                package com.example;

                class CommonController {
                    public void uploadFile(String file) {
                        validate(file);
                    }

                    private void validate(String file) {
                    }
                }
            """.trimIndent(),
        )

        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                    signature = "com.example.CommonController.uploadFile(java.lang.String):void",
                    metadata = mapOf(
                        "source.filePath" to sourceFile.toString(),
                        "source.startLine" to "4",
                        "source.endLine" to "6",
                    ),
                ),
            ),
        )
        val snapshot = testSnapshot(workingGraph = graph)
        val generationPlan = GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = "修改 uploadFile",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-upload-file",
                    title = "修改 uploadFile",
                    description = "补上传校验。",
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = "src/main/java/com/example/CommonController.java",
                ),
            ),
        )

        val payload = PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState(llmEnabled = true, provider = "MOCK") },
            projectBasePathProvider = { projectDir.toString() },
        ).computePlanningPayload(snapshot.toWorkflowEditorSnapshot(), generationPlanOverride = generationPlan)

        assertTrue(payload.sourceContext.isEmpty())
    }

    private fun planningContextFactory(): PlanningContextFactory =
        PlanningContextFactory(
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
            graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
            settingsProvider = { LinkGraphSettingsState() },
        )
}
