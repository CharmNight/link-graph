package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot

/**
 * debug 自动化里按方法签名定位“可展开调用节点”的辅助器。
 *
 * 优先在 workspace 规范图中找真正的 invocation 节点；
 * 如果当前界面展示的是流程图投影节点，则通过 projection index 回映到规范图节点。
 */
internal object DebugAutoExpandInvocationLocator {
    fun find(
        snapshot: GraphEditorStateSnapshot,
        signature: String,
    ): DebugAutoExpandInvocationMatch? {
        val requestedSignature = signature.trim().takeIf(String::isNotBlank) ?: return null
        val workspaceNodesById = snapshot.workspaceGraph.nodes.associateBy(GraphNode::id)

        findWorkspaceInvocation(snapshot.workspaceGraph.nodes, requestedSignature)?.let { node ->
            return DebugAutoExpandInvocationMatch(
                targetNode = node,
                matchedNode = node,
                matchedGraphName = "workspace",
            )
        }

        val candidateGraphs = listOf(
            CandidateGraph(
                name = "flowchart.visible",
                nodes = snapshot.flowchartView.visibleGraph.nodes,
                projectionIndex = snapshot.flowchartView.projectionIndex,
            ),
            CandidateGraph(
                name = "fact.visible",
                nodes = snapshot.factGraphView.visibleGraph.nodes,
                projectionIndex = snapshot.factGraphView.projectionIndex,
            ),
            CandidateGraph(
                name = "resource.visible",
                nodes = snapshot.resourceRelationView.visibleGraph.nodes,
                projectionIndex = snapshot.resourceRelationView.projectionIndex,
            ),
        )

        candidateGraphs.forEach { graph ->
            val matchedNode = graph.nodes.firstOrNull { node ->
                signaturesMatch(node.signature, requestedSignature)
            } ?: return@forEach

            resolveCanonicalInvocation(
                projectionIndex = graph.projectionIndex,
                projectedNodeId = matchedNode.id,
                workspaceNodesById = workspaceNodesById,
                requestedSignature = requestedSignature,
            )?.let { targetNode ->
                return DebugAutoExpandInvocationMatch(
                    targetNode = targetNode,
                    matchedNode = matchedNode,
                    matchedGraphName = graph.name,
                )
            }
        }

        return null
    }

    private fun findWorkspaceInvocation(
        nodes: List<GraphNode>,
        requestedSignature: String,
    ): GraphNode? =
        nodes.asSequence()
            .filter(::isInvocationNode)
            .firstOrNull { node -> signaturesMatch(node.signature, requestedSignature) }

    private fun resolveCanonicalInvocation(
        projectionIndex: GraphProjectionIndex,
        projectedNodeId: String,
        workspaceNodesById: Map<String, GraphNode>,
        requestedSignature: String,
    ): GraphNode? {
        val candidateNodeIds = linkedSetOf(projectedNodeId)
        projectionIndex.nodeMapping(projectedNodeId)
            ?.canonicalNodeIds
            .orEmpty()
            .forEach(candidateNodeIds::add)

        return candidateNodeIds.asSequence()
            .mapNotNull(workspaceNodesById::get)
            .firstOrNull { node ->
                isInvocationNode(node) && signaturesMatch(node.signature, requestedSignature)
            }
            ?: candidateNodeIds.asSequence()
                .mapNotNull(workspaceNodesById::get)
                .firstOrNull(::isInvocationNode)
    }

    private fun isInvocationNode(node: GraphNode): Boolean =
        node.type == NodeType.FLOW_ACTION && node.metadata["flow.kind"] == "INVOCATION"

    private fun signaturesMatch(
        candidateSignature: String?,
        requestedSignature: String,
    ): Boolean {
        val normalizedCandidate = candidateSignature?.trim()?.takeIf(String::isNotBlank) ?: return false
        return normalizedCandidate == requestedSignature ||
            comparableMethodSignature(normalizedCandidate) == comparableMethodSignature(requestedSignature)
    }

    private fun comparableMethodSignature(signature: String): String {
        val argumentsStart = signature.indexOf('(')
        if (argumentsStart <= 0) {
            return signature.trim()
        }
        val ownerAndMethod = signature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = ownerAndMethod)
        val methodName = ownerAndMethod.substringAfterLast('.')
        val simpleOwner = owner.substringAfterLast('.')
        val argumentsEnd = signature.indexOf(')', startIndex = argumentsStart)
        if (argumentsEnd < argumentsStart) {
            return "$simpleOwner.$methodName${signature.substring(argumentsStart)}"
        }
        val parameters = signature.substring(argumentsStart + 1, argumentsEnd)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(",") { type -> comparableType(type) }
        val returnType = signature.substring(argumentsEnd + 1)
            .removePrefix(":")
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(::comparableType)
            ?: ""
        return "$simpleOwner.$methodName($parameters):$returnType"
    }

    private fun comparableType(type: String): String {
        val trimmed = type.trim().removeSuffix("?")
        val arraySuffix = buildString {
            var rest = trimmed
            while (rest.endsWith("[]")) {
                append("[]")
                rest = rest.removeSuffix("[]")
            }
        }
        val withoutArrays = trimmed.removeSuffix(arraySuffix)
        val erased = withoutArrays.substringBefore('<').substringAfterLast('.').trim()
        return erased + arraySuffix
    }

    private data class CandidateGraph(
        val name: String,
        val nodes: List<GraphNode>,
        val projectionIndex: GraphProjectionIndex,
    )
}

internal data class DebugAutoExpandInvocationMatch(
    val targetNode: GraphNode,
    val matchedNode: GraphNode,
    val matchedGraphName: String,
)
