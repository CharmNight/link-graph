package com.charmnight.linkgraph.application.debug

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType

/**
 * 表示一张可用于调试或压测的示例图定义。
 */
internal data class DebugGraphDefinition(
    /** 保存调试图本体。 */
    val graph: GraphDocument,
    /** 保存锚点方法签名。 */
    val anchorSignature: String,
    /** 保存调试图摘要说明。 */
    val summary: String,
)

/**
 * 负责构造内置调试图。
 */
internal class DebugGraphFactory {
    /**
     * 表示单个调试方法节点的定义。
     */
    private data class DebugMethodSpec(
        /** 保存方法签名。 */
        val signature: String,
        /** 保存展示标题。 */
        val title: String,
        /** 保存节点说明。 */
        val doc: String,
    )

    /**
     * 根据调试模式创建对应的调试图。
     */
    fun create(mode: String): DebugGraphDefinition? {
        return when {
            mode == "wide19" -> buildWideDebugGraph()
            parseDenseDebugNodeCount(mode) != null -> buildDenseDebugGraph(parseDenseDebugNodeCount(mode) ?: 40)
            else -> null
        }
    }

    /**
     * 构造一张 19 节点的线性调试图。
     */
    private fun buildWideDebugGraph(): DebugGraphDefinition {
        // 线性链路按固定顺序组织，便于调试跳转、投影和画布滚动行为。
        val methodNodes = buildMethodNodes(
            specs = listOf(
                DebugMethodSpec(
                    signature = "com.example.order.OrderEntryController.submit(com.example.order.SubmitRequest):com.example.order.SubmitResult",
                    title = "OrderEntryController.submit",
                    doc = "入口控制器，负责接收提交请求。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderFacade.place(com.example.order.SubmitRequest):com.example.order.SubmitResult",
                    title = "OrderFacade.place",
                    doc = "Facade 层，补齐前置校验。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderApplicationService.execute(com.example.order.SubmitRequest):com.example.order.SubmitResult",
                    title = "OrderApplicationService.execute",
                    doc = "应用服务，编排领域与基础设施调用。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.DataSourceAspect.around(org.aspectj.lang.ProceedingJoinPoint):java.lang.Object",
                    title = "DataSourceAspect.around",
                    doc = "切面切换数据源，属于需要重点核对的黑逻辑。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderDomainService.prepare(com.example.order.SubmitRequest):com.example.order.OrderAggregate",
                    title = "OrderDomainService.prepare",
                    doc = "组装订单聚合并补全业务字段。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.InventoryPolicy.check(com.example.order.OrderAggregate):void",
                    title = "InventoryPolicy.check",
                    doc = "库存校验策略。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.RiskPolicy.check(com.example.order.OrderAggregate):void",
                    title = "RiskPolicy.check",
                    doc = "风控校验策略。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderRepository.save(com.example.order.OrderAggregate):java.lang.Long",
                    title = "OrderRepository.save",
                    doc = "持久化订单聚合。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderMapper.insert(com.example.order.OrderAggregate):int",
                    title = "OrderMapper.insert",
                    doc = "写入主订单记录。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OutboxPublisher.publishOrderCreated(java.lang.Long):void",
                    title = "OutboxPublisher.publishOrderCreated",
                    doc = "写入事件表，等待异步投递。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.SpringEventBridge.publish(java.lang.Long):void",
                    title = "SpringEventBridge.publish",
                    doc = "向 Spring Event 桥接领域事件。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderCreatedListener.onEvent(com.example.order.OrderCreatedEvent):void",
                    title = "OrderCreatedListener.onEvent",
                    doc = "监听订单创建事件，刷新搜索索引。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.SearchIndexSyncService.sync(java.lang.Long):void",
                    title = "SearchIndexSyncService.sync",
                    doc = "搜索索引同步。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.OrderTraceService.record(java.lang.Long):void",
                    title = "OrderTraceService.record",
                    doc = "记录问答流水。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.Notifier.send(java.lang.Long):void",
                    title = "Notifier.send",
                    doc = "触发通知链路。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.CallbackRouter.route(java.lang.Long):void",
                    title = "CallbackRouter.route",
                    doc = "分发下游回调。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.CallbackWorker.execute(java.lang.Long):void",
                    title = "CallbackWorker.execute",
                    doc = "执行最终回调动作。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.MetricsCollector.markSuccess(java.lang.Long):void",
                    title = "MetricsCollector.markSuccess",
                    doc = "记录成功指标。",
                ),
                DebugMethodSpec(
                    signature = "com.example.order.TraceLogger.finish(java.lang.Long):void",
                    title = "TraceLogger.finish",
                    doc = "补写最终 trace 日志。",
                ),
            ),
        )
        // 使用相邻节点连边，构造一条可预测的线性调用链。
        val edges = methodNodes.zipWithNext { left, right ->
            GraphEdge(
                id = GraphEdge.stableId(EdgeType.CALL, left.id, right.id, "debug-wide19"),
                type = EdgeType.CALL,
                fromNodeId = left.id,
                toNodeId = right.id,
                sourceTag = GraphSourceTag.FACT,
            )
        }
        return DebugGraphDefinition(
            graph = GraphDocument(nodes = methodNodes, edges = edges),
            // 锚点选在切面节点上，便于验证非业务核心点的问答体验。
            anchorSignature = methodNodes[3].signature.orEmpty(),
            summary = "wide19 线性链路（19 节点）",
        )
    }

