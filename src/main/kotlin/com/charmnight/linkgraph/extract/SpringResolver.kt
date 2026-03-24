package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
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
        val ownerClass = method.containingClass ?: return
        if (!isController(ownerClass)) {
            return
        }

        val httpMethod = requestMethod(method) ?: return
        val path = combinePaths(requestPath(ownerClass), requestPath(method)) ?: return
        val endpointNode = GraphNode(
            id = GraphNode.stableId(NodeType.HTTP_ENDPOINT, "$httpMethod $path"),
            type = NodeType.HTTP_ENDPOINT,
            title = "$httpMethod $path",
            location = javaResolver.methodNode(method).location,
            sourceKind = "SPRING_ENDPOINT",
            metadata = mapOf(
                "path" to path,
                "httpMethod" to httpMethod,
            ),
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
        return hasAnyAnnotation(
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

    private fun isController(psiClass: PsiClass): Boolean {
        return hasAnyAnnotation(
            psiClass,
            setOf(
                "org.springframework.stereotype.Controller",
                "org.springframework.web.bind.annotation.RestController",
            ),
        )
    }

    private fun requestMethod(method: PsiMethod): String? {
        return when {
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.GetMapping")) -> "GET"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PostMapping")) -> "POST"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PutMapping")) -> "PUT"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.DeleteMapping")) -> "DELETE"
            else -> null
        }
    }

    private fun requestPath(owner: PsiClass): String? {
        return owner.annotations
            .firstOrNull { annotation ->
                annotationName(annotation) == "org.springframework.web.bind.annotation.RequestMapping"
            }
            ?.let(::annotationPath)
    }

    private fun requestPath(method: PsiMethod): String? {
        return method.annotations
            .firstOrNull { annotation ->
                annotationName(annotation) in setOf(
                    "org.springframework.web.bind.annotation.RequestMapping",
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.PostMapping",
                    "org.springframework.web.bind.annotation.PutMapping",
                    "org.springframework.web.bind.annotation.DeleteMapping",
                )
            }
            ?.let(::annotationPath)
    }

    private fun annotationPath(annotation: PsiAnnotation): String? {
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        val literalValue = (value as? PsiLiteralExpression)?.value as? String
        return literalValue ?: value?.text?.trim('"')
    }

    private fun combinePaths(classPath: String?, methodPath: String?): String? {
        val methodPart = methodPath ?: return null
        val parts = listOfNotNull(classPath, methodPart)
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
        return "/" + parts.joinToString("/")
    }

    private fun hasAnyAnnotation(owner: com.intellij.psi.PsiModifierListOwner, names: Set<String>): Boolean {
        return owner.annotations.any { annotation ->
            val name = annotationName(annotation)
            name in names || name.substringAfterLast('.') in names.map { it.substringAfterLast('.') }.toSet()
        }
    }

    private fun annotationName(annotation: PsiAnnotation): String {
        return annotation.qualifiedName ?: annotation.text.removePrefix("@").substringBefore("(")
    }
}

data class SpringExtraction(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
)
