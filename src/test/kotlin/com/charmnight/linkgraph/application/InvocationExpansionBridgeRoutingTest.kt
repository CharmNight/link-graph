package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeHooks
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.application.usecase.InvocationExpansionUseCase
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.*
import com.charmnight.linkgraph.ui.GraphEditorCommandRouter
import com.charmnight.linkgraph.ui.GraphEditorMessage
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationExpansionBridgeRoutingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerGraphEditorApplicationServicesForTest()
    }

    fun testRoutesRequestExpandInvocationMessageToApplicationService() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(invocationOnlyGraph(), "test"))
        project.getService(LinkGraphProjectRuntimeHooks::class.java).invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.EXTERNAL_JDK, signature = signature)
        }

        project.getService(GraphEditorCommandRouter::class.java)
            .dispatch(GraphEditorMessage.RequestExpandInvocation("invoke:create-info"))

        assertTrue(
            project.getService(GraphEditorStateService::class.java)
                .snapshot()
                .operationFeedback
                ?.message
                ?.contains("JDK 方法") == true,
        )
    }

    fun testRoutesRequestRemoveInvocationExpansionMessageToApplicationService() {
        val expansionId = "expansion-1"
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(expandedGraph(expansionId), "test"))

        project.getService(GraphEditorCommandRouter::class.java)
            .dispatch(GraphEditorMessage.RequestRemoveInvocationExpansion(expansionId))

        assertFalse(
            project.getService(GraphEditorStateService::class.java)
                .snapshot()
                .workspaceGraph
                .nodes
                .any { node -> node.id == "action:save-info" },
        )
    }

    private fun invocationOnlyGraph(): GraphDocument =
        GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "invoke:create-info",
                    type = NodeType.FLOW_ACTION,
                    title = "systemService.createInfo()",
                    signature = CREATE_INFO_SIGNATURE,
                    metadata = mapOf("flow.kind" to "INVOCATION"),
                ),
            ),
        )

    private fun expandedGraph(expansionId: String): GraphDocument =
        GraphDocument(
            nodes = invocationOnlyGraph().nodes + GraphNode(
                id = "action:save-info",
                type = NodeType.FLOW_ACTION,
                title = "saveInfo()",
                metadata = mapOf(InvocationExpansionUseCase.EXPANSION_ID to expansionId),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:expanded",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "invoke:create-info",
                    toNodeId = "action:save-info",
                    metadata = mapOf(InvocationExpansionUseCase.EXPANSION_ID to expansionId),
                ),
            ),
        )

    private companion object {
        const val CREATE_INFO_SIGNATURE = "java.lang.String.trim():java.lang.String"
    }
}
