package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiMethod

/**
 * 负责解析方法与 Markdown 文档之间的关联关系。
 */
class DocResolver(
    /** 保存 Java 辅助解析器，用于生成方法稳定键。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /**
     * 扫描项目中的 Markdown 文档，并为命中的文档生成说明节点和关联边。
     */
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        // 无所属类或类名时无法构造文档引用标识，直接返回空结果。
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val ownerName = ownerClass.qualifiedName ?: return ResolverOutput()
        // 文档中同时支持 `类名#方法名` 和类限定名命中。
        val methodReference = "$ownerName#${method.name}"
        // 方法节点标识需要与主图中的方法节点稳定一致，便于直接连边。
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        // 使用有序映射去重，避免同一文档或同一边重复加入结果。
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        context.allMarkdownFiles().forEach { file ->
            // 只处理正文中实际引用到方法或所属类的文档。
            if (!file.text.contains(methodReference) && !file.text.contains(ownerName)) {
                return@forEach
            }

            // 优先使用虚拟文件路径，缺失时退回文件名，确保位置字段可展示。
            val path = file.virtualFile?.path ?: file.name
            val docNode = GraphNode(
                id = GraphNode.stableId(NodeType.DOC_PAGE, path),
                type = NodeType.DOC_PAGE,
                title = file.name,
                location = "$path:1",
                sourceKind = "MARKDOWN_PAGE",
                metadata = mapOf("path" to path),
            )
            nodes[docNode.id] = docNode
            edges[GraphEdge.stableId(EdgeType.LINKS_DOC, docNode.id, methodNodeId)] = GraphEdge(
                id = GraphEdge.stableId(EdgeType.LINKS_DOC, docNode.id, methodNodeId),
                type = EdgeType.LINKS_DOC,
                fromNodeId = docNode.id,
                toNodeId = methodNodeId,
                metadata = mapOf("reference" to methodReference),
            )
        }

        // 最终返回文档节点和文档到方法的说明边集合。
        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }
}
