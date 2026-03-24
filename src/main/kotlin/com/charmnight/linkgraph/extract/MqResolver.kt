package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

class MqResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val additionalMethods = linkedSetOf<PsiMethod>()

        val body = method.body
        if (body != null) {
            PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
                .forEach { callExpression ->
                    val topic = topicFromSend(callExpression.argumentList.expressions.firstOrNull()) ?: return@forEach
                    val topicNode = mqTopicNode(topic)
                    nodes[topicNode.id] = topicNode
                    edges[GraphEdge.stableId(EdgeType.PUBLISHES_TO, methodNodeId, topicNode.id)] = GraphEdge(
                        id = GraphEdge.stableId(EdgeType.PUBLISHES_TO, methodNodeId, topicNode.id),
                        type = EdgeType.PUBLISHES_TO,
                        fromNodeId = methodNodeId,
                        toNodeId = topicNode.id,
                    )
                    context.allProjectMethods()
                        .filter { candidate -> topic in ResolverSupport.listenerTopics(candidate) }
                        .forEach(additionalMethods::add)
                }
        }

        ResolverSupport.listenerTopics(method)
            .forEach { topic ->
                val topicNode = mqTopicNode(topic)
                val consumerNode = GraphNode(
                    id = GraphNode.stableId(NodeType.MQ_CONSUMER, javaResolver.methodKey(method)),
                    type = NodeType.MQ_CONSUMER,
                    title = "MQ ${method.containingClass?.name}.${method.name}",
                    location = ResolverSupport.locationOf(method),
                    sourceKind = "MQ_CONSUMER_METHOD",
                    metadata = mapOf("topic" to topic),
                )
                nodes[topicNode.id] = topicNode
                nodes[consumerNode.id] = consumerNode
                edges[GraphEdge.stableId(EdgeType.CONSUMES_FROM, consumerNode.id, topicNode.id)] = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.CONSUMES_FROM, consumerNode.id, topicNode.id),
                    type = EdgeType.CONSUMES_FROM,
                    fromNodeId = consumerNode.id,
                    toNodeId = topicNode.id,
                )
                edges[GraphEdge.stableId(EdgeType.ROUTES_TO, consumerNode.id, methodNodeId)] = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.ROUTES_TO, consumerNode.id, methodNodeId),
                    type = EdgeType.ROUTES_TO,
                    fromNodeId = consumerNode.id,
                    toNodeId = methodNodeId,
                )
            }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
            additionalMethods = additionalMethods.toList(),
        )
    }

    private fun mqTopicNode(topic: String): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.MQ_TOPIC, topic),
            type = NodeType.MQ_TOPIC,
            title = topic,
            sourceKind = "MQ_TOPIC",
            metadata = mapOf("topic" to topic),
        )
    }

    private fun topicFromSend(firstArgument: PsiExpression?): String? {
        return ResolverSupport.literalString(firstArgument)
    }
}
