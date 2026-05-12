package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphNode

/** Finds a node across the graph documents that can legitimately back source navigation. */
internal fun findNavigationNode(
    snapshot: com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? {
    return sequenceOf(
        currentVisibleGraph(snapshot),
        currentWorkingGraph(snapshot),
        snapshot.semanticFactGraph,
        snapshot.workspaceBaseGraph,
        snapshot.designBaselineGraph,
    ).filterNotNull()
        .flatMap { graph -> graph.nodes.asSequence() }
        .firstOrNull { node -> node.id == nodeId }
}

internal fun findTrustedNavigationNode(
    snapshot: com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? = snapshot.trustedNavigationNodes[nodeId]
