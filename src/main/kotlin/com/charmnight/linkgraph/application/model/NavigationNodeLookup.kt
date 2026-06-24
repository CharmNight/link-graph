package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphNode

/**
 * 在工作台快照涉及的多份图中按节点 ID 查找节点。
 *
 * 查找顺序与可见性优先级一致：
 * 当前可见图 → 当前工作图 → 语义事实图 → 工作台基线图 → 设计基线图。
 * 先命中先返回，保证用户能跳转到最"权威"的版本。
 *
 * @param snapshot 工作台编辑器快照，包含多份候选图
 * @param nodeId 待查找的节点 ID
 * @return 找到的节点；不存在时返回 null
 */
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

/**
 * 通过预构建的可信导航索引查找节点。
 *
 * 与 [findNavigationNode] 不同，本函数直接读快照中已经合并好的索引表，
 * 不再做实时遍历，性能更稳定，适合在热路径上使用。
 *
 * @param snapshot 工作台编辑器快照
 * @param nodeId 待查找的节点 ID
 * @return 找到的节点；不存在时返回 null
 */
internal fun findTrustedNavigationNode(
    snapshot: com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? = snapshot.trustedNavigationNodes[nodeId]
