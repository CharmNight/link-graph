package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType

/**
 * 统一负责候选变更的图 patch 归一化与补齐。
 * 这里是唯一允许把候选文案映射成真实图节点/边 patch 的入口。
 */
class CandidateGraphPatchComposer {
    private val controlFragmentRegex = Regex("""\b(?:if|switch|while|for|do-while|catch)\s*\([^`\n{}]+\)""", RegexOption.IGNORE_CASE)
    private val decisionScopeKinds = setOf("IF", "SWITCH", "FOREACH", "FOR", "WHILE", "DO_WHILE")

    fun normalizeCandidate(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): CandidateDraftChange {
        val graphPatch = resolveGraphPatch(candidate, baseGraph)
        val targetNodeIds = deriveTargetNodeIds(candidate, graphPatch, baseGraph)
        return candidate.copy(
            targetNodeIds = targetNodeIds,
            graphPatch = graphPatch,
        )
    }

    fun resolveGraphPatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): GraphPatch? {
        candidate.patchIntent?.let { explicitIntent ->
            return resolveGraphPatchFromExplicitIntent(candidate, baseGraph, explicitIntent)
        }
        return normalizeGraphPatch(candidate, baseGraph) ?: synthesizePatch(candidate, baseGraph)
    }

    private fun resolveGraphPatchFromExplicitIntent(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
        intent: com.charmnight.linkgraph.workbench.CandidatePatchIntent,
    ): GraphPatch? {
        return when (intent.mode) {
            CandidatePatchIntentMode.UPDATE_EXISTING_NODE -> {
                val targetNode = intent.targetNodeId
                    ?.takeIf(String::isNotBlank)
                    ?.let { nodeId -> baseGraph.nodes.firstOrNull { node -> node.id == nodeId } }
                    ?: return null
                synthesizeExistingNodeUpdatePatch(candidate, targetNode)
            }

            CandidatePatchIntentMode.INSERT_NEW_ACTION -> {
                val attachEdge = intent.attachEdgeId
                    ?.takeIf(String::isNotBlank)
                    ?.let { edgeId -> baseGraph.edges.firstOrNull { edge -> edge.id == edgeId } }
                    ?: return null
                synthesizeInsertedActionPatch(candidate, baseGraph, attachEdge)
            }

            CandidatePatchIntentMode.INSERT_NEW_DECISION -> {
                val attachEdge = intent.attachEdgeId
                    ?.takeIf(String::isNotBlank)
                    ?.let { edgeId -> baseGraph.edges.firstOrNull { edge -> edge.id == edgeId } }
                    ?: return null
                val falseBranchTargetId = intent.falseBranchTargetNodeId?.takeIf(String::isNotBlank) ?: return null
                val falseBranchTarget = baseGraph.nodes.firstOrNull { node -> node.id == falseBranchTargetId } ?: return null
                synthesizeInsertedDecisionPatch(candidate, baseGraph, attachEdge, falseBranchTarget)
            }

            CandidatePatchIntentMode.ANNOTATION_ONLY -> {
                val anchorNodeId = intent.targetNodeId?.takeIf(String::isNotBlank) ?: return null
                synthesizeExplanationNotePatch(candidate, anchorNodeId)
            }
        }
    }

    private fun normalizeGraphPatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): GraphPatch? {
        val patch = candidate.graphPatch ?: return null
        val normalizedOperations = patch.operations.map { operation ->
            normalizeOperation(candidate, operation, baseGraph)
        }
        return patch.copy(operations = normalizedOperations)
    }

    private fun normalizeOperation(
        candidate: CandidateDraftChange,
        operation: GraphPatchOperation,
        baseGraph: GraphDocument,
    ): GraphPatchOperation {
        if (operation.elementKind != GraphDiffElementKind.NODE || operation.action != GraphPatchAction.UPDATE_NODE) {
            return operation
        }
        val targetNode = resolveTargetNode(candidate, baseGraph, operation) ?: return operation
        val normalizedNode = targetNode.copy(
            title = resolveUpdatedNodeTitle(
                targetNode = targetNode,
                candidate = candidate,
                explicitTitle = operation.node?.title,
            ),
            doc = buildPatchedNodeDoc(targetNode, candidate),
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = normalizeUpdatedNodeMetadata(
                targetNode = targetNode,
                incomingMetadata = operation.node?.metadata.orEmpty() + operation.metadata + buildDraftMetadata(candidate),
            ),
        )
        return operation.copy(
            elementId = targetNode.id,
            node = normalizedNode,
            metadata = operation.metadata + buildDraftMetadata(candidate),
        )
    }

    private fun normalizeUpdatedNodeMetadata(
        targetNode: GraphNode,
        incomingMetadata: Map<String, String>,
    ): Map<String, String> {
        val mergedMetadata = targetNode.metadata + incomingMetadata
        val structuralFlowchartKind = when {
            targetNode.metadata["flowchart.kind"] == "DECISION" -> "DECISION"
            targetNode.type == NodeType.FLOW_SCOPE && targetNode.metadata["flow.kind"] in decisionScopeKinds -> "DECISION"
            else -> null
        }
        return structuralFlowchartKind?.let { kind -> mergedMetadata + ("flowchart.kind" to kind) }
            ?: mergedMetadata
    }

    private fun synthesizePatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): GraphPatch? {
        if (candidate.claimType == "EXPLANATION_NOTE") {
            return synthesizeExplanationNotePatch(candidate)
        }
        val targetNode = resolveTargetNode(candidate, baseGraph)
            ?: return null
        return synthesizeExistingNodeUpdatePatch(candidate, targetNode)
    }

    private fun synthesizeInsertedActionPatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
        attachEdge: GraphEdge,
    ): GraphPatch? {
        if (attachEdge.type != EdgeType.CONTROL_FLOW) {
            return null
        }
        val sourceNode = baseGraph.nodes.firstOrNull { node -> node.id == attachEdge.fromNodeId } ?: return null
        val targetNode = baseGraph.nodes.firstOrNull { node -> node.id == attachEdge.toNodeId } ?: return null
        val insertedTitle = resolveInsertedActionTitle(candidate) ?: return null
        val insertedNodeId = "draft-flow-action:${candidate.changeId}"
        val insertedNode = GraphNode(
            id = insertedNodeId,
            type = NodeType.FLOW_ACTION,
            title = insertedTitle,
            doc = buildInsertedNodeDoc(candidate),
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = buildInsertedFlowNodeMetadata(
                candidate = candidate,
                sourceNode = sourceNode,
                targetNode = targetNode,
                flowchartKind = "PROCESS",
                flowKind = "ACTION",
                patchMode = CandidatePatchIntentMode.INSERT_NEW_ACTION.name,
            ),
        )
        val sourceToInsertedEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTROL_FLOW, sourceNode.id, insertedNodeId, candidate.changeId),
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = sourceNode.id,
            toNodeId = insertedNodeId,
            label = attachEdge.label,
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = attachEdge.metadata + buildDraftMetadata(candidate),
        )
        val insertedToTargetEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTROL_FLOW, insertedNodeId, targetNode.id, candidate.changeId),
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = insertedNodeId,
            toNodeId = targetNode.id,
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = buildDraftMetadata(candidate),
        )
        return GraphPatch(
            summary = candidate.title,
            operations = listOf(
                GraphPatchOperation(
                    id = "draft-delete-edge:${candidate.changeId}:${attachEdge.id}",
                    action = GraphPatchAction.DELETE_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = attachEdge.id,
                    title = candidate.title,
                ),
                GraphPatchOperation(
                    id = "draft-add-node:${candidate.changeId}",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = insertedNodeId,
                    title = candidate.title,
                    summary = candidate.impactSummary.ifBlank { candidate.reason },
                    node = insertedNode,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_ACTION.name),
                ),
                GraphPatchOperation(
                    id = "draft-add-edge-source:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = sourceToInsertedEdge.id,
                    title = candidate.title,
                    edge = sourceToInsertedEdge,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_ACTION.name),
                ),
                GraphPatchOperation(
                    id = "draft-add-edge-target:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = insertedToTargetEdge.id,
                    title = candidate.title,
                    edge = insertedToTargetEdge,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_ACTION.name),
                ),
            ),
            addedNodeIds = listOf(insertedNodeId),
            removedEdgeIds = listOf(attachEdge.id),
            addedEdgeIds = listOf(sourceToInsertedEdge.id, insertedToTargetEdge.id),
        )
    }

    private fun synthesizeInsertedDecisionPatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
        attachEdge: GraphEdge,
        falseBranchTarget: GraphNode,
    ): GraphPatch? {
        if (attachEdge.type != EdgeType.CONTROL_FLOW) {
            return null
        }
        val sourceNode = baseGraph.nodes.firstOrNull { node -> node.id == attachEdge.fromNodeId } ?: return null
        val trueBranchTarget = baseGraph.nodes.firstOrNull { node -> node.id == attachEdge.toNodeId } ?: return null
        if (falseBranchTarget.id == trueBranchTarget.id) {
            return null
        }
        val insertedTitle = resolveInsertedDecisionTitle(candidate) ?: return null
        val insertedNodeId = "draft-flow-decision:${candidate.changeId}"
        val insertedNode = GraphNode(
            id = insertedNodeId,
            type = NodeType.FLOW_SCOPE,
            title = insertedTitle,
            doc = buildInsertedNodeDoc(candidate),
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = buildInsertedFlowNodeMetadata(
                candidate = candidate,
                sourceNode = sourceNode,
                targetNode = trueBranchTarget,
                flowchartKind = "DECISION",
                flowKind = "CONDITION",
                patchMode = CandidatePatchIntentMode.INSERT_NEW_DECISION.name,
            ),
        )
        val sourceToInsertedEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTROL_FLOW, sourceNode.id, insertedNodeId, candidate.changeId),
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = sourceNode.id,
            toNodeId = insertedNodeId,
            label = attachEdge.label,
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = attachEdge.metadata + buildDraftMetadata(candidate),
        )
        val trueEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTROL_FLOW, insertedNodeId, trueBranchTarget.id, "${candidate.changeId}-true"),
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = insertedNodeId,
            toNodeId = trueBranchTarget.id,
            label = "TRUE",
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = buildDraftMetadata(candidate),
        )
        val falseEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTROL_FLOW, insertedNodeId, falseBranchTarget.id, "${candidate.changeId}-false"),
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = insertedNodeId,
            toNodeId = falseBranchTarget.id,
            label = "FALSE",
            sourceTag = GraphSourceTag.DRAFT_AI,
            metadata = buildDraftMetadata(candidate),
        )
        return GraphPatch(
            summary = candidate.title,
            operations = listOf(
                GraphPatchOperation(
                    id = "draft-delete-edge:${candidate.changeId}:${attachEdge.id}",
                    action = GraphPatchAction.DELETE_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = attachEdge.id,
                    title = candidate.title,
                ),
                GraphPatchOperation(
                    id = "draft-add-node:${candidate.changeId}",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = insertedNodeId,
                    title = candidate.title,
                    summary = candidate.impactSummary.ifBlank { candidate.reason },
                    node = insertedNode,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_DECISION.name),
                ),
                GraphPatchOperation(
                    id = "draft-add-edge-source:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = sourceToInsertedEdge.id,
                    title = candidate.title,
                    edge = sourceToInsertedEdge,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_DECISION.name),
                ),
                GraphPatchOperation(
                    id = "draft-add-edge-true:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = trueEdge.id,
                    title = candidate.title,
                    edge = trueEdge,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_DECISION.name),
                ),
                GraphPatchOperation(
                    id = "draft-add-edge-false:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = falseEdge.id,
                    title = candidate.title,
                    edge = falseEdge,
                    metadata = buildDraftMetadata(candidate) + mapOf("draft.patchMode" to CandidatePatchIntentMode.INSERT_NEW_DECISION.name),
                ),
            ),
            addedNodeIds = listOf(insertedNodeId),
            removedEdgeIds = listOf(attachEdge.id),
            addedEdgeIds = listOf(sourceToInsertedEdge.id, trueEdge.id, falseEdge.id),
        )
    }

    private fun deriveTargetNodeIds(
        candidate: CandidateDraftChange,
        graphPatch: GraphPatch?,
        baseGraph: GraphDocument,
    ): List<String> {
        val existingNodeIds = baseGraph.nodes.asSequence().map(GraphNode::id).toHashSet()
        val targetNodeIds = linkedSetOf<String>()
        candidate.patchIntent?.let { explicitIntent ->
            explicitIntent.targetNodeId?.takeIf { it in existingNodeIds }?.let(targetNodeIds::add)
            explicitIntent.falseBranchTargetNodeId?.takeIf { it in existingNodeIds }?.let(targetNodeIds::add)
            explicitIntent.attachEdgeId
                ?.takeIf(String::isNotBlank)
                ?.let { edgeId -> baseGraph.edges.firstOrNull { edge -> edge.id == edgeId } }
                ?.let { edge ->
                    edge.fromNodeId.takeIf { it in existingNodeIds }?.let(targetNodeIds::add)
                    edge.toNodeId.takeIf { it in existingNodeIds }?.let(targetNodeIds::add)
                }
        }
        graphPatch?.operations?.forEach { operation ->
            when {
                operation.elementKind == GraphDiffElementKind.NODE && operation.elementId in existingNodeIds -> targetNodeIds += operation.elementId
                operation.node?.id in existingNodeIds -> targetNodeIds += operation.node!!.id
                operation.edge?.fromNodeId in existingNodeIds -> targetNodeIds += operation.edge!!.fromNodeId
                operation.edge?.toNodeId in existingNodeIds -> targetNodeIds += operation.edge!!.toNodeId
            }
        }
        if (targetNodeIds.isEmpty()) {
            targetNodeIds += candidate.targetNodeIds.filter { it in existingNodeIds }
            candidate.evidence
                .flatMap { finding -> finding.references }
                .mapNotNullTo(targetNodeIds) { reference -> reference.nodeId?.takeIf { it in existingNodeIds } }
        }
        return targetNodeIds.toList()
    }

    private fun resolveTargetNode(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
        operation: GraphPatchOperation? = null,
    ): GraphNode? {
        val nodesById = baseGraph.nodes.associateBy(GraphNode::id)
        val referencedNodeIds = linkedSetOf<String>()
        referencedNodeIds += candidate.targetNodeIds
        operation?.elementId?.let(referencedNodeIds::add)
        operation?.node?.id?.let(referencedNodeIds::add)
        candidate.evidence
            .flatMap { finding -> finding.references }
            .mapNotNullTo(referencedNodeIds) { reference -> reference.nodeId?.takeIf(String::isNotBlank) }

        val intent = TargetResolutionIntent.from(candidate, operation)
        val fallbackTarget = referencedNodeIds.firstNotNullOfOrNull(nodesById::get)
        val ownerMethods = referencedNodeIds.mapNotNull { nodeId ->
            ownerMethodSignature(nodesById[nodeId])
        }.toSet()
        val candidatePool = if (ownerMethods.isEmpty()) {
            baseGraph.nodes
        } else {
            baseGraph.nodes.filter { node ->
                ownerMethodSignature(node) in ownerMethods || node.id in referencedNodeIds
            }
        }
        val scoredNodes = candidatePool.map { node ->
            node to scoreTargetNode(node, candidate, intent, referencedNodeIds, operation)
        }
        val bestMatch = scoredNodes.maxByOrNull { (_, score) -> score } ?: return fallbackTarget
        val fallbackScore = fallbackTarget?.let { node ->
            scoreTargetNode(node, candidate, intent, referencedNodeIds, operation)
        } ?: Int.MIN_VALUE
        return when {
            fallbackTarget == null -> bestMatch.first.takeIf { bestMatch.second > Int.MIN_VALUE / 2 }
            shouldRetarget(fallbackTarget, bestMatch.first, fallbackScore, bestMatch.second, intent) -> bestMatch.first
            else -> fallbackTarget
        }
    }

    private fun shouldRetarget(
        currentTarget: GraphNode,
        bestTarget: GraphNode,
        currentScore: Int,
        bestScore: Int,
        intent: TargetResolutionIntent,
    ): Boolean {
        if (currentTarget.id == bestTarget.id) {
            return false
        }
        if (bestScore <= currentScore) {
            return false
        }
        val currentKind = currentTarget.metadata["flowchart.kind"]
        val bestKind = bestTarget.metadata["flowchart.kind"]
        if (intent.expectsDecisionNode && currentKind != "DECISION" && bestKind == "DECISION") {
            return true
        }
        return bestScore >= currentScore + 180
    }

    private fun scoreTargetNode(
        node: GraphNode,
        candidate: CandidateDraftChange,
        intent: TargetResolutionIntent,
        referencedNodeIds: Set<String>,
        operation: GraphPatchOperation?,
    ): Int {
        var score = 0
        if (node.id in referencedNodeIds) {
            score += 120
        }
        if (candidate.evidence.flatMap { finding -> finding.references }.any { reference -> reference.nodeId == node.id }) {
            score += 320
        }
        if (candidate.beforeState.equals(node.title, ignoreCase = false)) {
            score += 900
        }
        if (candidate.afterState.equals(node.title, ignoreCase = false)) {
            score += 240
        }
        if (intent.controlFragments.any { fragment -> fragment.equals(node.title, ignoreCase = true) }) {
            score += 720
        }
        if (operation?.elementId == node.id || operation?.node?.id == node.id) {
            score += 60
        }
        val overlapCount = intent.tokens.intersect(tokenize(node.title)).size
        score += overlapCount.coerceAtMost(4) * 70

        when (node.metadata["flowchart.kind"]) {
            "DECISION" -> if (intent.expectsDecisionNode) {
                score += 420
            }
            "SCOPE" -> if (intent.expectsDecisionNode) {
                score -= 280
            }
        }
        if (node.type == NodeType.FLOW_SCOPE && intent.expectsDecisionNode) {
            score += 80
        }
        if (node.title.equals("try", ignoreCase = true) && intent.expectsDecisionNode) {
            score -= 260
        }
        if (node.title.startsWith("catch", ignoreCase = true) && intent.expectsDecisionNode) {
            score -= 200
        }
        return score
    }

    private fun buildDraftMetadata(candidate: CandidateDraftChange): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        metadata["draft.changeId"] = candidate.changeId
        candidate.claimType?.let { metadata["draft.claimType"] = it }
        return metadata
    }

    private fun ownerMethodSignature(node: GraphNode?): String? {
        node ?: return null
        return node.metadata["flow.ownerMethod"] ?: node.signature
    }

    private fun synthesizeExistingNodeUpdatePatch(
        candidate: CandidateDraftChange,
        targetNode: GraphNode,
    ): GraphPatch? {
        val patchDoc = buildPatchedNodeDoc(targetNode, candidate)
        val hasDraftContent = !patchDoc.isNullOrBlank() ||
            candidate.reason.isNotBlank() ||
            candidate.impactSummary.isNotBlank()
        if (!hasDraftContent) {
            return null
        }
        val mergedMetadata = linkedMapOf<String, String>()
        mergedMetadata += targetNode.metadata
        mergedMetadata += buildDraftMetadata(candidate)
        mergedMetadata["draft.patchMode"] = "UPDATE_EXISTING_NODE"
        return GraphPatch(
            summary = candidate.title,
            operations = listOf(
                GraphPatchOperation(
                    id = "draft-update-node:${candidate.changeId}",
                    action = GraphPatchAction.UPDATE_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = targetNode.id,
                    title = candidate.title,
                    summary = candidate.impactSummary.ifBlank { candidate.reason },
                    node = GraphNode(
                        id = targetNode.id,
                        type = targetNode.type,
                        title = resolveUpdatedNodeTitle(
                            targetNode = targetNode,
                            candidate = candidate,
                            explicitTitle = null,
                        ),
                        doc = patchDoc,
                        sourceTag = GraphSourceTag.DRAFT_AI,
                        metadata = mergedMetadata,
                    ),
                    metadata = mapOf(
                        "draft.changeId" to candidate.changeId,
                        "draft.patchMode" to "UPDATE_EXISTING_NODE",
                    ) + candidate.claimType?.let { mapOf("draft.claimType" to it) }.orEmpty(),
                ),
            ),
        )
    }

    private fun buildPatchedNodeDoc(
        targetNode: GraphNode,
        candidate: CandidateDraftChange,
    ): String? {
        val segments = listOfNotNull(
            targetNode.doc?.takeIf(String::isNotBlank),
            candidate.beforeState?.takeIf(String::isNotBlank)?.let { "修改前：$it" },
            candidate.afterState?.takeIf(String::isNotBlank)?.let { "修改后：$it" },
            candidate.reason.takeIf(String::isNotBlank)?.let { "原因：$it" },
            candidate.impactSummary.takeIf(String::isNotBlank)?.let { "影响：$it" },
        )
        return segments.joinToString("\n").ifBlank { null }
    }

    private fun buildInsertedNodeDoc(candidate: CandidateDraftChange): String? {
        return listOfNotNull(
            candidate.beforeState?.takeIf(String::isNotBlank)?.let { "修改前：$it" },
            candidate.afterState?.takeIf(String::isNotBlank)?.let { "修改后：$it" },
            candidate.reason.takeIf(String::isNotBlank)?.let { "原因：$it" },
            candidate.impactSummary.takeIf(String::isNotBlank)?.let { "影响：$it" },
        ).joinToString("\n").ifBlank { null }
    }

    private fun resolveUpdatedNodeTitle(
        targetNode: GraphNode,
        candidate: CandidateDraftChange,
        explicitTitle: String?,
    ): String {
        val normalizedExplicitTitle = explicitTitle?.trim().takeIf { !it.isNullOrBlank() }
        val explicitDisplayTitle = normalizedExplicitTitle?.let {
            resolveDisplayablePatchedTitle(it, targetNode, candidate.beforeState)
        }
        if (explicitDisplayTitle != null) {
            return explicitDisplayTitle
        }
        val normalizedAfterState = candidate.afterState?.trim().takeIf { !it.isNullOrBlank() }
        val afterStateDisplayTitle = normalizedAfterState?.let {
            resolveDisplayablePatchedTitle(it, targetNode, candidate.beforeState)
        }
        if (afterStateDisplayTitle != null) {
            return afterStateDisplayTitle
        }
        return targetNode.title
    }

    private fun resolveDisplayablePatchedTitle(
        rawTitle: String,
        targetNode: GraphNode,
        beforeState: String?,
    ): String? {
        val normalizedTitle = rawTitle.trim()
        if (shouldUseAsPatchedTitle(normalizedTitle, targetNode, beforeState)) {
            return normalizedTitle
        }
        val extractedControlTitle = extractDisplayableControlFragment(normalizedTitle)
            ?.takeIf { fragment -> shouldUseAsPatchedTitle(fragment, targetNode, beforeState) }
        return extractedControlTitle
    }

    private fun extractDisplayableControlFragment(text: String): String? {
        return controlFragmentRegex.find(text)?.value?.trim()
    }

    private fun resolveInsertedDecisionTitle(candidate: CandidateDraftChange): String? {
        return listOfNotNull(candidate.afterState, candidate.title)
            .map(String::trim)
            .firstNotNullOfOrNull(::resolveDisplayableDecisionTitle)
    }

    private fun resolveDisplayableDecisionTitle(rawTitle: String): String? {
        val normalizedTitle = rawTitle.trim()
        if (normalizedTitle.isBlank() || normalizedTitle.length > 120 || normalizedTitle.contains('\n')) {
            return null
        }
        if (normalizedTitle.contains('。') || normalizedTitle.contains('；') || normalizedTitle.contains('：')) {
            return null
        }
        if (controlKeyword(normalizedTitle) in setOf("if", "switch", "while", "for", "catch")) {
            return normalizedTitle
        }
        val extractedControlTitle = extractDisplayableControlFragment(normalizedTitle) ?: return null
        return extractedControlTitle.takeIf { controlKeyword(it) in setOf("if", "switch", "while", "for", "catch") }
    }

    private fun resolveInsertedActionTitle(candidate: CandidateDraftChange): String? {
        return listOfNotNull(candidate.afterState, candidate.title)
            .map(String::trim)
            .firstOrNull(::isReadableActionTitle)
    }

    private fun isReadableActionTitle(title: String): Boolean {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank() || normalizedTitle.length > 80 || normalizedTitle.contains('\n')) {
            return false
        }
        if (normalizedTitle.contains('。') || normalizedTitle.contains('；') || normalizedTitle.contains('：')) {
            return false
        }
        return true
    }

    private fun shouldUseAsPatchedTitle(
        title: String,
        targetNode: GraphNode,
        beforeState: String?,
    ): Boolean {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank() || normalizedTitle.length > 120 || normalizedTitle.contains('\n')) {
            return false
        }
        if (normalizedTitle.contains('。') || normalizedTitle.contains('；') || normalizedTitle.contains('：')) {
            return false
        }
        val targetKind = targetNode.metadata["flowchart.kind"]
        val titleControlKeyword = controlKeyword(normalizedTitle)
        val beforeControlKeyword = beforeState?.let(::controlKeyword)
        if (targetKind == "DECISION") {
            return when {
                titleControlKeyword in setOf("if", "switch", "while", "for", "catch") -> true
                beforeControlKeyword != null && titleControlKeyword == beforeControlKeyword -> true
                else -> false
            }
        }
        return normalizedTitle.length <= 80
    }

    private fun controlKeyword(value: String): String? {
        val normalizedValue = value.trim().lowercase()
        return when {
            normalizedValue.startsWith("if ") || normalizedValue.startsWith("if(") -> "if"
            normalizedValue.startsWith("switch ") || normalizedValue.startsWith("switch(") -> "switch"
            normalizedValue.startsWith("while ") || normalizedValue.startsWith("while(") -> "while"
            normalizedValue.startsWith("for ") || normalizedValue.startsWith("for(") -> "for"
            normalizedValue.startsWith("catch ") || normalizedValue.startsWith("catch(") -> "catch"
            normalizedValue == "try" || normalizedValue.startsWith("try ") -> "try"
            else -> null
        }
    }

    private fun buildInsertedFlowNodeMetadata(
        candidate: CandidateDraftChange,
        sourceNode: GraphNode,
        targetNode: GraphNode,
        flowchartKind: String,
        flowKind: String,
        patchMode: String,
    ): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        metadata["flowchart.kind"] = flowchartKind
        metadata["flow.kind"] = flowKind
        sourceNode.metadata["flow.ownerMethod"]
            ?.takeIf(String::isNotBlank)
            ?.let { metadata["flow.ownerMethod"] = it }
            ?: targetNode.metadata["flow.ownerMethod"]
                ?.takeIf(String::isNotBlank)
                ?.let { metadata["flow.ownerMethod"] = it }
        sourceNode.metadata["flow.anchorMethod"]
            ?.takeIf(String::isNotBlank)
            ?.let { metadata["flow.anchorMethod"] = it }
            ?: targetNode.metadata["flow.anchorMethod"]
                ?.takeIf(String::isNotBlank)
                ?.let { metadata["flow.anchorMethod"] = it }
        metadata += buildDraftMetadata(candidate)
        metadata["draft.patchMode"] = patchMode
        return metadata
    }

    private fun synthesizeExplanationNotePatch(candidate: CandidateDraftChange): GraphPatch? {
        val anchorNodeId = candidate.targetNodeIds.firstOrNull()
            ?: candidate.evidence
                .flatMap { finding -> finding.references }
                .firstNotNullOfOrNull { reference -> reference.nodeId?.takeIf(String::isNotBlank) }
        return anchorNodeId?.let { synthesizeExplanationNotePatch(candidate, it) }
    }

    private fun synthesizeExplanationNotePatch(
        candidate: CandidateDraftChange,
        anchorNodeId: String,
    ): GraphPatch? {
        val noteNodeId = "draft-note:${candidate.changeId}"
        val noteTitle = candidate.afterState?.takeIf(String::isNotBlank)
            ?: candidate.title.ifBlank { "草稿变更说明" }
        val noteDoc = buildString {
            candidate.beforeState?.takeIf(String::isNotBlank)?.let { append("修改前：").append(it).append('\n') }
            candidate.afterState?.takeIf(String::isNotBlank)?.let { append("修改后：").append(it).append('\n') }
            if (candidate.reason.isNotBlank()) {
                append("原因：").append(candidate.reason).append('\n')
            }
            if (candidate.impactSummary.isNotBlank()) {
                append("影响：").append(candidate.impactSummary)
            }
        }.trim().ifBlank { candidate.title.ifBlank { "草稿变更说明" } }
        val noteEdgeId = "draft-edge:${anchorNodeId}->${candidate.changeId}"
        return GraphPatch(
            summary = candidate.title,
            operations = listOf(
                GraphPatchOperation(
                    id = "draft-note-op:${candidate.changeId}",
                    action = GraphPatchAction.ADD_ANNOTATION,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = noteNodeId,
                    title = candidate.title,
                    summary = candidate.impactSummary.ifBlank { candidate.reason },
                    node = GraphNode(
                        id = noteNodeId,
                        type = NodeType.DOC_PAGE,
                        title = noteTitle,
                        doc = noteDoc,
                        sourceTag = GraphSourceTag.DRAFT_AI,
                        metadata = mapOf(
                            "draft.role" to "change-note",
                            "draft.changeId" to candidate.changeId,
                        ),
                    ),
                ),
                GraphPatchOperation(
                    id = "draft-edge-op:${candidate.changeId}",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = noteEdgeId,
                    title = candidate.title,
                    edge = GraphEdge(
                        id = noteEdgeId,
                        type = EdgeType.LINKS_DOC,
                        fromNodeId = anchorNodeId,
                        toNodeId = noteNodeId,
                        label = "调整说明",
                        sourceTag = GraphSourceTag.DRAFT_AI,
                        metadata = mapOf("draft.changeId" to candidate.changeId),
                    ),
                ),
            ),
            addedNodeIds = listOf(noteNodeId),
            addedEdgeIds = listOf(noteEdgeId),
        )
    }

    private data class TargetResolutionIntent(
        val expectsDecisionNode: Boolean,
        val controlFragments: List<String>,
        val tokens: Set<String>,
    ) {
        companion object {
            private val controlFragmentRegex = Regex("""\b(?:if|switch|while|for|do-while|catch)\s*\([^`\n{}]+\)""", RegexOption.IGNORE_CASE)
            private val tokenRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*|[\u4E00-\u9FFF]{2,}""")

            fun from(
                candidate: CandidateDraftChange,
                operation: GraphPatchOperation?,
            ): TargetResolutionIntent {
                val texts = listOfNotNull(
                    candidate.title,
                    candidate.beforeState,
                    candidate.afterState,
                    candidate.reason,
                    candidate.impactSummary,
                    operation?.title,
                    operation?.summary,
                    operation?.node?.title,
                    operation?.node?.doc,
                )
                val combined = texts.joinToString("\n")
                val controlFragments = controlFragmentRegex.findAll(combined).map { match ->
                    match.value.trim()
                }.toList()
                val expectsDecisionNode = controlFragments.isNotEmpty() ||
                    combined.contains("条件") ||
                    combined.contains("判断") ||
                    combined.contains("分支") ||
                    combined.contains("校验") ||
                    combined.contains("branch", ignoreCase = true)
                val tokens = tokenize(combined)
                return TargetResolutionIntent(
                    expectsDecisionNode = expectsDecisionNode,
                    controlFragments = controlFragments,
                    tokens = tokens,
                )
            }

            private fun tokenize(text: String): Set<String> {
                return tokenRegex.findAll(text)
                    .map { it.value.lowercase() }
                    .filter { it.length > 1 }
                    .toSet()
            }
        }
    }

    private companion object {
        private val tokenRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*|[\u4E00-\u9FFF]{2,}""")

        fun tokenize(text: String): Set<String> {
            return tokenRegex.findAll(text)
                .map { it.value.lowercase() }
                .filter { it.length > 1 }
                .toSet()
        }
    }
}
