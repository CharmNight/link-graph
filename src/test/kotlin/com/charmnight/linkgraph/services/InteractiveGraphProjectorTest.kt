package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractiveGraphProjectorTest {
    @Test
    fun truncatesMediumSizedMethodGraphByDefaultToKeepCanvasReadable() {
        val anchor = methodNode("method:anchor", "OrderService.importUser")
        val upstreamNodes = (1..18).map { index -> methodNode("method:caller-$index", "OrderController.call$index") }
        val downstreamNodes = (1..26).map { index -> methodNode("method:callee-$index", "OrderHandler.handle$index") }
        val graph = GraphDocument(
            nodes = buildList {
                add(anchor)
                addAll(upstreamNodes)
                addAll(downstreamNodes)
            },
            edges = buildList {
                upstreamNodes.forEach { node ->
                    add(callEdge(node.id, anchor.id))
                }
                downstreamNodes.forEach { node ->
                    add(callEdge(anchor.id, node.id))
                }
            },
        )

        val projection = InteractiveGraphProjector().project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        assertTrue(projection.truncated, "默认交互画布应收束中等规模方法图，避免一次展示过多节点")
        assertTrue(
            projection.visibleGraph.nodes.size < projection.fullGraph.nodes.size,
            "默认交互画布应保留完整事实图，但只展示较小的可交互窗口",
        )
        assertTrue(
            projection.visibleGraph.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.title.contains("已折叠")
            },
            "默认交互画布应明确保留可继续展开的折叠摘要节点",
        )
    }

    @Test
    fun projectsLargeGraphIntoInteractiveWindowAndAddsOverflowMarkers() {
        val anchor = methodNode("method:anchor", "OrderService.submit")
        val upstreamNodes = (1..8).map { index -> methodNode("method:caller-$index", "OrderController.call$index") }
        val downstreamNodes = (1..10).map { index -> methodNode("method:callee-$index", "OrderHandler.handle$index") }
        val graph = GraphDocument(
            nodes = buildList {
                add(anchor)
                addAll(upstreamNodes)
                addAll(downstreamNodes)
            },
            edges = buildList {
                upstreamNodes.forEach { node ->
                    add(callEdge(node.id, anchor.id))
                }
                downstreamNodes.forEach { node ->
                    add(callEdge(anchor.id, node.id))
                }
            },
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 7,
            maxVisibleEdges = 8,
            upstreamDepth = 1,
            downstreamDepth = 1,
            maxNeighborsPerDirection = 2,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        assertTrue(projection.truncated, "expected large graph to be truncated for interactive rendering")
        assertEquals(graph.nodes.size, projection.fullGraph.nodes.size)
        assertTrue(projection.visibleGraph.nodes.size <= 7, "visible graph should stay within interactive limit")
        assertTrue(
            projection.visibleGraph.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.title.contains("上游已折叠")
            },
            "expected upstream overflow marker",
        )
        assertTrue(
            projection.visibleGraph.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.title.contains("下游已折叠")
            },
            "expected downstream overflow marker",
        )
    }

    @Test
    fun keepsBusinessMethodsVisibleBeforeAccessorNoiseWhenInteractiveBudgetIsTight() {
        val anchor = methodNode("method:anchor", "OrderService.importUser")
        val accessorA = methodNode("method:get-create-by", "BaseEntity.getCreateBy")
        val accessorB = methodNode("method:get-update-by", "BaseEntity.getUpdateBy")
        val business = methodNode("method:business-core", "SysUserService.validateImportPayload")
        val graph = GraphDocument(
            nodes = listOf(anchor, accessorA, accessorB, business),
            edges = listOf(
                callEdge(anchor.id, accessorA.id),
                callEdge(anchor.id, accessorB.id),
                callEdge(anchor.id, business.id),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 3,
            maxVisibleEdges = 3,
            upstreamDepth = 0,
            downstreamDepth = 1,
            maxNeighborsPerDirection = 1,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        assertTrue(
            projection.visibleGraph.nodes.any { it.id == business.id },
            "业务方法应该优先保留在可交互画布中",
        )
    }

    @Test
    fun keepsAnchorDirectCallsVisibleBeforeSecondHopNoiseAndRetainsExtractionOverflow() {
        val anchor = methodNode("method:anchor", "DataScopeAspect.dataScopeFilter")
        val directAccessorA = methodNode("method:get-roles", "SysUser.getRoles")
        val directAccessorB = methodNode("method:get-data-scope", "SysRole.getDataScope")
        val directBusinessA = methodNode("method:contains-any", "StringUtils.containsAny")
        val directBusinessB = methodNode("method:to-str-array", "Convert.toStrArray")
        val extractOverflow = GraphNode(
            id = "uncertain:extract-overflow",
            type = NodeType.UNCERTAIN_LINK,
            title = "下游调用过多，已折叠 6 个方法",
            signature = "6 个方法未继续展开",
            metadata = mapOf(
                "linkGraph.overflow.hiddenMethodCount" to "6",
                "linkGraph.overflow.titlePrefix" to "下游调用过多",
            ),
        )
        val secondHopNoise = methodNode("method:is-empty", "StringUtils.isEmpty")
        val graph = GraphDocument(
            nodes = listOf(
                anchor,
                directAccessorA,
                directAccessorB,
                directBusinessA,
                directBusinessB,
                extractOverflow,
                secondHopNoise,
            ),
            edges = listOf(
                callEdge(anchor.id, directAccessorA.id, callOrder = 0),
                callEdge(anchor.id, directAccessorB.id, callOrder = 1),
                callEdge(anchor.id, directBusinessA.id, callOrder = 2),
                callEdge(anchor.id, directBusinessB.id, callOrder = 3),
                callEdge(anchor.id, extractOverflow.id, callOrder = 4),
                callEdge(directBusinessA.id, secondHopNoise.id, callOrder = 0),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 6,
            maxVisibleEdges = 8,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 2,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        val visibleTitles = projection.visibleGraph.nodes.map { it.title }.sorted()

        assertTrue(directAccessorA.id in visibleNodeIds, "锚点的一跳直接调用应优先保留，实际节点=$visibleTitles")
        assertTrue(directAccessorB.id in visibleNodeIds, "锚点的一跳 getter 调用不应被二跳工具方法挤掉，实际节点=$visibleTitles")
        assertTrue(directBusinessA.id in visibleNodeIds, "锚点的一跳业务调用应优先保留，实际节点=$visibleTitles")
        assertTrue(directBusinessB.id in visibleNodeIds, "锚点的一跳业务调用应优先保留，实际节点=$visibleTitles")
        assertTrue(extractOverflow.id in visibleNodeIds, "提图阶段已经生成的 overflow 节点应优先保留，实际节点=$visibleTitles")
        assertTrue(secondHopNoise.id !in visibleNodeIds, "预算紧张时应先隐藏二跳噪音节点，实际节点=$visibleTitles")
    }

    @Test
    fun keepsFlowScopeVisibleBeforeScopedInternalCallsWhenInteractiveBudgetIsTight() {
        val flowScopeType = NodeType.valueOf("FLOW_SCOPE")
        val containsFlowType = EdgeType.valueOf("CONTAINS_FLOW")
        val anchor = methodNode("method:anchor", "ScopedCallChain.render")
        val getLines = methodNode("method:get-lines", "Order.getLines")
        val forEachCall = methodNode("method:for-each", "Iterable.forEach")
        val afterCall = methodNode("method:after", "ScopedCallChain.after")
        val lambdaScope = GraphNode(
            id = "flow:lambda",
            type = flowScopeType,
            title = "forEach λ",
            signature = "lambda body",
        )
        val ifScope = GraphNode(
            id = "flow:if",
            type = flowScopeType,
            title = "if (line.isActive())",
            signature = "branch body",
        )
        val validate = methodNode("method:validate", "ScopedCallChain.validate")
        val record = methodNode("method:record", "ScopedCallChain.record")
        val graph = GraphDocument(
            nodes = listOf(anchor, getLines, forEachCall, afterCall, lambdaScope, ifScope, validate, record),
            edges = listOf(
                callEdge(anchor.id, getLines.id, callOrder = 0),
                callEdge(anchor.id, forEachCall.id, callOrder = 1),
                GraphEdge(
                    id = "flow-edge-anchor-lambda",
                    type = containsFlowType,
                    fromNodeId = anchor.id,
                    toNodeId = lambdaScope.id,
                    metadata = mapOf("callOrder" to "2"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(anchor.id, afterCall.id, callOrder = 3),
                GraphEdge(
                    id = "flow-edge-lambda-if",
                    type = containsFlowType,
                    fromNodeId = lambdaScope.id,
                    toNodeId = ifScope.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(ifScope.id, validate.id, callOrder = 0),
                callEdge(ifScope.id, record.id, callOrder = 1),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 5,
            maxVisibleEdges = 6,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 2,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        val visibleTitles = projection.visibleGraph.nodes.map { it.title }.sorted()
        assertTrue(getLines.id in visibleNodeIds, "锚点顶层真实调用应优先保留，实际节点=$visibleTitles")
        assertTrue(forEachCall.id in visibleNodeIds, "锚点顶层真实调用应优先保留，实际节点=$visibleTitles")
        assertTrue(lambdaScope.id in visibleNodeIds, "预算紧张时应先保留作用域节点，而不是把内部调用直接顶到首屏，实际节点=$visibleTitles")
        assertTrue(afterCall.id in visibleNodeIds, "锚点顶层真实调用应优先保留，实际节点=$visibleTitles")
        assertTrue(validate.id !in visibleNodeIds, "作用域内部调用应在预算紧张时次级展示，实际节点=$visibleTitles")
        assertTrue(record.id !in visibleNodeIds, "作用域内部调用应在预算紧张时次级展示，实际节点=$visibleTitles")
    }

    @Test
    fun nestedFlowScopesDoNotConsumeBusinessDepthForDirectCallsInsideCurrentMethod() {
        val flowScopeType = NodeType.valueOf("FLOW_SCOPE")
        val containsFlowType = EdgeType.valueOf("CONTAINS_FLOW")
        val anchor = methodNode("method:handle-data-scope", "DataScopeAspect.handleDataScope")
        val doBefore = methodNode("method:do-before", "DataScopeAspect.doBefore")
        val getSysUser = methodNode("method:get-sys-user", "ShiroUtils.getSysUser")
        val outerIf = GraphNode(
            id = "flow:outer-if",
            type = flowScopeType,
            title = "if (currentUser != null)",
            signature = "branch body",
        )
        val innerIf = GraphNode(
            id = "flow:inner-if",
            type = flowScopeType,
            title = "if (!currentUser.isAdmin())",
            signature = "branch body",
        )
        val dataScopeFilter = methodNode("method:data-scope-filter", "DataScopeAspect.dataScopeFilter")
        val permissionLookup = methodNode("method:permission-lookup", "PermissionContextHolder.getContext")
        val graph = GraphDocument(
            nodes = listOf(doBefore, anchor, getSysUser, outerIf, innerIf, dataScopeFilter, permissionLookup),
            edges = listOf(
                callEdge(doBefore.id, anchor.id, callOrder = 0),
                callEdge(anchor.id, getSysUser.id, callOrder = 0),
                GraphEdge(
                    id = "flow-edge-anchor-outer",
                    type = containsFlowType,
                    fromNodeId = anchor.id,
                    toNodeId = outerIf.id,
                    metadata = mapOf("callOrder" to "1"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "flow-edge-outer-inner",
                    type = containsFlowType,
                    fromNodeId = outerIf.id,
                    toNodeId = innerIf.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(innerIf.id, dataScopeFilter.id, callOrder = 0),
                callEdge(innerIf.id, permissionLookup.id, callOrder = 1),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 6,
            maxVisibleEdges = 6,
            upstreamDepth = 1,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 5,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        val visibleTitles = projection.visibleGraph.nodes.map { it.title }.sorted()
        assertTrue(outerIf.id in visibleNodeIds, "当前方法里的外层流程节点应可见，实际节点=$visibleTitles")
        assertTrue(innerIf.id in visibleNodeIds, "当前方法里的内层流程节点应可见，实际节点=$visibleTitles")
        assertTrue(
            dataScopeFilter.id in visibleNodeIds,
            "嵌套流程中的直接业务调用不应因 FLOW_SCOPE 吞掉深度而丢失，实际节点=$visibleTitles",
        )
    }

    @Test
    fun hiddenNodeCountMatchesFullGraphDifferenceInsteadOfTraversalFrontierOnly() {
        val flowScopeType = NodeType.valueOf("FLOW_SCOPE")
        val containsFlowType = EdgeType.valueOf("CONTAINS_FLOW")
        val anchor = methodNode("method:anchor", "DataScopeAspect.handleDataScope")
        val outerIf = GraphNode(
            id = "flow:outer-if",
            type = flowScopeType,
            title = "if (user != null)",
            signature = "branch body",
        )
        val innerIf = GraphNode(
            id = "flow:inner-if",
            type = flowScopeType,
            title = "if (!user.isAdmin())",
            signature = "branch body",
        )
        val directCall = methodNode("method:get-sys-user", "ShiroUtils.getSysUser")
        val deepBusinessA = methodNode("method:permission", "DataScope.permission")
        val deepBusinessB = methodNode("method:dept-alias", "DataScope.deptAlias")
        val deepBusinessC = methodNode("method:user-alias", "DataScope.userAlias")
        val deepBusinessD = methodNode("method:data-scope-filter", "DataScopeAspect.dataScopeFilter")
        val graph = GraphDocument(
            nodes = listOf(anchor, outerIf, innerIf, directCall, deepBusinessA, deepBusinessB, deepBusinessC, deepBusinessD),
            edges = listOf(
                callEdge(anchor.id, directCall.id, callOrder = 0),
                GraphEdge(
                    id = "flow-edge-anchor-outer",
                    type = containsFlowType,
                    fromNodeId = anchor.id,
                    toNodeId = outerIf.id,
                    metadata = mapOf("callOrder" to "1"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "flow-edge-outer-inner",
                    type = containsFlowType,
                    fromNodeId = outerIf.id,
                    toNodeId = innerIf.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(innerIf.id, deepBusinessA.id, callOrder = 0),
                callEdge(innerIf.id, deepBusinessB.id, callOrder = 1),
                callEdge(innerIf.id, deepBusinessC.id, callOrder = 2),
                callEdge(innerIf.id, deepBusinessD.id, callOrder = 3),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 4,
            maxVisibleEdges = 4,
            upstreamDepth = 0,
            downstreamDepth = 1,
            maxNeighborsPerDirection = 2,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIdsInFullGraph = projection.visibleGraph.nodes.mapNotNull { node ->
            node.id.takeIf { visibleId -> graph.nodes.any { it.id == visibleId } }
        }.toSet()
        val expectedHiddenNodeCount = graph.nodes.count { it.id !in visibleNodeIdsInFullGraph }

        assertEquals(
            expectedHiddenNodeCount,
            projection.hiddenNodeCount,
            "折叠节点数应等于完整事实图与当前可见真实节点的差值",
        )
    }

    @Test
    fun prefersBusinessCallsOverAccessorsInsideFlowScopesWhenNeighborBudgetIsTight() {
        val flowScopeType = NodeType.valueOf("FLOW_SCOPE")
        val containsFlowType = EdgeType.valueOf("CONTAINS_FLOW")
        val anchor = methodNode("method:anchor", "DataScopeAspect.handleDataScope")
        val outerIf = GraphNode(
            id = "flow:outer-if",
            type = flowScopeType,
            title = "if (currentUser != null)",
            signature = "branch body",
        )
        val innerIf = GraphNode(
            id = "flow:inner-if",
            type = flowScopeType,
            title = "if (!currentUser.isAdmin())",
            signature = "branch body",
        )
        val permission = methodNode("method:permission", "DataScope.permission")
        val getContext = methodNode("method:get-context", "PermissionContextHolder.getContext")
        val deptAlias = methodNode("method:dept-alias", "DataScope.deptAlias")
        val userAlias = methodNode("method:user-alias", "DataScope.userAlias")
        val defaultIfEmpty = methodNode("method:default-if-empty", "StringUtils.defaultIfEmpty")
        val dataScopeFilter = methodNode("method:data-scope-filter", "DataScopeAspect.dataScopeFilter")
        val graph = GraphDocument(
            nodes = listOf(anchor, outerIf, innerIf, permission, getContext, deptAlias, userAlias, defaultIfEmpty, dataScopeFilter),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge-anchor-outer",
                    type = containsFlowType,
                    fromNodeId = anchor.id,
                    toNodeId = outerIf.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                GraphEdge(
                    id = "flow-edge-outer-inner",
                    type = containsFlowType,
                    fromNodeId = outerIf.id,
                    toNodeId = innerIf.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(innerIf.id, permission.id, callOrder = 0),
                callEdge(innerIf.id, getContext.id, callOrder = 1),
                callEdge(innerIf.id, deptAlias.id, callOrder = 2),
                callEdge(innerIf.id, userAlias.id, callOrder = 3),
                callEdge(innerIf.id, defaultIfEmpty.id, callOrder = 4),
                callEdge(innerIf.id, dataScopeFilter.id, callOrder = 5),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 8,
            maxVisibleEdges = 8,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 5,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        val visibleTitles = projection.visibleGraph.nodes.map { it.title }.sorted()
        assertTrue(defaultIfEmpty.id in visibleNodeIds, "流程体中的业务整理调用应优先可见，实际节点=$visibleTitles")
        assertTrue(dataScopeFilter.id in visibleNodeIds, "最终业务调用不应被前面的 accessor 挤掉，实际节点=$visibleTitles")
        assertTrue(
            listOf(permission.id, getContext.id, deptAlias.id, userAlias.id).any { it !in visibleNodeIds },
            "预算紧张时应优先折叠至少一个 accessor/注解读取节点，实际节点=$visibleTitles",
        )
    }

    @Test
    fun separatesHiddenCurrentMethodNodesFromCrossMethodExpansion() {
        val anchor = methodNode("method:anchor", "ScopedCallChain.buildUser")
        val ifScope = GraphNode(
            id = "flow:if",
            type = NodeType.FLOW_SCOPE,
            title = "if (source != null)",
            signature = "branch body",
            metadata = mapOf("flow.ownerMethod" to "com.example.ScopedCallChain.buildUser(java.lang.Object):void"),
        )
        val copyAction = GraphNode(
            id = "flow-action:copy-bean",
            type = NodeType.FLOW_ACTION,
            title = "BeanUtils.copyBeanProp(user, source)",
            signature = "BeanUtils.copyBeanProp(user, source)",
            metadata = mapOf("flow.anchorMethod" to "com.example.ScopedCallChain.buildUser(java.lang.Object):void"),
        )
        val directBusiness = methodNode("method:copy-bean", "BeanUtils.copyBeanProp")
        val crossMethodExpansion = methodNode("method:copy-fields", "PropertyCopier.copyFields")
        val graph = GraphDocument(
            nodes = listOf(anchor, ifScope, copyAction, directBusiness, crossMethodExpansion),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge-anchor-if",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = anchor.id,
                    toNodeId = ifScope.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(ifScope.id, copyAction.id, callOrder = 0),
                callEdge(ifScope.id, directBusiness.id, callOrder = 1),
                callEdge(directBusiness.id, crossMethodExpansion.id, callOrder = 0),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 3,
            maxVisibleEdges = 3,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 1,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        assertTrue(copyAction.id in visibleNodeIds, "当前方法动作节点应优先保留")
        assertTrue(directBusiness.id !in visibleNodeIds, "预算不足时，后续真实调用可被折叠")
        assertEquals(1, projection.hiddenCurrentMethodNodeCount, "应单独统计当前方法内部被折叠的节点")
        assertEquals(1, projection.hiddenCrossMethodNodeCount, "应单独统计跨方法扩展被折叠的节点")
    }

    @Test
    fun prefersDeeperCurrentMethodNodesBeforeCrossMethodExpansionWhenBudgetRunsOut() {
        val anchor = methodNode("method:anchor", "ScopedCallChain.render")
        val ifScope = GraphNode(
            id = "flow:if",
            type = NodeType.FLOW_SCOPE,
            title = "if (source != null)",
            signature = "branch body",
        )
        val copyAction = GraphNode(
            id = "flow-action:copy",
            type = NodeType.FLOW_ACTION,
            title = "BeanUtils.copyBeanProp(user, source)",
            signature = "BeanUtils.copyBeanProp(user, source)",
            metadata = mapOf("flow.anchorMethod" to "com.example.ScopedCallChain.render():void"),
        )
        val nestedCurrentMethodCall = methodNode("method:nested-current", "ScopedCallChain.fillUserProfile")
        val directCurrentMethodCall = methodNode("method:direct-current", "BeanUtils.copyBeanProp")
        val crossMethodExpansion = methodNode("method:cross", "PropertyCopier.copyFields")
        val graph = GraphDocument(
            nodes = listOf(anchor, ifScope, copyAction, nestedCurrentMethodCall, directCurrentMethodCall, crossMethodExpansion),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge-anchor-if",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = anchor.id,
                    toNodeId = ifScope.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(anchor.id, directCurrentMethodCall.id, callOrder = 1),
                callEdge(ifScope.id, copyAction.id, callOrder = 0),
                callEdge(copyAction.id, nestedCurrentMethodCall.id, callOrder = 0),
                callEdge(directCurrentMethodCall.id, crossMethodExpansion.id, callOrder = 0),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 5,
            maxVisibleEdges = 6,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 5,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        val visibleTitles = projection.visibleGraph.nodes.map { it.title }.sorted()
        assertTrue(ifScope.id in visibleNodeIds, "当前方法里的流程骨架应优先保留，实际节点=$visibleTitles")
        assertTrue(copyAction.id in visibleNodeIds, "当前方法动作节点应优先保留，实际节点=$visibleTitles")
        assertTrue(
            nestedCurrentMethodCall.id in visibleNodeIds,
            "预算紧张时，应先保留当前方法更深一层的关键调用，而不是先展开跨方法节点，实际节点=$visibleTitles",
        )
        assertTrue(
            crossMethodExpansion.id !in visibleNodeIds,
            "跨方法扩展应让位于当前方法内部关键节点，实际节点=$visibleTitles",
        )
        assertEquals(0, projection.hiddenCurrentMethodNodeCount, "当前方法内部核心节点应尽量优先保留")
        assertEquals(1, projection.hiddenCrossMethodNodeCount, "跨方法扩展应作为次级信息折叠")
    }

    @Test
    fun carriesCurrentMethodAndCrossMethodHiddenCountsOnProjectorOverflowNodes() {
        val anchor = methodNode("method:anchor", "ScopedCallChain.render")
        val ifScope = GraphNode(
            id = "flow:if",
            type = NodeType.FLOW_SCOPE,
            title = "if (source != null)",
            signature = "branch body",
        )
        val copyAction = GraphNode(
            id = "flow-action:copy",
            type = NodeType.FLOW_ACTION,
            title = "BeanUtils.copyBeanProp(user, source)",
            signature = "BeanUtils.copyBeanProp(user, source)",
            metadata = mapOf("flow.anchorMethod" to "com.example.ScopedCallChain.render():void"),
        )
        val nestedCurrentMethodCall = methodNode("method:nested-current", "ScopedCallChain.fillUserProfile")
        val directCurrentMethodCall = methodNode("method:direct-current", "BeanUtils.copyBeanProp")
        val crossMethodExpansionA = methodNode("method:cross-a", "PropertyCopier.copyFields")
        val crossMethodExpansionB = methodNode("method:cross-b", "PropertyCopier.copyMeta")
        val crossMethodExpansionC = methodNode("method:cross-c", "PropertyCopier.copyAudit")
        val graph = GraphDocument(
            nodes = listOf(
                anchor,
                ifScope,
                copyAction,
                nestedCurrentMethodCall,
                directCurrentMethodCall,
                crossMethodExpansionA,
                crossMethodExpansionB,
                crossMethodExpansionC,
            ),
            edges = listOf(
                GraphEdge(
                    id = "flow-edge-anchor-if",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = anchor.id,
                    toNodeId = ifScope.id,
                    metadata = mapOf("callOrder" to "0"),
                    sourceTag = GraphSourceTag.FACT,
                ),
                callEdge(anchor.id, directCurrentMethodCall.id, callOrder = 1),
                callEdge(ifScope.id, copyAction.id, callOrder = 0),
                callEdge(copyAction.id, nestedCurrentMethodCall.id, callOrder = 0),
                callEdge(directCurrentMethodCall.id, crossMethodExpansionA.id, callOrder = 0),
                callEdge(crossMethodExpansionA.id, crossMethodExpansionB.id, callOrder = 0),
                callEdge(crossMethodExpansionB.id, crossMethodExpansionC.id, callOrder = 0),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 7,
            maxVisibleEdges = 7,
            upstreamDepth = 0,
            downstreamDepth = 2,
            maxNeighborsPerDirection = 5,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val overflowNode = projection.visibleGraph.nodes.firstOrNull { it.type == NodeType.UNCERTAIN_LINK }
        assertTrue(overflowNode != null, "预算紧张时应生成 projector overflow 节点")
        assertEquals("0", overflowNode.metadata["linkGraph.hidden.currentMethodNodeCount"])
        assertEquals("2", overflowNode.metadata["linkGraph.hidden.crossMethodNodeCount"])
    }

    @Test
    fun keepsDownstreamMethodBodyCollapsedInCurrentMethodProjection() {
        val anchor = methodNode("method:set-sys-user", "ShiroUtils.setSysUser")
        val currentAction = GraphNode(
            id = "flow-action:get-subject",
            type = NodeType.FLOW_ACTION,
            title = "getSubject()",
            signature = "getSubject()",
            metadata = mapOf(
                "flow.anchorMethod" to "com.example.ShiroUtils.setSysUser(com.example.SysUser):void",
                "flow.ownerMethod" to "com.example.ShiroUtils.setSysUser(com.example.SysUser):void",
            ),
        )
        val downstreamMethod = methodNode("method:shiro-get-subject", "ShiroUtils.getSubject")
        val downstreamInternalAction = GraphNode(
            id = "flow-action:security-get-subject",
            type = NodeType.FLOW_ACTION,
            title = "SecurityUtils.getSubject()",
            signature = "SecurityUtils.getSubject()",
            metadata = mapOf(
                "flow.anchorMethod" to "com.example.ShiroUtils.getSubject():org.apache.shiro.subject.Subject",
                "flow.ownerMethod" to "com.example.ShiroUtils.getSubject():org.apache.shiro.subject.Subject",
            ),
        )
        val downstreamInternalMethod = methodNode("method:security-get-subject", "SecurityUtils.getSubject")
        val graph = GraphDocument(
            nodes = listOf(anchor, currentAction, downstreamMethod, downstreamInternalAction, downstreamInternalMethod),
            edges = listOf(
                callEdge(anchor.id, currentAction.id, callOrder = 0),
                callEdge(currentAction.id, downstreamMethod.id, callOrder = 0),
                callEdge(downstreamMethod.id, downstreamInternalAction.id, callOrder = 0),
                callEdge(downstreamInternalAction.id, downstreamInternalMethod.id, callOrder = 0),
            ),
        )

        val projection = InteractiveGraphProjector(
            maxVisibleNodes = 26,
            maxVisibleEdges = 40,
            upstreamDepth = 0,
            downstreamDepth = 3,
            maxNeighborsPerDirection = 5,
        ).project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        val visibleNodeIds = projection.visibleGraph.nodes.map { it.id }.toSet()
        assertTrue(currentAction.id in visibleNodeIds, "当前方法动作节点应保留")
        assertTrue(downstreamMethod.id in visibleNodeIds, "当前方法直接调用到的下游方法应保留")
        assertTrue(
            downstreamInternalAction.id !in visibleNodeIds,
            "当前方法视图默认不应直接内联下游方法体内部动作，否则会把下游实现细节误读成当前代码行",
        )
        assertTrue(
            downstreamInternalMethod.id !in visibleNodeIds,
            "当前方法视图默认不应继续展开到下游方法体内部动作所连接出的更深节点",
        )
        assertEquals(5, projection.fullGraph.nodes.size, "完整事实图仍应保留")
    }

    @Test
    fun keepsHundredsOfNodesOutOfInitialInteractiveProjection() {
        val anchor = methodNode("method:anchor", "AuditAnchor.execute")
        val callers = (1..120).map { index -> methodNode("method:caller-$index", "UpstreamCaller.call$index") }
        val callees = (1..140).map { index -> methodNode("method:callee-$index", "DownstreamHandler.handle$index") }
        val graph = GraphDocument(
            nodes = buildList {
                add(anchor)
                addAll(callers)
                addAll(callees)
            },
            edges = buildList {
                callers.forEach { node ->
                    add(callEdge(node.id, anchor.id))
                }
                callees.forEach { node ->
                    add(callEdge(anchor.id, node.id))
                }
            },
        )

        val projection = InteractiveGraphProjector().project(
            graph = graph,
            anchorNodeId = anchor.id,
        )

        assertTrue(projection.truncated, "数百节点事实图应先收束为可交互窗口")
        assertEquals(graph.nodes.size, projection.fullGraph.nodes.size, "完整事实图不能丢")
        assertTrue(
            projection.visibleGraph.nodes.size <= 26,
            "默认交互窗口不应把数百节点整图直接压给前端",
        )
        assertTrue(
            projection.visibleGraph.edges.size <= 40,
            "默认交互窗口的边数量也应受预算保护",
        )
        assertTrue(
            projection.visibleGraph.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.title.contains("已折叠")
            },
            "大图默认投影应保留可继续展开的摘要节点",
        )
        assertTrue(
            projection.visibleGraph.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.doc?.contains("继续问答") == true
            },
            "折叠摘要节点文案应与问答口径保持一致",
        )
    }

    private fun methodNode(id: String, title: String) = GraphNode(
        id = id,
        type = NodeType.METHOD,
        title = title,
        signature = "com.example.$title():void",
    )

    private fun callEdge(sourceId: String, targetId: String, callOrder: Int? = null) = GraphEdge(
        id = "call:$sourceId->$targetId",
        type = EdgeType.CALL,
        fromNodeId = sourceId,
        toNodeId = targetId,
        metadata = buildMap {
            if (callOrder != null) {
                put("callOrder", callOrder.toString())
            }
        },
        sourceTag = GraphSourceTag.FACT,
    )
}