    /**
     * 构造一张高扇出的稠密调试图。
     */
    private fun buildDenseDebugGraph(
        nodeCount: Int,
    ): DebugGraphDefinition {
        // 节点总数限制在安全区间内，避免调试模式失控。
        val boundedNodeCount = nodeCount.coerceIn(2, 400)
        val anchor = buildMethodNode(
            spec = DebugMethodSpec(
                signature = "com.example.order.DenseAnchor.execute(com.example.order.DenseRequest):void",
                title = "DenseAnchor.execute",
                doc = "高扇出锚点，用于压测大图渲染。",
            ),
            index = 0,
        )
        val neighborNodes = (1 until boundedNodeCount).map { index ->
            buildMethodNode(
                spec = DebugMethodSpec(
                    signature = "com.example.order.DenseNode$index.handle(java.lang.String):void",
                    title = "DenseNode$index.handle",
                    doc = "压测节点 $index。",
                ),
                index = index,
            )
        }
        // 模式后缀参与边标识构造，保证不同密度图之间标识不冲突。
        val modeSuffix = "debug-dense$boundedNodeCount"
        val nodes = listOf(anchor) + neighborNodes
        val edges = buildList {
            neighborNodes.forEachIndexed { index, node ->
                // 所有邻接节点都从锚点出发，形成高扇出结构。
                add(
                    GraphEdge(
                        id = GraphEdge.stableId(EdgeType.CALL, anchor.id, node.id, modeSuffix),
                        type = EdgeType.CALL,
                        fromNodeId = anchor.id,
                        toNodeId = node.id,
                        sourceTag = GraphSourceTag.FACT,
                    ),
                )
                if (index > 0) {
                    // 邻接节点之间再串联一层，制造更复杂的连边密度。
                    add(
                        GraphEdge(
                            id = GraphEdge.stableId(EdgeType.CALL, neighborNodes[index - 1].id, node.id, modeSuffix),
                            type = EdgeType.CALL,
                            fromNodeId = neighborNodes[index - 1].id,
                            toNodeId = node.id,
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    )
                }
            }
        }
        return DebugGraphDefinition(
            graph = GraphDocument(nodes = nodes, edges = edges),
            anchorSignature = anchor.signature.orEmpty(),
            summary = "dense$boundedNodeCount 高扇出链路（$boundedNodeCount 节点）",
        )
    }

    /**
     * 批量构造方法节点。
     */
    private fun buildMethodNodes(
        specs: List<DebugMethodSpec>,
    ): List<GraphNode> = specs.mapIndexed(::buildMethodNode)

    /**
     * 把单个调试方法规格转换为图节点。
     */
    private fun buildMethodNode(
        index: Int,
        spec: DebugMethodSpec,
    ): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, spec.signature),
            type = NodeType.METHOD,
            title = spec.title,
            // 调试压测图需要命中真实可打开文件，才能验证源码跳转是否会卡住 EDT。
            location = "src/main/kotlin/com/charmnight/linkgraph/services/DebugGraphFactory.kt:${120 + index}:1",
            signature = spec.signature,
            inputs = listOf("com.example.order.Payload$index", "java.lang.String"),
            outputs = listOf(spec.signature.substringAfterLast(':')),
            doc = spec.doc,
            bindingStatus = BindingStatus.BOUND,
            certainty = Certainty.PROVEN,
            sourceTag = GraphSourceTag.FACT,
        )
    }
}
