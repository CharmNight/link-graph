package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * 解析 Spring Event 发布/监听链路。
 * 这里只做静态类型匹配；如果无法完全确认，也会保留事件边界节点并标记为不确定事实，避免链路中断。
 */
class SpringEventResolver(
    /** 保存 Java 辅助解析器，用于生成方法稳定键。 */
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    /**
     * 解析方法中的 Spring Event 发布与监听链路。
     */
    fun resolve(
        method: PsiMethod,
        context: ResolverContext,
    ): ResolverOutput {
        // 没有方法体时无法识别 publishEvent 调用。
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        // 用有序映射和集合去重节点、边和补充方法。
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val additionalMethods = linkedSetOf<PsiMethod>()

        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .forEach { call ->
                // 先识别是否是 Spring Event 发布调用。
                if (!looksLikeSpringEventPublish(call)) {
                    return@forEach
                }
                // 解析出事件类型后构造事件边界节点。
                val eventType = resolveEventType(call.argumentList.expressions.firstOrNull()) ?: return@forEach
                val eventNode = eventBoundaryNode(method, eventType)
                nodes[eventNode.id] = eventNode

                // 从当前方法连向事件节点，表示发布动作。
                val publishEdge = GraphEdge(
                    id = GraphEdge.stableId(EdgeType.PUBLISHES_TO, methodNodeId, eventNode.id, "spring-event"),
                    type = EdgeType.PUBLISHES_TO,
                    fromNodeId = methodNodeId,
                    toNodeId = eventNode.id,
                    certainty = Certainty.RULE_INFERRED,
                    uncertainty = GraphUncertainty(reason = "Spring Event publish inferred from publishEvent(...)"),
                    sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                )
                edges[publishEdge.id] = publishEdge

                findEventListeners(context, eventType).forEach { listener ->
                    // 命中监听器后补入监听方法节点和消费边。
                    val listenerNode = javaResolver.methodNode(listener)
                    nodes[listenerNode.id] = listenerNode
                    additionalMethods += listener
                    val consumeEdge = GraphEdge(
                        id = GraphEdge.stableId(EdgeType.CONSUMES_FROM, eventNode.id, listenerNode.id, "spring-event"),
                        type = EdgeType.CONSUMES_FROM,
                        fromNodeId = eventNode.id,
                        toNodeId = listenerNode.id,
                        certainty = Certainty.RULE_INFERRED,
                        uncertainty = GraphUncertainty(reason = "Spring Event listener matched by parameter type"),
                        sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                    )
                    edges[consumeEdge.id] = consumeEdge
                }
            }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
            additionalMethods = additionalMethods.toList(),
        )
    }

    /**
     * 判断一个方法调用是否像 Spring Event 发布。
     */
    private fun looksLikeSpringEventPublish(call: PsiMethodCallExpression): Boolean {
        if (call.methodExpression.referenceName != "publishEvent") {
            return false
        }
        // 既支持按类型判断，也兼容变量名包含 publisher 的弱匹配。
        val qualifierType = call.methodExpression.qualifierExpression?.type?.canonicalText.orEmpty()
        return qualifierType.contains("ApplicationEventPublisher") ||
            qualifierType.contains("ApplicationEventMulticaster") ||
            call.methodExpression.qualifierExpression?.text?.contains("publisher", ignoreCase = true) == true
    }

    /**
     * 解析 publishEvent 调用中的事件类型。
     */
    private fun resolveEventType(expression: PsiExpression?): String? {
        expression ?: return null
        return when (expression) {
            is PsiNewExpression -> expression.classReference?.qualifiedName ?: expression.classReference?.referenceName
            else -> expression.type?.canonicalText
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * 在项目方法中查找匹配事件类型的监听器。
     */
    private fun findEventListeners(
        context: ResolverContext,
        eventType: String,
    ): List<PsiMethod> {
        // 事件类型统一标准化后再与监听器参数类型比较。
        val normalizedEventType = normalizeEventType(eventType)
        return context.allProjectMethods()
            .asSequence()
            .filter { method ->
                ResolverSupport.hasAnyAnnotation(
                    method,
                    setOf("org.springframework.context.event.EventListener"),
                )
            }
            .filter { method ->
                method.parameterList.parameters.any { parameter ->
                    normalizeEventType(parameter.type.canonicalText) == normalizedEventType
                }
            }
            .distinctBy { method -> javaResolver.methodKey(method) }
            .sortedBy { method -> javaResolver.methodKey(method) }
            .toList()
    }

    /**
     * 构造事件边界节点。
     */
    private fun eventBoundaryNode(
        method: PsiMethod,
        eventType: String,
    ): GraphNode {
        // 节点标题使用简单类名，签名和元数据保留完整事件类型。
        val simpleName = eventType.substringAfterLast('.')
        return GraphNode(
            id = GraphNode.stableId(NodeType.UNCERTAIN_LINK, eventType, "spring-event"),
            type = NodeType.UNCERTAIN_LINK,
            title = "SpringEvent.$simpleName",
            location = ResolverSupport.locationOf(method),
            signature = eventType,
            doc = "Spring Event 边界：$simpleName",
            sourceKind = "SPRING_EVENT",
            certainty = Certainty.RULE_INFERRED,
            uncertainty = GraphUncertainty(reason = "Spring Event dispatch resolved by static type match"),
            metadata = mapOf("eventType" to eventType),
            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
        )
    }

    /**
     * 标准化事件类型文本。
     */
    private fun normalizeEventType(type: String): String {
        return type.removeSuffix("?").trim()
    }
}
