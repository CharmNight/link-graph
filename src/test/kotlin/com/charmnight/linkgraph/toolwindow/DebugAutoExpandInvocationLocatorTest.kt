package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.testSnapshot
import com.charmnight.linkgraph.toolwindow.debug.DebugAutoExpandInvocationLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DebugAutoExpandInvocationLocatorTest {
    @Test
    fun findsWorkspaceInvocationNodeByComparableSignature() {
        val snapshot = testSnapshot(
            workspaceGraph = GraphDocument(
                nodes = listOf(
                    invocationNode(
                        id = "invoke:detect-package-managers",
                        signature = "com.cpescan.core.package_manager.AbstractPackageManagerScanner.detectPackageManagers():java.util.List<java.lang.String>",
                    ),
                ),
            ),
        )

        val match = DebugAutoExpandInvocationLocator.find(
            snapshot,
            "com.cpescan.core.package_manager.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
        )

        assertNotNull(match)
        assertEquals("workspace", match.matchedGraphName)
        assertEquals("invoke:detect-package-managers", match.targetNode.id)
    }

    @Test
    fun resolvesProjectedFlowchartActionBackToCanonicalInvocationNode() {
        val workspaceInvocation = invocationNode(
            id = "invoke:create-info",
            signature = "com.example.SystemService.createInfo(java.lang.String):void",
        )
        val projectedAction = GraphNode(
            id = "action:create-info",
            type = NodeType.FLOW_ACTION,
            title = "调用 SystemService.createInfo",
            signature = "com.example.SystemService.createInfo():void",
            metadata = mapOf(
                "flow.kind" to "ACTION",
                "flowchart.kind" to "PROCESS",
            ),
        )
        val snapshot = testSnapshot(
            workspaceGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:caller",
                        type = NodeType.METHOD,
                        title = "Caller.run",
                        signature = "com.example.Caller.run():void",
                    ),
                    projectedAction,
                    workspaceInvocation,
                ),
            ),
            flowchartView = com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument(
                visibleGraph = GraphDocument(nodes = listOf(projectedAction)),
                fullGraph = GraphDocument(nodes = listOf(projectedAction, workspaceInvocation)),
                projectionIndex = GraphProjectionIndex(
                    nodeMappings = mapOf(
                        projectedAction.id to GraphProjectionNodeMapping(
                            projectedNodeId = projectedAction.id,
                            mappingKind = GraphProjectionMappingKind.MERGED_ALIAS,
                            canonicalNodeIds = listOf(projectedAction.id, workspaceInvocation.id),
                        ),
                    ),
                ),
            ),
            analysisDisplayMode = com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART,
        )

        val match = DebugAutoExpandInvocationLocator.find(
            snapshot,
            "com.example.SystemService.createInfo():void",
        )

        assertNotNull(match)
        assertEquals("flowchart.visible", match.matchedGraphName)
        assertEquals(projectedAction.id, match.matchedNode.id)
        assertEquals(workspaceInvocation.id, match.targetNode.id)
    }

    private fun invocationNode(
        id: String,
        signature: String,
    ): GraphNode =
        GraphNode(
            id = id,
            type = NodeType.FLOW_ACTION,
            title = id,
            signature = signature,
            metadata = mapOf(
                "flow.kind" to "INVOCATION",
                "flowchart.kind" to "SUBROUTINE",
            ),
        )
}
