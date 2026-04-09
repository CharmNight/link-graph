package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * 解析方法中的消息发送与消费关系。
 * 当前主要识别常见 MQ 发送方法和监听注解，把它们转换为主题、消费者及路由边。
 */
class MqResolver(
    /** 负责把 PSI 方法转换为图节点标识的 Java 解析器。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /** 基于方法内容解析 MQ 主题、生产关系和消费关系。 */
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        /** 当前方法节点的稳定 ID。 */
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        /** 本次解析得到的节点集合。 */
        val nodes = linkedMapOf<String, GraphNode>()
        /** 本次解析得到的边集合。 */
        val edges = linkedMapOf<String, GraphEdge>()
        /** 为了继续扩展链路需要补充分析的方法集合。 */
        val additionalMethods = linkedSetOf<PsiMethod>()

        /** 当前方法体，用于扫描发送调用。 */
        val body = method.body
        if (body != null) {
            PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
                .forEach { callExpression ->
                    /** 当前发送调用解析出的 topic。 */
                    val topic = topicFromSend(callExpression) ?: return@forEach
                    /** topic 对应的主题节点。 */
                    val topicNode = mqTopicNode(topic)
                    nodes[topicNode.id] = topicNode
                    edges[GraphEdge.stableId(EdgeType.PUBLISHES_TO, methodNodeId, topicNode.id)] = GraphEdge(
                        id = GraphEdge.stableId(EdgeType.PUBLISHES_TO, methodNodeId, topicNode.id),
                        type = EdgeType.PUBLISHES_TO,
                        fromNodeId = methodNodeId,
                        toNodeId = topicNode.id,
                    )
                    /** 项目中监听该 topic 的方法集合。 */
                    context.allProjectMethods()
                        .filter { candidate -> topic in ResolverSupport.listenerTopics(candidate) }
                        .forEach(additionalMethods::add)
                }
        }

        ResolverSupport.listenerTopics(method)
            .forEach { topic ->
                /** topic 对应的主题节点。 */
                val topicNode = mqTopicNode(topic)
                /** 当前监听方法对应的消费者节点。 */
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

    /** 构造 MQ 主题节点。 */
    private fun mqTopicNode(topic: String): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.MQ_TOPIC, topic),
            type = NodeType.MQ_TOPIC,
            title = topic,
            sourceKind = "MQ_TOPIC",
            metadata = mapOf("topic" to topic),
        )
    }

    /**
     * 只把典型 MQ 发送方法的首个字符串字面量参数当作 topic。
     * 避免把普通 setter / builder / 工厂方法的第一个参数误识别成 MQ 主题。
     */
    private fun topicFromSend(callExpression: PsiMethodCallExpression): String? {
        /** 当前调用的方法名。 */
        val methodName = callExpression.methodExpression.referenceName ?: return null
        if (methodName !in MQ_SEND_METHOD_NAMES) {
            return null
        }
        /** 发送方法的第一个参数，约定为 topic。 */
        val firstArgument = callExpression.argumentList.expressions.firstOrNull() as? PsiLiteralExpression ?: return null
        return firstArgument.value as? String
    }

    private companion object {
        /** 视为 MQ 发送动作的方法名集合。 */
        private val MQ_SEND_METHOD_NAMES = setOf(
            "send",
            "syncSend",
            "asyncSend",
            "sendOneWay",
            "convertAndSend",
        )
    }
}
