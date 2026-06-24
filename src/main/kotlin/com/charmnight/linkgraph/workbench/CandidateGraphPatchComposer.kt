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
    // 从文本中抽取 `if/switch/while/for/do-while/catch (...)` 这类控制流片段的正则，用于识别节点标题是否代表决策。
    private val controlFragmentRegex = Regex("""\b(?:if|switch|while|for|do-while|catch)\s*\([^`\n{}]+\)""", RegexOption.IGNORE_CASE)
    // 流程图中归类为决策（条件分支）的 scope kind 集合，用于在归一化时修正 flowchart.kind。
    private val decisionScopeKinds = setOf("IF", "SWITCH", "FOREACH", "FOR", "WHILE", "DO_WHILE")

    /**
     * 把一条候选变更归一化：解析出最终的 [GraphPatch]，并重新推导出与之匹配的目标节点列表。
     * 调用方拿到的结果会同时携带 patch 与 targetNodeIds，可安全用于 UI 预览与提交。
     */
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

    /**
     * 给定候选变更与基础图谱，推导出最终的可应用 [GraphPatch]。
     * 优先使用显式意图（patchIntent）合成；否则归一化候选自带的 patch；最后才退化为兜底合成。
     */
    fun resolveGraphPatch(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): GraphPatch? {
        candidate.patchIntent?.let { explicitIntent ->
            return resolveGraphPatchFromExplicitIntent(candidate, baseGraph, explicitIntent)
        }
        return normalizeGraphPatch(candidate, baseGraph) ?: synthesizePatch(candidate, baseGraph)
    }

    /**
     * 当候选携带显式意图（如「更新现有节点」「插入新动作」「插入新决策」「仅加注释」）时，
     * 按意图分支组合出对应 patch。意图所需的目标节点 / 边 / 分支若无法解析则返回 null。
     */
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

    /**
     * 对候选自带的 patch 做归一化：逐个修正 UPDATE_NODE 操作里的标题、文档、源标签与元数据，
     * 使最终 patch 落到真实的图节点上，避免 LLM 输出的偏差被原样带入。
     */
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

    /**
     * 归一化单个操作：只处理节点更新操作，把目标节点替换为带最新标题/文档/元数据的版本，
     * 并保留决策类节点的 flowchart.kind。其它操作原样返回。
     */
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

    /**
     * 合并目标节点原有元数据与新传入的元数据；若节点本身被识别为决策（DECISION）则强制修正 flowchart.kind，
     * 避免下游渲染把分支节点画成普通流程节点。
     */
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

    /**
     * 在候选没有显式意图、也没有自带 patch 时的兜底合成路径。
     * 说明类候选直接产出注释节点；其它候选尝试匹配目标节点后产出节点更新 patch。
     */
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

    /**
     * 在控制流边上插入一个新的「动作节点」：删除原边、新增动作节点，
     * 并把原边的源/目标分别接到新节点上，从而保持流程图的拓扑连通性。
     */
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

    /**
     * 在控制流边上插入一个新的「决策节点」：删除原边、新增决策节点，
     * 然后从源节点接入决策节点，再从决策节点向原来的目标节点（TRUE 分支）和指定的假分支目标（FALSE 分支）出两条边。
     * 若真假分支落在同一节点上则视为无效，返回 null。
     */
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

    /**
     * 推导候选最终应当聚焦展示的节点集合。
     * 依次考虑：显式意图里指向的节点 / 边端点；patch 操作涉及的节点与边端点；候选自带的 targetNodeIds 与证据引用节点。
     * 全部去重并保留插入顺序，便于 UI 高亮关联节点。
     */
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

    /**
     * 在图谱中定位候选变更应当作用到的目标节点。
     * 综合候选引用的节点、所属方法签名限制与基于标题/控制片段的打分，挑选最匹配的节点。
     * 若打分胜出者相对兜底目标没有显著优势则仍使用兜底目标，避免无意义地"抢节点"。
     */
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

    /**
     * 判断是否应把当前目标节点替换为新打分胜出的节点。
     * 规则：同一节点不替换；分数不占优不替换；意图期望决策节点且胜出者为决策节点时可替换；
     * 否则需胜出分数至少高出阈值才替换，确保替换确实显著。
     */
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

    /**
     * 为单个候选节点打分，分数越高越可能是正确目标。
     * 综合节点是否被直接引用、是否出现在证据中、标题与候选前后态匹配度、控制片段匹配、意图一致性等因素。
     */
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

    /**
     * 构造每个草稿操作都需要的通用元数据：变更 ID 与可选的声明类型，便于下游溯源。
     */
    private fun buildDraftMetadata(candidate: CandidateDraftChange): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        metadata["draft.changeId"] = candidate.changeId
        candidate.claimType?.let { metadata["draft.claimType"] = it }
        return metadata
    }

    /**
     * 取节点所属方法的标识（优先用 flow.ownerMethod 元数据，回退到节点自带的签名），
     * 用于把候选变更限定在同一方法范围内的节点上匹配。
     */
    private fun ownerMethodSignature(node: GraphNode?): String? {
        node ?: return null
        return node.metadata["flow.ownerMethod"] ?: node.signature
    }

    /**
     * 合成"更新已存在节点"的 patch：把候选的修改前/修改后/原因/影响合并进节点文档与标题，
     * 同时打上草稿元数据。若候选没有任何可写入的草稿内容则返回 null。
     */
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

    /**
     * 拼接目标节点更新后的文档：保留原 doc，依次追加修改前/修改后/原因/影响等说明字段。
     * 若最终为空白则返回 null，避免覆盖原有文档。
     */
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

    /**
     * 构造新插入节点（动作/决策/注释）的初始文档，只包含候选的修改前/修改后/原因/影响。
     */
    private fun buildInsertedNodeDoc(candidate: CandidateDraftChange): String? {
        return listOfNotNull(
            candidate.beforeState?.takeIf(String::isNotBlank)?.let { "修改前：$it" },
            candidate.afterState?.takeIf(String::isNotBlank)?.let { "修改后：$it" },
            candidate.reason.takeIf(String::isNotBlank)?.let { "原因：$it" },
            candidate.impactSummary.takeIf(String::isNotBlank)?.let { "影响：$it" },
        ).joinToString("\n").ifBlank { null }
    }

    /**
     * 决定目标节点更新后的展示标题：优先使用显式标题，其次使用候选的 afterState，
     * 都不满足展示规范时回退为节点原标题。
     */
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

    /**
     * 把原始标题归一化为可直接展示的更新标题。
     * 原始文本若符合规范就直接采用；否则尝试从文本中抽取控制流片段作为标题。
     */
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

    /**
     * 从输入文本中匹配出第一个控制流片段（如 `if (x > 0)`），用于决策节点的标题提取。
     */
    private fun extractDisplayableControlFragment(text: String): String? {
        return controlFragmentRegex.find(text)?.value?.trim()
    }

    /**
     * 为新插入的决策节点挑选展示标题：优先候选的 afterState，其次 title，逐个尝试转换为决策标题。
     */
    private fun resolveInsertedDecisionTitle(candidate: CandidateDraftChange): String? {
        return listOfNotNull(candidate.afterState, candidate.title)
            .map(String::trim)
            .firstNotNullOfOrNull(::resolveDisplayableDecisionTitle)
    }

    /**
     * 校验并清洗决策节点的展示标题：去掉过长、含换行或中文标点的文本，
     * 并要求最终文本确实以某个控制关键字开头或能抽出控制片段。
     */
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

    /**
     * 为新插入的动作节点挑选展示标题：优先 afterState，其次 title，取第一个符合可读规范的。
     */
    private fun resolveInsertedActionTitle(candidate: CandidateDraftChange): String? {
        return listOfNotNull(candidate.afterState, candidate.title)
            .map(String::trim)
            .firstOrNull(::isReadableActionTitle)
    }

    /**
     * 判断文本能否作为动作节点的可读标题：非空、长度合理、不含换行与中文标点。
     */
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

    /**
     * 判断一段标题文本能否用作更新后的节点标题。
     * 决策节点要求标题与控制关键字匹配；普通节点则只要求长度合理、不含换行/中文标点。
     */
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

    /**
     * 识别文本开头的控制流关键字（if/switch/while/for/catch/try），用于推断节点是否表达决策或控制流。
     */
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

    /**
     * 构造新插入流程节点的元数据：写入 flowchart 类型与 flow 类型，
     * 并从源/目标节点继承所属方法与锚点方法，再补齐草稿元数据，保证插入节点可被归属到正确的方法上下文。
     */
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

    /**
     * 合成说明性注释节点的无锚点版本：从候选的目标节点或证据引用中找出第一个可用锚点，再调用带锚点的重载。
     * 若找不到任何锚点节点则返回 null。
     */
    private fun synthesizeExplanationNotePatch(candidate: CandidateDraftChange): GraphPatch? {
        val anchorNodeId = candidate.targetNodeIds.firstOrNull()
            ?: candidate.evidence
                .flatMap { finding -> finding.references }
                .firstNotNullOfOrNull { reference -> reference.nodeId?.takeIf(String::isNotBlank) }
        return anchorNodeId?.let { synthesizeExplanationNotePatch(candidate, it) }
    }

    /**
     * 合成挂在指定锚点节点下的注释 patch：新建一个 DOC_PAGE 注释节点，并通过 LINKS_DOC 边连到锚点节点，
     * 用以承载候选的修改前/修改后/原因/影响文本，不改动原图结构。
     */
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

    /**
     * \u63CF\u8FF0\u5019\u9009\u53D8\u66F4\u5728\u9009\u76EE\u6807\u8282\u70B9\u65F6\u7684"\u6253\u5206\u610F\u56FE"\uFF1A\u662F\u5426\u9884\u671F\u5339\u914D\u51B3\u7B56\u8282\u70B9\u3001\u547D\u4E2D\u4E86\u54EA\u4E9B\u63A7\u5236\u6D41\u7247\u6BB5\u3001\u5173\u8054\u7684\u8BCD\u5143\u96C6\u5408\u3002
     * \u7528\u6765\u5728 [scoreTargetNode] \u4E2D\u6309\u573A\u666F\u8C03\u6574\u6743\u91CD\u3002
     */
    private data class TargetResolutionIntent(
        val expectsDecisionNode: Boolean,
        val controlFragments: List<String>,
        val tokens: Set<String>,
    ) {
        companion object {
            // \u7528\u4E8E\u4ECE\u6587\u672C\u4E2D\u62BD\u53D6\u63A7\u5236\u6D41\u7247\u6BB5\u7684\u6B63\u5219\uFF08\u4E0E\u5916\u5C42\u7C7B\u540C\u4E49\uFF09\uFF0C\u7528\u4E8E\u63A8\u65AD\u5019\u9009\u662F\u5426\u4E0E\u51B3\u7B56\u76F8\u5173\u3002
            private val controlFragmentRegex = Regex("""\b(?:if|switch|while|for|do-while|catch)\s*\([^`\n{}]+\)""", RegexOption.IGNORE_CASE)
            // \u7528\u4E8E\u628A\u6587\u672C\u5207\u5206\u4E3A\u8BCD\u5143\u7684\u6B63\u5219\uFF1A\u8BC6\u522B\u82F1\u6587/\u4E0B\u5212\u7EBF\u6807\u8BC6\u7B26\u4E0E\u8FDE\u7EED\u4E2D\u6587\u7247\u6BB5\u3002
            private val tokenRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*|[\u4E00-\u9FFF]{2,}""")

            /**
             * \u628A\u5019\u9009\u4E0E\u5176\u64CD\u4F5C\u6587\u672C\u805A\u5408\uFF0C\u5206\u6790\u51FA\u9884\u671F\u610F\u56FE\uFF1A\u662F\u5426\u671F\u671B\u51B3\u7B56\u8282\u70B9\u3001\u547D\u4E2D\u7684\u63A7\u5236\u7247\u6BB5\u3001\u6240\u6709\u53EF\u6BD4\u8F83\u7684\u8BCD\u5143\u3002
             */
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

            /**
             * \u628A\u6587\u672C\u5207\u5206\u4E3A\u5F52\u4E00\u5316\u7684\u5C0F\u5199\u8BCD\u5143\u96C6\u5408\uFF0C\u4E22\u5F03\u5355\u5B57\u7B26\u566A\u97F3\uFF0C\u4FBF\u4E8E\u8DE8\u8282\u70B9\u6807\u9898\u8BA1\u7B97\u91CD\u53E0\u5EA6\u3002
             */
            private fun tokenize(text: String): Set<String> {
                return tokenRegex.findAll(text)
                    .map { it.value.lowercase() }
                    .filter { it.length > 1 }
                    .toSet()
            }
        }
    }

    private companion object {
        // \u7C7B\u7EA7\u522B\u5171\u4EAB\u7684\u8BCD\u5143\u5207\u5206\u6B63\u5219\uFF1A\u8BC6\u522B\u82F1\u6587/\u4E0B\u5212\u7EBF\u6807\u8BC6\u7B26\u4E0E\u8FDE\u7EED\u4E2D\u6587\u7247\u6BB5\u3002
        private val tokenRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*|[\u4E00-\u9FFF]{2,}""")

        /**
         * \u628A\u6587\u672C\u5207\u5206\u4E3A\u5F52\u4E00\u5316\u7684\u5C0F\u5199\u8BCD\u5143\u96C6\u5408\uFF0C\u4F9B\u8282\u70B9\u6253\u5206\u65F6\u8BA1\u7B97\u6807\u9898\u4E4B\u95F4\u7684\u8BCD\u5143\u91CD\u53E0\u3002
         */
        fun tokenize(text: String): Set<String> {
            return tokenRegex.findAll(text)
                .map { it.value.lowercase() }
                .filter { it.length > 1 }
                .toSet()
        }
    }
}
