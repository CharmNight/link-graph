package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import java.util.ArrayDeque

/**
 * 以入口方法为起点构建“事实图”。
 * 这里负责调度所有跨框架 resolver，并把它们补出的节点、边和后续待展开方法合并到同一张图里。
 */
open class GraphExtractor(
    private val javaResolver: JavaResolver = JavaResolver(),
    private val springResolver: SpringResolver = SpringResolver(javaResolver),
    private val springEventResolver: SpringEventResolver = SpringEventResolver(javaResolver),
    private val myBatisResolver: MyBatisResolver = MyBatisResolver(javaResolver),
    private val httpFeignResolver: HttpFeignResolver = HttpFeignResolver(javaResolver),
    private val dubboResolver: DubboResolver = DubboResolver(javaResolver),
    private val mqResolver: MqResolver = MqResolver(javaResolver),
    private val configResolver: ConfigResolver = ConfigResolver(javaResolver),
    private val docResolver: DocResolver = DocResolver(javaResolver),
    private val uncertainLinkResolver: UncertainLinkResolver = UncertainLinkResolver(javaResolver),
    private val methodFlowBuilder: MethodFlowBuilder = MethodFlowBuilder(),
) {
    open fun extract(request: GraphExtractionRequest): GraphExtractionResult {
        if (request.entryMethods.isEmpty()) {
            return GraphExtractionResult(GraphDocument())
        }

        val limits = request.limits.sanitized()
        val resolverContext = ResolverContext(request.entryMethods.first().project)
        val graph = MutableGraphDocument()
        val state = ExtractionState(
            graph = graph,
            javaResolver = javaResolver,
            methodFlowBuilder = methodFlowBuilder,
            limits = limits,
        )
        val downstreamPending = ArrayDeque<TraversalTask>()
        val upstreamPending = ArrayDeque<TraversalTask>()
        val processedDownstream = linkedSetOf<String>()
        val processedUpstream = linkedSetOf<String>()
        val entryMethodKeys = request.entryMethods.map(javaResolver::methodKey).toSet()
        val entryMethodBoundaries = linkedMapOf<String, ExtractionBoundary>()

        request.entryMethods
            .sortedBy(javaResolver::methodKey)
            .forEach { entryMethod ->
                state.reserveMethod(entryMethod)
                state.addMethodNode(entryMethod)
                downstreamPending += TraversalTask(entryMethod, depth = 0)
                upstreamPending += TraversalTask(entryMethod, depth = 0)
            }

        while (downstreamPending.isNotEmpty()) {
            val task = downstreamPending.removeFirst()
            val methodKey = javaResolver.methodKey(task.method)
            if (!processedDownstream.add(methodKey)) {
                continue
            }

            val method = task.method
            state.addMethodNode(method)

            val spring = springResolver.resolve(method)
            spring.nodes.forEach(state::addNode)
            spring.edges.forEach(state::addEdge)

            val additionalOutputs = listOf(
                myBatisResolver.resolve(method, resolverContext),
                httpFeignResolver.resolve(method, resolverContext),
                dubboResolver.resolve(method, resolverContext),
                mqResolver.resolve(method, resolverContext),
                configResolver.resolve(method, resolverContext),
                docResolver.resolve(method, resolverContext),
                springEventResolver.resolve(method, resolverContext),
                uncertainLinkResolver.resolve(method, resolverContext),
            )

            additionalOutputs.forEach { output ->
                processResolverOutput(
                    method = method,
                    depth = task.depth,
                    output = output,
                    pending = downstreamPending,
                    state = state,
                )
            }

            if (task.depth >= limits.maxCallDepthDownstream) {
                continue
            }

            val resolvedFlow = javaResolver.resolveCallGraph(method)
            if (methodKey in entryMethodKeys) {
                resolvedFlow.boundary?.let { boundary ->
                    entryMethodBoundaries.putIfAbsent(methodKey, boundary)
                }
            }
            resolvedFlow.scopeNodes.forEach(state::addNode)
            resolvedFlow.containmentEdges.forEach(state::addEdge)
            resolvedFlow.actionNodes.forEach(state::addNode)
            resolvedFlow.actionEdges.forEach(state::addEdge)
            val visibleCalls = resolvedFlow.calls.take(limits.maxCallsPerMethod)
            var hiddenCallCount = resolvedFlow.calls.size - visibleCalls.size

            visibleCalls.forEach { resolvedCall ->
                if (!state.reserveMethod(resolvedCall.target)) {
                    hiddenCallCount += 1
                    return@forEach
                }
                state.addMethodNode(resolvedCall.target)
                state.addEdge(
                    javaResolver.callEdge(
                        fromNodeId = resolvedCall.sourceNodeId,
                        toMethod = resolvedCall.target,
                        callOrder = resolvedCall.callOrder,
                    ),
                )
                resolvedCall.implementationEdge?.let { implementation ->
                    state.addNode(javaResolver.classNode(implementation.implementationClass))
                    state.addNode(javaResolver.classNode(implementation.contractClass))
                    state.addEdge(
                        javaResolver.implementsEdge(
                            implementationClass = implementation.implementationClass,
                            contractClass = implementation.contractClass,
                        ),
                    )
                }
                downstreamPending += TraversalTask(resolvedCall.target, depth = task.depth + 1)
            }

            if (hiddenCallCount > 0) {
                state.addDownstreamOverflow(method, hiddenCallCount, "下游调用过多")
            }
        }

        while (upstreamPending.isNotEmpty()) {
            val task = upstreamPending.removeFirst()
            val methodKey = javaResolver.methodKey(task.method)
            if (!processedUpstream.add(methodKey)) {
                continue
            }
            if (task.depth >= limits.maxCallDepthUpstream) {
                continue
            }

            val callers = javaResolver.resolveCallers(
                task.method,
                limit = limits.maxCallersPerMethod + 1,
            )
                .sortedBy(javaResolver::methodKey)
            val visibleCallers = callers.take(limits.maxCallersPerMethod)
            var hiddenCallerCount = callers.size - visibleCallers.size

            visibleCallers.forEach { caller ->
                if (!state.reserveMethod(caller)) {
                    hiddenCallerCount += 1
                    return@forEach
                }
                state.addMethodNode(caller)
                state.addEdge(javaResolver.callEdge(caller, task.method))
                upstreamPending += TraversalTask(caller, depth = task.depth + 1)
            }

            if (hiddenCallerCount > 0) {
                state.addUpstreamOverflow(task.method, hiddenCallerCount, "上游调用方过多")
            }
        }

        return GraphExtractionResult(
            document = graph.toDocument(),
            entryMethodBoundaries = entryMethodBoundaries.toMap(),
        )
    }

    private fun processResolverOutput(
        method: com.intellij.psi.PsiMethod,
        depth: Int,
        output: ResolverOutput,
        pending: ArrayDeque<TraversalTask>,
        state: ExtractionState,
    ) {
        val visibleAdditionalMethodIds = linkedSetOf<String>()
        val additionalMethods = output.additionalMethods
            .distinctBy(javaResolver::methodKey)
            .sortedBy(javaResolver::methodKey)
        val queuedAdditionalMethods = if (depth < state.limits.maxCallDepthDownstream) {
            additionalMethods.take(state.limits.maxResolverMethodsPerMethod)
        } else {
            emptyList()
        }
        var hiddenAdditionalMethodCount = additionalMethods.size - queuedAdditionalMethods.size

        queuedAdditionalMethods.forEach { additionalMethod ->
            if (!state.reserveMethod(additionalMethod)) {
                hiddenAdditionalMethodCount += 1
                return@forEach
            }
            val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(additionalMethod))
            visibleAdditionalMethodIds += methodNodeId
            pending += TraversalTask(additionalMethod, depth = depth + 1)
        }

        val hiddenMethodNodeIds = additionalMethods
            .map { additionalMethod -> GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(additionalMethod)) }
            .filterNot { nodeId -> nodeId in visibleAdditionalMethodIds || state.hasReservedMethodNode(nodeId) }
            .toSet()

        output.nodes
            .filter { node -> node.type != NodeType.METHOD || node.id in visibleAdditionalMethodIds || state.hasReservedMethodNode(node.id) }
            .forEach(state::addNode)
        output.edges
            .filter { edge -> edge.fromNodeId !in hiddenMethodNodeIds && edge.toNodeId !in hiddenMethodNodeIds }
            .forEach(state::addEdge)

        if (hiddenAdditionalMethodCount > 0) {
            state.addDownstreamOverflow(method, hiddenAdditionalMethodCount, "跨框架扩展过多")
        }
    }
}

