package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/**
 * 解析 Spring 组件之间的依赖关系与 HTTP endpoint 关系。
 * 当前主要覆盖构造器注入和控制器方法到路由节点的映射。
 */
class SpringResolver(
    /** 负责把 PSI 中的类和方法转换为图节点的 Java 解析器。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /** 基于方法入口解析所属 Spring 类的节点和边。 */
    fun resolve(method: PsiMethod): SpringExtraction {
        /** 当前方法所在的类。 */
        val ownerClass = method.containingClass ?: return SpringExtraction()
        /** 本次解析收集到的节点映射。 */
        val nodes = linkedMapOf<String, GraphNode>()
        /** 本次解析收集到的边映射。 */
        val edges = linkedMapOf<String, GraphEdge>()

        addClassWithDependencies(ownerClass, nodes, edges)
        addEndpoint(method, nodes, edges)

        return SpringExtraction(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
        )
    }

    /** 收集 Spring Bean 与其构造器依赖之间的注入关系。 */
    private fun addClassWithDependencies(
        ownerClass: PsiClass,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        if (!isSpringBean(ownerClass)) {
            return
        }

        /** 当前类对应的图节点。 */
        val ownerNode = javaResolver.classNode(ownerClass)
        /** 标记是否至少生成了一条依赖边，避免留下孤立类节点。 */
        var hasDependencyEdge = false

        ownerClass.constructors
            .flatMap { constructor -> constructor.parameterList.parameters.toList() }
            .mapNotNull(javaResolver::concreteBeanClass)
            .filter(::isSpringBean)
            .forEach { dependency ->
                /** 构造器参数解析出的依赖节点。 */
                val dependencyNode = javaResolver.classNode(dependency)
                nodes[ownerNode.id] = ownerNode
                nodes[dependencyNode.id] = dependencyNode
                /** 从宿主 Bean 指向依赖 Bean 的注入边。 */
                val edge = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.INJECT, ownerNode.id, dependencyNode.id),
                    type = EdgeType.INJECT,
                    fromNodeId = ownerNode.id,
                    toNodeId = dependencyNode.id,
                )
                edges[edge.id] = edge
                hasDependencyEdge = true
            }

        if (!hasDependencyEdge) {
            nodes.remove(ownerNode.id)
        }
    }

    /** 为控制器方法补充 HTTP endpoint 节点和路由边。 */
    private fun addEndpoint(
        method: PsiMethod,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        /** 方法解析得到的 HTTP 动词和路径。 */
        val (httpMethod, path) = ResolverSupport.endpointSignature(method) ?: return
        /** 当前方法对应的 endpoint 节点。 */
        val endpointNode = ResolverSupport.endpointNode(
            javaResolver = javaResolver,
            method = method,
            httpMethod = httpMethod,
            path = path,
            sourceKind = "SPRING_ENDPOINT",
        )
        nodes[endpointNode.id] = endpointNode

        /** 当前方法节点的稳定 ID。 */
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        /** 方法指向路由节点的边。 */
        val edge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.ROUTES_TO, methodNodeId, endpointNode.id),
            type = EdgeType.ROUTES_TO,
            fromNodeId = methodNodeId,
            toNodeId = endpointNode.id,
        )
        edges[edge.id] = edge
    }

    /** 判断类是否属于常见的 Spring 托管组件。 */
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

/** Spring 解析阶段输出的节点与边集合。 */
data class SpringExtraction(
    /** 解析得到的节点列表。 */
    val nodes: List<GraphNode> = emptyList(),
    /** 解析得到的边列表。 */
    val edges: List<GraphEdge> = emptyList(),
)
