package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

class DubboResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .forEach { callExpression ->
                val qualifier = callExpression.methodExpression.qualifierExpression as? PsiReferenceExpression
                    ?: return@forEach
                val field = qualifier.resolve() as? PsiField ?: return@forEach
                if (!ResolverSupport.hasAnyAnnotation(field, setOf("org.apache.dubbo.config.annotation.DubboReference"))) {
                    return@forEach
                }

                val serviceInterface = callExpression.resolveMethod()?.containingClass ?: return@forEach
                val qualifiedName = serviceInterface.qualifiedName ?: return@forEach
                val serviceNode = GraphNode(
                    id = GraphNode.stableId(NodeType.DUBBO_SERVICE, qualifiedName),
                    type = NodeType.DUBBO_SERVICE,
                    title = serviceInterface.name ?: qualifiedName,
                    location = field.containingFile?.virtualFile?.path?.let { "$it:1" },
                    sourceKind = "DUBBO_REFERENCE",
                    metadata = mapOf("serviceInterface" to qualifiedName),
                )
                nodes[serviceNode.id] = serviceNode
                edges[GraphEdge.stableId(EdgeType.USES_PROXY, methodNodeId, serviceNode.id)] = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.USES_PROXY, methodNodeId, serviceNode.id),
                    type = EdgeType.USES_PROXY,
                    fromNodeId = methodNodeId,
                    toNodeId = serviceNode.id,
                )
            }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }
}
