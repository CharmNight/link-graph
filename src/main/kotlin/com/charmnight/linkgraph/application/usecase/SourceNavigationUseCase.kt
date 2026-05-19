package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

sealed interface SourceNavigationUseCaseResult {
    data class MissingTrustedNode(val nodeId: String) : SourceNavigationUseCaseResult
    data class NotNavigable(val node: GraphNode) : SourceNavigationUseCaseResult
    data class Ready(val node: GraphNode) : SourceNavigationUseCaseResult
    data object SettingsOpenRequested : SourceNavigationUseCaseResult
}

class SourceNavigationUseCase(
    private val navigationNodeFinder: (WorkflowEditorSnapshot, String) -> GraphNode?,
) {
    fun requestSourceNavigation(
        snapshot: WorkflowEditorSnapshot,
        nodeId: String,
    ): SourceNavigationUseCaseResult {
        val node = navigationNodeFinder(snapshot, nodeId)
            ?: return SourceNavigationUseCaseResult.MissingTrustedNode(nodeId)
        if (!canNavigateToSource(node)) {
            return SourceNavigationUseCaseResult.NotNavigable(node)
        }
        return SourceNavigationUseCaseResult.Ready(node)
    }

    fun requestOpenSettings(): SourceNavigationUseCaseResult.SettingsOpenRequested =
        SourceNavigationUseCaseResult.SettingsOpenRequested

    private fun canNavigateToSource(node: GraphNode): Boolean {
        if (!node.location.isNullOrBlank()) {
            return true
        }
        return (
            node.type == NodeType.METHOD ||
                node.type == NodeType.CLASS ||
                node.type == NodeType.INTERFACE ||
                node.type == NodeType.ENUM ||
                node.type == NodeType.ANNOTATION ||
                node.type == NodeType.RECORD ||
                node.type == NodeType.OBJECT ||
                node.type == NodeType.EXTERNAL_CLASS
            ) && !node.signature.isNullOrBlank()
    }
}
