package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

class HttpFeignResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val additionalMethods = linkedSetOf<PsiMethod>()

        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .forEach { callExpression ->
                val resolvedMethod = callExpression.resolveMethod() ?: return@forEach
                val ownerClass = resolvedMethod.containingClass ?: return@forEach
                val feignAnnotation = ResolverSupport.findAnnotation(
                    ownerClass,
                    setOf("org.springframework.cloud.openfeign.FeignClient"),
                ) ?: return@forEach

                val httpMethod = ResolverSupport.requestMethod(resolvedMethod) ?: return@forEach
                val path = ResolverSupport.combinePaths(
                    ResolverSupport.annotationString(feignAnnotation, "path", "value"),
                    ResolverSupport.requestPath(resolvedMethod),
                ) ?: return@forEach
                val clientClass = ownerClass.qualifiedName ?: return@forEach

                val feignNode = GraphNode(
                    id = GraphNode.stableId(NodeType.FEIGN_CLIENT, javaResolver.methodKey(resolvedMethod)),
                    type = NodeType.FEIGN_CLIENT,
                    title = "${ownerClass.name}.${resolvedMethod.name}",
                    location = ResolverSupport.locationOf(resolvedMethod),
                    sourceKind = "FEIGN_CLIENT_METHOD",
                    metadata = mapOf(
                        "clientClass" to clientClass,
                        "httpMethod" to httpMethod,
                        "path" to path,
                    ),
                )
                nodes[feignNode.id] = feignNode

                val endpointNode = ResolverSupport.endpointNode(
                    httpMethod = httpMethod,
                    path = path,
                    location = feignNode.location,
                    sourceKind = "FEIGN_TARGET",
                )
                nodes[endpointNode.id] = endpointNode

                edges[GraphEdge.stableId(EdgeType.USES_PROXY, methodNodeId, feignNode.id)] = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.USES_PROXY, methodNodeId, feignNode.id),
                    type = EdgeType.USES_PROXY,
                    fromNodeId = methodNodeId,
                    toNodeId = feignNode.id,
                )
                edges[GraphEdge.stableId(EdgeType.ROUTES_TO, feignNode.id, endpointNode.id)] = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.ROUTES_TO, feignNode.id, endpointNode.id),
                    type = EdgeType.ROUTES_TO,
                    fromNodeId = feignNode.id,
                    toNodeId = endpointNode.id,
                )

                context.allProjectMethods()
                    .filter { candidate -> ResolverSupport.endpointSignature(candidate) == httpMethod to path }
                    .forEach(additionalMethods::add)
            }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
            additionalMethods = additionalMethods.toList(),
        )
    }
}
