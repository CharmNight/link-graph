package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

class SpringResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod): SpringExtraction {
        val ownerClass = method.containingClass ?: return SpringExtraction()
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        addClassWithDependencies(ownerClass, nodes, edges)
        addEndpoint(method, nodes, edges)

        return SpringExtraction(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }

    private fun addClassWithDependencies(
        ownerClass: PsiClass,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        if (!isSpringBean(ownerClass)) {
            return
        }

        val ownerNode = javaResolver.classNode(ownerClass)
        nodes[ownerNode.id] = ownerNode

        ownerClass.constructors
            .flatMap { constructor -> constructor.parameterList.parameters.toList() }
            .mapNotNull(javaResolver::concreteBeanClass)
            .filter(::isSpringBean)
            .forEach { dependency ->
                val dependencyNode = javaResolver.classNode(dependency)
                nodes[dependencyNode.id] = dependencyNode
                val edge = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.INJECT, ownerNode.id, dependencyNode.id),
                    type = EdgeType.INJECT,
                    fromNodeId = ownerNode.id,
                    toNodeId = dependencyNode.id,
                )
                edges[edge.id] = edge
            }
    }

    private fun addEndpoint(
        method: PsiMethod,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        val (httpMethod, path) = ResolverSupport.endpointSignature(method) ?: return
        val endpointNode = ResolverSupport.endpointNode(
            javaResolver = javaResolver,
            method = method,
            httpMethod = httpMethod,
            path = path,
            sourceKind = "SPRING_ENDPOINT",
        )
        nodes[endpointNode.id] = endpointNode

        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val edge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.ROUTES_TO, methodNodeId, endpointNode.id),
            type = EdgeType.ROUTES_TO,
            fromNodeId = methodNodeId,
            toNodeId = endpointNode.id,
        )
        edges[edge.id] = edge
    }

    private fun isSpringBean(psiClass: PsiClass): Boolean {
        return ResolverSupport.hasAnyAnnotation(
            psiClass,
            setOf(
                "org.springframework.stereotype.Controller",
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository",
                "org.springframework.stereotype.Component",
            ),
        )
    }
}

data class SpringExtraction(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
)
