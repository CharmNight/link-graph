package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiMethod

class DocResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val ownerClass = method.containingClass ?: return ResolverOutput()
        val ownerName = ownerClass.qualifiedName ?: return ResolverOutput()
        val methodReference = "$ownerName#${method.name}"
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        context.allMarkdownFiles().forEach { file ->
            if (!file.text.contains(methodReference) && !file.text.contains(ownerName)) {
                return@forEach
            }

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

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }
}
