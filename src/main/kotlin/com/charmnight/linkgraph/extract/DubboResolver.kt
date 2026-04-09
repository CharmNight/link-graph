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

/**
 * 解析方法中的 Dubbo 引用关系。
 */
class DubboResolver(
    /** 保存 Java 辅助解析器，用于生成方法稳定键。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /**
     * 扫描方法体中的 DubboReference 调用，并生成代理依赖边。
     */
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        // 没有方法体时无法扫描调用表达式。
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        // 用有序映射去重服务节点和代理边。
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .forEach { callExpression ->
                // Dubbo 调用通常以字段为限定符，这里先取出字段引用。
                val qualifier = callExpression.methodExpression.qualifierExpression as? PsiReferenceExpression
                    ?: return@forEach
                val field = qualifier.resolve() as? PsiField ?: return@forEach
                if (!ResolverSupport.hasAnyAnnotation(field, setOf("org.apache.dubbo.config.annotation.DubboReference"))) {
                    return@forEach
                }

                // 解析出服务接口类型后，构建服务节点。
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
