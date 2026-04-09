package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.normalizeStableComponent

class StepProjectionService {
    fun buildSteps(
        factGraph: GraphDocument,
        draftEntries: List<DraftWorkbenchEntry>,
        granularity: StepGranularity,
    ): StepProjectionResult {
        val steps = when (granularity) {
            StepGranularity.BUSINESS -> factGraph.nodes
                .asSequence()
                .filter(::isStepNode)
                .sortedBy { node -> node.metadata["source.startLine"]?.toIntOrNull() ?: Int.MAX_VALUE }
                .map { node ->
                    WorkbenchStep(
                        stepId = node.metadata["workbench.businessStepId"] ?: fallbackStepId(node),
                        title = node.metadata["workbench.businessStepTitle"] ?: node.title,
                        granularity = StepGranularity.BUSINESS,
                        kind = stepKindFor(node),
                        description = "",
                        nodeRefs = listOf(node.id),
                    )
                }
                .toList()

            StepGranularity.METHOD_CALL,
            StepGranularity.CODE_SEMANTIC,
            -> emptyList()
        }
        return StepProjectionResult(steps = steps)
    }

    private fun isStepNode(node: GraphNode): Boolean {
        return node.type == NodeType.FLOW_ACTION || node.type == NodeType.TERMINAL
    }

    private fun stepKindFor(node: GraphNode): StepKind {
        return when (node.type) {
            NodeType.TERMINAL -> StepKind.RETURN
            else -> StepKind.BUSINESS_ACTION
        }
    }

    private fun fallbackStepId(node: GraphNode): String {
        return "step-${normalizeStableComponent(node.title)}"
    }
}