private data class TraversalTask(
    val method: com.intellij.psi.PsiMethod,
    val depth: Int,
)

private class ExtractionState(
    private val graph: MutableGraphDocument,
    private val javaResolver: JavaResolver,
    private val methodFlowBuilder: MethodFlowBuilder,
    val limits: GraphExtractionLimits,
) {
    private val reservedMethodNodeIds = linkedSetOf<String>()

    fun reserveMethod(method: com.intellij.psi.PsiMethod): Boolean {
        val nodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        if (nodeId in reservedMethodNodeIds) {
            return true
        }
        if (reservedMethodNodeIds.size >= limits.maxMethodNodes) {
            return false
        }
        reservedMethodNodeIds += nodeId
        return true
    }

    fun hasReservedMethodNode(nodeId: String): Boolean = nodeId in reservedMethodNodeIds

    fun addMethodNode(method: com.intellij.psi.PsiMethod) {
        addNode(javaResolver.methodNode(method, methodFlowBuilder.build(method)))
    }

    fun addNode(node: GraphNode) {
        if (node.type == NodeType.METHOD) {
            reservedMethodNodeIds += node.id
        }
        graph.addNode(node)
    }

    fun addEdge(edge: GraphEdge) {
        graph.addEdge(edge)
    }

    fun addDownstreamOverflow(
        method: com.intellij.psi.PsiMethod,
        hiddenCount: Int,
        titlePrefix: String,
    ) {
        addOverflow(
            method = method,
            hiddenCount = hiddenCount,
            titlePrefix = titlePrefix,
            edgeFromOverflowToMethod = false,
        )
    }

    fun addUpstreamOverflow(
        method: com.intellij.psi.PsiMethod,
        hiddenCount: Int,
        titlePrefix: String,
    ) {
        addOverflow(
            method = method,
            hiddenCount = hiddenCount,
            titlePrefix = titlePrefix,
            edgeFromOverflowToMethod = true,
        )
    }

    private fun addOverflow(
        method: com.intellij.psi.PsiMethod,
        hiddenCount: Int,
        titlePrefix: String,
        edgeFromOverflowToMethod: Boolean,
    ) {
        if (hiddenCount <= 0) {
            return
        }
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val overflowNode = GraphNode(
            id = GraphNode.stableId(
                NodeType.UNCERTAIN_LINK,
                "${javaResolver.methodKey(method)}-$titlePrefix",
                "extract-overflow",
            ),
            type = NodeType.UNCERTAIN_LINK,
            title = "$titlePrefix，已折叠 $hiddenCount 个方法",
            signature = "$hiddenCount 个方法未继续展开",
            doc = "静态提图已在提取阶段做安全限界，避免高扇入/高扇出链路导致界面卡死。请以该节点作为“还有未展开链路”的明确标识。",
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.PARTIALLY_SYNCED,
            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            metadata = mapOf(
                "linkGraph.overflow.hiddenMethodCount" to hiddenCount.toString(),
                "linkGraph.overflow.titlePrefix" to titlePrefix,
            ),
        )
        addNode(overflowNode)
        addEdge(
            GraphEdge(
                id = GraphEdge.stableId(
                    EdgeType.CALL,
                    if (edgeFromOverflowToMethod) overflowNode.id else methodNodeId,
                    if (edgeFromOverflowToMethod) methodNodeId else overflowNode.id,
                    "extract-overflow",
                ),
                type = EdgeType.CALL,
                fromNodeId = if (edgeFromOverflowToMethod) overflowNode.id else methodNodeId,
                toNodeId = if (edgeFromOverflowToMethod) methodNodeId else overflowNode.id,
                label = "还有 $hiddenCount 个方法",
                certainty = Certainty.RULE_INFERRED,
                bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            ),
        )
    }
}

/**
 * 采用稳定 ID 去重，避免多个 resolver 对同一节点/边重复入图。
 */
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
