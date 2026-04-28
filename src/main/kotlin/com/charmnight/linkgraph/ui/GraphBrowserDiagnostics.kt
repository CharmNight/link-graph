package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.services.currentVisibleGraph
import com.charmnight.linkgraph.services.currentWorkingGraph
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.llm.GenerationPlan

internal object GraphBrowserDiagnostics {
    fun snapshotSummary(snapshot: GraphEditorStateSnapshot): String {
        fun graphSummary(document: GraphDocument?): String {
            if (document == null) {
                return "0/0"
            }
            return "${document.nodes.size}/${document.edges.size} sample=${document.nodes.take(6).map { it.id }}"
        }

        fun requestStateSummary(state: AsyncRequestState): String {
            return buildString {
                append("phase=").append(state.phase)
                append(", requestId=").append(state.requestId)
                append(", streaming=").append(state.streaming)
                append(", status=").append(state.statusMessage)
                append(", detail=").append(state.detailMessage)
                append(", error=").append(state.errorMessage)
                append(", provider=").append(state.providerLabel)
                append(", model=").append(state.model)
            }
        }

        fun generationPlanSummary(plan: GenerationPlan?): String {
            if (plan == null) {
                return "null"
            }
            return "source=${plan.source}, items=${plan.items.size}, warnings=${plan.warnings.size}, summary=${summarizePayloadText(plan.summary)}"
        }

        return buildString {
            val effectiveVisibleGraph = currentVisibleGraph(snapshot)
            val effectiveWorkspaceGraph = currentWorkingGraph(snapshot)
            val currentSceneState = snapshot.currentSceneState()
            append("lastMessageType=").append(snapshot.lastMessageType)
            append(", lastGraphSource=").append(snapshot.lastGraphSource)
            append(", analysisDisplayMode=").append(snapshot.analysisDisplayMode)
            append(", currentSceneId=").append(snapshot.currentSceneId)
            append(", semanticRevision=").append(snapshot.semanticRevision)
            append(", workspaceRevision=").append(snapshot.workspaceRevision)
            append(", layoutRevision=").append(currentSceneState.layoutRevision)
            append(", snapshotRevision=").append(snapshot.snapshotRevision)
            append(", selectedNodeId=").append(currentSceneState.selectedNodeId)
            append(", visibleGraph=").append(graphSummary(effectiveVisibleGraph))
            append(", workspaceGraph=").append(graphSummary(effectiveWorkspaceGraph))
            append(", semanticFactGraph=").append(graphSummary(snapshot.semanticFactGraph))
            append(", generationPlan=").append(generationPlanSummary(snapshot.generationPlan))
            append(", generationPlanRequestState=").append(requestStateSummary(snapshot.generationPlanRequestState))
            append(", feedback=").append(snapshot.operationFeedback?.message)
        }
    }

    fun snapshotDeltaSummary(
        previous: GraphEditorStateSnapshot,
        next: GraphEditorStateSnapshot,
    ): String {
        return buildString {
            append("visible{").append(graphDeltaSummary(currentVisibleGraph(previous), currentVisibleGraph(next))).append("}")
            append(", workspace{").append(graphDeltaSummary(currentWorkingGraph(previous), currentWorkingGraph(next))).append("}")
        }
    }

    fun summarizePayloadText(
        value: String?,
        maxLength: Int = 160,
    ): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return "\"\""
        }
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength) + "...(trimmed)"
        }
    }

    private fun graphDeltaSummary(
        previous: GraphDocument?,
        next: GraphDocument?,
    ): String {
        val previousNodes = previous?.nodes?.associateBy { it.id }.orEmpty()
        val nextNodes = next?.nodes?.associateBy { it.id }.orEmpty()
        val added = nextNodes.keys.subtract(previousNodes.keys)
        val removed = previousNodes.keys.subtract(nextNodes.keys)
        val retitled = nextNodes.keys.intersect(previousNodes.keys)
            .mapNotNull { nodeId ->
                val before = previousNodes[nodeId] ?: return@mapNotNull null
                val after = nextNodes[nodeId] ?: return@mapNotNull null
                if (before.title == after.title) {
                    null
                } else {
                    "$nodeId:${summarizePayloadText(before.title)} -> ${summarizePayloadText(after.title)}"
                }
            }
        return buildString {
            append("added=").append(added.take(4))
            append(", removed=").append(removed.take(4))
            append(", retitled=").append(retitled.take(4))
        }
    }
}
