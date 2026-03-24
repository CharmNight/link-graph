package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import java.util.ArrayDeque

class GraphExtractor(
    private val javaResolver: JavaResolver = JavaResolver(),
    private val springResolver: SpringResolver = SpringResolver(javaResolver),
    private val myBatisResolver: MyBatisResolver = MyBatisResolver(javaResolver),
    private val httpFeignResolver: HttpFeignResolver = HttpFeignResolver(javaResolver),
    private val dubboResolver: DubboResolver = DubboResolver(javaResolver),
    private val mqResolver: MqResolver = MqResolver(javaResolver),
    private val configResolver: ConfigResolver = ConfigResolver(javaResolver),
    private val docResolver: DocResolver = DocResolver(javaResolver),
    private val uncertainLinkResolver: UncertainLinkResolver = UncertainLinkResolver(javaResolver),
    private val methodFlowBuilder: MethodFlowBuilder = MethodFlowBuilder(),
) {
    fun extract(request: GraphExtractionRequest): GraphExtractionResult {
        if (request.entryMethods.isEmpty()) {
            return GraphExtractionResult(GraphDocument())
        }

        val resolverContext = ResolverContext(request.entryMethods.first().project)
        val graph = MutableGraphDocument()
        val pending = ArrayDeque(request.entryMethods)
        val visitedMethods = linkedSetOf<String>()

        while (pending.isNotEmpty()) {
            val method = pending.removeFirst()
            val methodKey = javaResolver.methodKey(method)
            if (!visitedMethods.add(methodKey)) {
                continue
            }

            graph.addNode(javaResolver.methodNode(method, methodFlowBuilder.build(method)))

            val spring = springResolver.resolve(method)
            spring.nodes.forEach(graph::addNode)
            spring.edges.forEach(graph::addEdge)

            listOf(
                myBatisResolver.resolve(method, resolverContext),
                httpFeignResolver.resolve(method, resolverContext),
                dubboResolver.resolve(method, resolverContext),
                mqResolver.resolve(method, resolverContext),
                configResolver.resolve(method, resolverContext),
                docResolver.resolve(method, resolverContext),
                uncertainLinkResolver.resolve(method, resolverContext),
            ).forEach { output ->
                output.nodes.forEach(graph::addNode)
                output.edges.forEach(graph::addEdge)
                pending += output.additionalMethods
            }

            javaResolver.resolveCalls(method).forEach { resolvedCall ->
                graph.addNode(javaResolver.methodNode(resolvedCall.target, methodFlowBuilder.build(resolvedCall.target)))
                graph.addEdge(javaResolver.callEdge(method, resolvedCall.target))
                resolvedCall.implementationEdge?.let { implementation ->
                    graph.addNode(javaResolver.classNode(implementation.implementationClass))
                    graph.addNode(javaResolver.classNode(implementation.contractClass))
                    graph.addEdge(
                        javaResolver.implementsEdge(
                            implementationClass = implementation.implementationClass,
                            contractClass = implementation.contractClass,
                        ),
                    )
                }
                pending += resolvedCall.target
            }
        }

        return GraphExtractionResult(graph.toDocument())
    }
}

private class MutableGraphDocument {
    private val nodes = linkedMapOf<String, GraphNode>()
    private val edges = linkedMapOf<String, GraphEdge>()

    fun addNode(node: GraphNode) {
        nodes[node.id] = node
    }

    fun addEdge(edge: GraphEdge) {
        edges[edge.id] = edge
    }

    fun toDocument(): GraphDocument {
        return GraphDocument(
            nodes = nodes.values.sortedBy { it.id },
            edges = edges.values.sortedBy { it.id },
        )
    }
}
