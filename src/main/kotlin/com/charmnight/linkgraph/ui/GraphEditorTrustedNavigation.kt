package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

/**
 * 构建一个"可信导航"用的节点索引：把多份图文档的所有节点按 ID 合并为一张映射表。
 *
 * 应用场景：用户在不同视图间跳转时，前端希望基于 node id 查到节点对象用于高亮、提示等。
 * 由于视图来源可能不同（事实图、架构图、类图等），这里把若干图全部展开后做合并，
 * 后写入的节点会覆盖同 ID 的旧节点（"最可信"的版本通常排在参数列表后面）。
 *
 * @param graphs 0 到多份可能为 null 的图文档
 * @return 节点 ID 到节点对象的映射
 */
internal fun buildTrustedNavigationNodeIndex(vararg graphs: GraphDocument?): Map<String, GraphNode> {
    return buildMap {
        graphs.asSequence()
            .filterNotNull()
            .flatMap { graph -> graph.nodes.asSequence() }
            .forEach { node -> put(node.id, node) }
    }
}
