package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

/**
 * 前端图变更消毒器。
 *
 * 前端可能给出"乐观更新"的节点（带位置、临时标题等），但这些字段不一定可信。
 * 本消毒器把已知节点的字段用后端的可信版本覆盖回去，仅保留前端可写的字段
 * （title/inputs/outputs/doc）；新节点则剥离 location/signature 等需要后端验证的字段。
 * 这能避免前端把不合法的源码位置写回工作台图。
 *
 * 标记为 open 以便测试可注入"返回空节点列表 → 视为拒绝写入"的桩实现，
 * 见 GraphEditApplier 对空结果的拒绝处理。
 */
internal open class FrontendGraphMutationSanitizer {
    /**
     * 对一份待写入的图做消毒。
     *
     * @param snapshot 当前快照，提供可信导航索引
     * @param graph 前端传入的待写入图
     * @return 消毒后的图，可安全写回；若结果节点列表为空，调用方应视为拒绝写入
     */
    open fun sanitize(
        snapshot: WorkflowEditorSnapshot,
        graph: GraphDocument,
    ): GraphDocument {
        val trustedNodes = snapshot.trustedNavigationNodes
        return graph.copy(
            nodes = graph.nodes.map { node ->
                // 已知节点：用可信版本覆盖；新节点：剥离不可信字段
                trustedNodes[node.id]
                    ?.let { trustedNode -> sanitizeExistingNode(node, trustedNode) }
                    ?: sanitizeNewNode(node)
            },
        )
    }

    /**
     * 已知节点的消毒：保留前端可写的展示字段，其他字段用可信版本。
     * 这样前端修改标题等字段可以生效，但不会污染签名或位置。
     */
    private fun sanitizeExistingNode(
        node: GraphNode,
        trustedNode: GraphNode,
    ): GraphNode {
        return trustedNode.copy(
            title = node.title,
            inputs = node.inputs,
            outputs = node.outputs,
            doc = node.doc,
        )
    }

    /**
     * 新节点的消毒：剥离 location/signature。
     * 这些字段需要后端通过 PSI 等可信渠道填充，前端不能直接给定。
     */
    private fun sanitizeNewNode(node: GraphNode): GraphNode {
        return node.copy(
            location = null,
            signature = null,
        )
    }
}
