package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.indexed.IndexedClassUsageOptions
import com.charmnight.linkgraph.application.indexed.requestArchitectureGraphRequest
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.indexed.requestClassUsageOverlayRequest
import com.charmnight.linkgraph.application.usecase.InvocationExpansionUseCase
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession
import com.charmnight.linkgraph.ui.GraphBrowserDiagnostics
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.SourceNavigationPhase
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅供 debug 入口使用的启动后自动化编排器。
 * 所有调试环境变量驱动的自动开窗、自动载图和自动请求都集中在这里。
 */
@Service(Service.Level.PROJECT)
internal class LinkGraphDebugAutomationCoordinator(
    private val project: Project,
) {
    /** 标记是否已经调度过自动化任务，避免同一项目重复触发。 */
    private val automationScheduled = AtomicBoolean(false)

    /**
     * 当传入的请求包含任一自动化动作时，依次调度对应的窗口打开、载图与请求任务。
     * 每种动作都会以固定延迟在后台执行，并尽量等待图就绪后再触发对应命令。
     */
    fun scheduleIfRequested(request: LinkGraphDebugAutomationRequest) {
        if (!request.hasAnyAction) {
            return
        }
        if (!automationScheduled.compareAndSet(false, true)) {
            return
        }

        val toolWindowSession = project.getService(LinkGraphToolWindowSession::class.java)
        val commandDispatcher = project.getService(GraphEditorApplicationService::class.java).commandDispatcher
        if (request.requiresToolWindowOpen) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "debug 入口触发工具窗口自动打开" }
            toolWindowSession.openToolWindow()
        }

        if (request.autoloadMethodSignature != null || request.autoloadGraphMode != null) {
            commandDispatcher.dispatch(
                ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode(
                    GraphEditorApplicationService.DEBUG_ANALYSIS_DISPLAY_MODE_ENV,
                ),
            )
        }

        request.autoloadMethodSignature?.let { signature ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到真实方法调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后提取真实方法链路: $signature"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                commandDispatcher.dispatch(ApplicationCommand.LoadDebugMethodGraphBySignature(signature))
            }
        } ?: request.autoloadGraphMode?.let { mode ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后注入诊断链路图: $mode"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                commandDispatcher.dispatch(ApplicationCommand.LoadDebugGraph(mode))
            }
        }

        request.autoExpandInvocationSignature?.let { invocationSignature ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动请求：调用展开，methodSignature=${request.autoloadMethodSignature.orEmpty()}, invocationSignature=$invocationSignature"
            }
            schedule(DEBUG_AUTO_EXPAND_INVOCATION_DELAY_MS) {
                runWhenAutoExpandInvocationReady(
                    requestedMethodSignature = request.autoloadMethodSignature,
                    requestedInvocationSignature = invocationSignature,
                )
            }
        }

        if (request.autoRequestArchitectureGraph) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：架构图" }
            schedule(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_DELAY_MS) {
                commandDispatcher.dispatch(ApplicationCommand.RequestIndexedGraph(requestArchitectureGraphRequest()))
            }
        }

        if (request.autoRequestArchitectureGraphBeautification) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：架构图讲解" }
            schedule(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_DELAY_MS) {
                runWhenArchitectureGraphReady("架构图讲解") {
                    val focusNodeId = currentArchitectureGraphFocusNodeId()
                    logger.warn("debug 自动触发架构图讲解: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("架构图讲解") {
                        commandDispatcher.dispatch(
                            ApplicationCommand.RequestAssistantTask(
                                intent = AssistantIntent.EXPLAIN_CODE,
                                actionId = AssistantActionId.EXPLAIN_STRUCTURE,
                                prompt = "验证架构图讲解上下文。请只围绕当前架构图中的模块、包、层和资源关系解释。",
                                selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

        if (request.autoRequestArchitectureGraphQa) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：架构图问答" }
            schedule(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_DELAY_MS) {
                runWhenArchitectureGraphReady("架构图问答") {
                    val focusNodeId = currentArchitectureGraphFocusNodeId()
                    logger.warn("debug 自动触发架构图问答: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("架构图问答") {
                        commandDispatcher.dispatch(
                            ApplicationCommand.RequestAssistantTask(
                                intent = AssistantIntent.ASK_CODE,
                                actionId = AssistantActionId.ASK_CONTEXT,
                                prompt = "请说明当前架构图里这个节点和相邻模块/包/资源是什么关系。",
                                selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

        if (request.autoRequestClassDiagram) {
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动请求：类图，scopeNodeId=${request.autoRequestClassDiagramScopeNodeId.orEmpty()}"
            }
            schedule(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_DELAY_MS) {
                logger.warn(
                    "debug 自动触发类图请求: scopeNodeId=${request.autoRequestClassDiagramScopeNodeId.orEmpty()}",
                )
                commandDispatcher.dispatch(
                    ApplicationCommand.RequestIndexedGraph(
                        requestClassDiagramRequest(request.autoRequestClassDiagramScopeNodeId),
                    ),
                )
            }
        }

        request.autoRequestClassUsageTargetNodeId?.let { targetNodeId ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动请求：类使用处，targetNodeId=$targetNodeId"
            }
            schedule(DEBUG_AUTO_REQUEST_CLASS_USAGE_DELAY_MS) {
                logger.warn("debug 自动触发类使用处请求: targetNodeId=$targetNodeId")
                commandDispatcher.dispatch(
                    ApplicationCommand.RequestIndexedGraph(
                        requestClassUsageOverlayRequest(targetNodeId).copy(
                            usage = IndexedClassUsageOptions(
                                enabled = true,
                                targetNodeId = targetNodeId,
                                targetQualifiedName = request.autoRequestClassUsageTargetQualifiedName,
                            ),
                        ),
                    ),
                )
            }
        }

        if (request.autoRequestClassDiagramBeautification) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：类图讲解" }
            schedule(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_DELAY_MS) {
                runWhenClassDiagramReady("类图讲解") {
                    val focusNodeId = currentClassDiagramFocusNodeId()
                    logger.warn("debug 自动触发类图讲解: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("类图讲解") {
                        commandDispatcher.dispatch(
                            ApplicationCommand.RequestAssistantTask(
                                intent = AssistantIntent.EXPLAIN_CODE,
                                actionId = AssistantActionId.EXPLAIN_STRUCTURE,
                                prompt = "验证类图讲解上下文。请只围绕当前类图中的类、接口、枚举和它们的关系解释。",
                                selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

        if (request.autoRequestClassDiagramQa) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：类图问答" }
            schedule(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_DELAY_MS) {
                runWhenClassDiagramReady("类图问答") {
                    val focusNodeId = currentClassDiagramFocusNodeId()
                    logger.warn("debug 自动触发类图问答: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("类图问答") {
                        commandDispatcher.dispatch(
                            ApplicationCommand.RequestAssistantTask(
                                intent = AssistantIntent.ASK_CODE,
                                actionId = AssistantActionId.ASK_CONTEXT,
                                prompt = "请说明当前类图里这个节点和相邻类/接口是什么关系。",
                                selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

        if (request.autoRequestSourceNavigation) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：源码导航" }
            schedule(DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_DELAY_MS) {
                runWhenArchitectureGraphReady("源码导航") {
                    val targetNode = currentArchitectureGraphSourceNavigationNode()
                    if (targetNode == null) {
                        logger.warn("debug 自动触发源码导航失败: 架构图没有可导航节点")
                        return@runWhenArchitectureGraphReady
                    }
                    logger.warn("debug 自动触发源码导航: nodeId=${targetNode.id}, title=${targetNode.title}, type=${targetNode.type}")
                    commandDispatcher.dispatch(ApplicationCommand.RequestSourceNavigation(targetNode.id))
                    schedule(DEBUG_SOURCE_NAVIGATION_STATE_POLL_MS) {
                        logSourceNavigationResult(targetNode.id)
                    }
                }
            }
        }

        if (request.autoRequestPlan) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成计划" }
            schedule(DEBUG_AUTO_REQUEST_PLAN_DELAY_MS) {
                commandDispatcher.dispatch(
                    ApplicationCommand.RequestAssistantTask(
                        intent = AssistantIntent.GENERATE_CODE,
                        actionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                        prompt = "",
                    ),
                )
            }
        }

        if (request.autoRequestCodeDrafts) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成代码草稿" }
            schedule(DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS) {
                commandDispatcher.dispatch(ApplicationCommand.RequestCodeDrafts)
            }
        }
    }

    /**
     * 计算类图当前焦点节点 ID。
     * 优先使用界面选中节点，其次使用锚点节点，最后回退到可见图中的第一个节点。
     */
    private fun currentClassDiagramFocusNodeId(): String? {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val currentSceneSelection = snapshot.currentSceneState().selectedNodeId
        return currentSceneSelection
            ?.takeIf { nodeId -> snapshot.classDiagramView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.classDiagramView.anchorNodeId
                ?.takeIf { nodeId -> snapshot.classDiagramView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.classDiagramView.visibleGraph.nodes.firstOrNull()?.id
    }

    /**
     * 计算架构图当前焦点节点 ID。
     * 选择顺序：界面选中 -> 锚点 -> 推荐焦点（有源码样本的聚合节点）-> 第一个节点。
     */
    private fun currentArchitectureGraphFocusNodeId(): String? {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val currentSceneSelection = snapshot.currentSceneState().selectedNodeId
        return currentSceneSelection
            ?.takeIf { nodeId -> snapshot.architectureGraphView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.architectureGraphView.anchorNodeId
                ?.takeIf { nodeId -> snapshot.architectureGraphView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.architectureGraphView.visibleGraph.preferredArchitectureFocusNode()?.id
            ?: snapshot.architectureGraphView.visibleGraph.nodes.firstOrNull()?.id
    }

    /**
     * 在架构图中挑选一个具备源码导航能力的节点。
     * 优先返回界面选中且可导航的节点，其次取推荐焦点，最后退回到首个可导航节点。
     */
    private fun currentArchitectureGraphSourceNavigationNode(): GraphNode? {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val graph = snapshot.architectureGraphView.visibleGraph
        val currentSceneSelection = snapshot.currentSceneState().selectedNodeId
            ?.let { selectedNodeId -> graph.nodes.firstOrNull { node -> node.id == selectedNodeId } }
            ?.takeIf(SourceNavigationAnchors::canNavigate)
        if (currentSceneSelection != null) {
            return currentSceneSelection
        }
        return graph.preferredArchitectureFocusNode()
            ?.takeIf(SourceNavigationAnchors::canNavigate)
            ?: graph.nodes.firstOrNull(SourceNavigationAnchors::canNavigate)
    }

    /**
     * 在架构图中挑选一个"推荐焦点"节点。
     * 优先返回带有源码样本的层级/服务/包/资源节点，便于在自动演示中聚焦有真实代码背景的元素。
     */
    private fun com.charmnight.linkgraph.model.GraphDocument.preferredArchitectureFocusNode(): GraphNode? {
        val sourceBackedAggregateTypes = listOf(
            NodeType.LAYER,
            NodeType.SERVICE,
            NodeType.PACKAGE,
            NodeType.RESOURCE,
        )
        sourceBackedAggregateTypes.forEach { nodeType ->
            nodes.firstOrNull { node ->
                node.type == nodeType && node.hasArchitectureSourceSamples()
            }?.let { return it }
        }
        return null
    }

    /** 判断节点是否带有架构源码样本（依据元数据中的样本计数）。 */
    private fun GraphNode.hasArchitectureSourceSamples(): Boolean =
        metadata["architecture.sourceSample.count"]?.toIntOrNull()?.let { count -> count > 0 } == true

    /**
     * 周期性轮询源码导航的最终结果，直到状态终结或达到最大尝试次数。
     * 主要用于自动测试场景下采集导航是否成功落点的诊断信息。
     */
    private fun logSourceNavigationResult(
        nodeId: String,
        attempt: Int = 0,
    ) {
        val state = project.getService(GraphEditorStateService::class.java).snapshot().sourceNavigationState
        if (state.nodeId == nodeId && state.phase != SourceNavigationPhase.IDLE && state.phase != SourceNavigationPhase.RUNNING) {
            logger.warn(
                "debug 自动源码导航结果: nodeId=$nodeId, phase=${state.phase}, result=${state.result}, targetPath=${state.targetPath}, line=${state.line}, column=${state.column}, error=${state.errorMessage}",
            )
            return
        }
        if (attempt >= DEBUG_SOURCE_NAVIGATION_STATE_MAX_ATTEMPTS) {
            logger.warn(
                "debug 自动源码导航结果等待超时: nodeId=$nodeId, currentNodeId=${state.nodeId}, phase=${state.phase}, result=${state.result}, error=${state.errorMessage}",
            )
            return
        }
        schedule(DEBUG_SOURCE_NAVIGATION_STATE_POLL_MS) {
            logSourceNavigationResult(nodeId, attempt + 1)
        }
    }

    /**
     * 等待可展开调用节点出现在当前快照里。
     * 既支持直接命中 workspace invocation 节点，也支持从流程图投影节点回映到规范图节点。
     */
    private fun runWhenAutoExpandInvocationReady(
        requestedMethodSignature: String?,
        requestedInvocationSignature: String,
        attempt: Int = 0,
    ) {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val match = DebugAutoExpandInvocationLocator.find(snapshot, requestedInvocationSignature)
        if (match != null) {
            logger.warn(
                "debug 自动触发调用展开: " +
                    "requestedMethodSignature=${requestedMethodSignature.orEmpty()}, " +
                    "requestedInvocationSignature=$requestedInvocationSignature, " +
                    "matchedGraph=${match.matchedGraphName}, " +
                    "matchedNodeId=${match.matchedNode.id}, " +
                    "matchedNodeTitle=${GraphBrowserDiagnostics.summarizePayloadText(match.matchedNode.title)}, " +
                    "matchedNodeSignature=${match.matchedNode.signature.orEmpty()}, " +
                    "targetNodeId=${match.targetNode.id}, " +
                    "targetNodeTitle=${GraphBrowserDiagnostics.summarizePayloadText(match.targetNode.title)}, " +
                    "targetNodeSignature=${match.targetNode.signature.orEmpty()}, " +
                    "snapshot=${GraphBrowserDiagnostics.snapshotSummary(snapshot)}",
            )
            val beforeSnapshot = snapshot
            project.getService(GraphEditorApplicationService::class.java).commandDispatcher.dispatch(
                ApplicationCommand.RequestExpandInvocation(match.targetNode.id),
            )
            schedule(DEBUG_AUTO_EXPAND_INVOCATION_RESULT_POLL_MS) {
                logAutoExpandInvocationResult(
                    requestedMethodSignature = requestedMethodSignature,
                    requestedInvocationSignature = requestedInvocationSignature,
                    targetNodeId = match.targetNode.id,
                    previousSnapshot = beforeSnapshot,
                )
            }
            return
        }
        if (attempt >= DEBUG_AUTO_EXPAND_INVOCATION_MAX_ATTEMPTS) {
            logger.warn(
                "debug 自动调用展开放弃: " +
                    "requestedMethodSignature=${requestedMethodSignature.orEmpty()}, " +
                    "requestedInvocationSignature=$requestedInvocationSignature, " +
                    "attempt=$attempt, " +
                    "invocationCandidates=${sampleInvocationCandidates(snapshot)}, " +
                    "snapshot=${GraphBrowserDiagnostics.snapshotSummary(snapshot)}",
            )
            return
        }
        if (attempt == 0 || (attempt + 1) % DEBUG_AUTO_EXPAND_INVOCATION_LOG_INTERVAL == 0) {
            logger.warn(
                "debug 自动调用展开等待节点: " +
                    "requestedMethodSignature=${requestedMethodSignature.orEmpty()}, " +
                    "requestedInvocationSignature=$requestedInvocationSignature, " +
                    "attempt=${attempt + 1}, " +
                    "invocationCandidates=${sampleInvocationCandidates(snapshot)}, " +
                    "snapshot=${GraphBrowserDiagnostics.snapshotSummary(snapshot)}",
            )
        }
        schedule(DEBUG_AUTO_EXPAND_INVOCATION_POLL_MS) {
            runWhenAutoExpandInvocationReady(
                requestedMethodSignature = requestedMethodSignature,
                requestedInvocationSignature = requestedInvocationSignature,
                attempt = attempt + 1,
            )
        }
    }

    /**
     * 轮询一次自动调用展开的结果，输出图谱增量、反馈文案以及新增展开节点样本。
     */
    private fun logAutoExpandInvocationResult(
        requestedMethodSignature: String?,
        requestedInvocationSignature: String,
        targetNodeId: String,
        previousSnapshot: GraphEditorStateSnapshot,
        attempt: Int = 0,
    ) {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val expansionNodes = snapshot.workspaceGraph.nodes
            .filter { node ->
                node.metadata[InvocationExpansionUseCase.EXPANSION_SOURCE_INVOCATION_NODE_ID] == targetNodeId
            }
        val expansionEdges = snapshot.workspaceGraph.edges
            .filter { edge ->
                edge.metadata[InvocationExpansionUseCase.EXPANSION_SOURCE_INVOCATION_NODE_ID] == targetNodeId
            }
        val feedbackChanged = snapshot.operationFeedback?.message != previousSnapshot.operationFeedback?.message
        val workspaceChanged = snapshot.workspaceGraph != previousSnapshot.workspaceGraph
        if (expansionNodes.isNotEmpty() || expansionEdges.isNotEmpty() || feedbackChanged || workspaceChanged) {
            logger.warn(
                "debug 自动调用展开结果: " +
                    "requestedMethodSignature=${requestedMethodSignature.orEmpty()}, " +
                    "requestedInvocationSignature=$requestedInvocationSignature, " +
                    "targetNodeId=$targetNodeId, " +
                    "expansionNodes=${expansionNodes.size}[${sampleExpandedNodes(expansionNodes)}], " +
                    "expansionEdges=${expansionEdges.size}, " +
                    "delta=${GraphBrowserDiagnostics.snapshotDeltaSummary(previousSnapshot, snapshot)}, " +
                    "snapshot=${GraphBrowserDiagnostics.snapshotSummary(snapshot)}",
            )
            return
        }
        if (attempt >= DEBUG_AUTO_EXPAND_INVOCATION_RESULT_MAX_ATTEMPTS) {
            logger.warn(
                "debug 自动调用展开结果等待超时: " +
                    "requestedMethodSignature=${requestedMethodSignature.orEmpty()}, " +
                    "requestedInvocationSignature=$requestedInvocationSignature, " +
                    "targetNodeId=$targetNodeId, " +
                    "delta=${GraphBrowserDiagnostics.snapshotDeltaSummary(previousSnapshot, snapshot)}, " +
                    "snapshot=${GraphBrowserDiagnostics.snapshotSummary(snapshot)}",
            )
            return
        }
        schedule(DEBUG_AUTO_EXPAND_INVOCATION_RESULT_POLL_MS) {
            logAutoExpandInvocationResult(
                requestedMethodSignature = requestedMethodSignature,
                requestedInvocationSignature = requestedInvocationSignature,
                targetNodeId = targetNodeId,
                previousSnapshot = previousSnapshot,
                attempt = attempt + 1,
            )
        }
    }

    private fun sampleInvocationCandidates(snapshot: GraphEditorStateSnapshot): String {
        val candidates = sequenceOf(
            snapshot.workspaceGraph,
            snapshot.flowchartView.visibleGraph,
            snapshot.factGraphView.visibleGraph,
            snapshot.resourceRelationView.visibleGraph,
        ).flatMap { graph -> graph.nodes.asSequence() }
            .filter { node ->
                node.type == NodeType.FLOW_ACTION &&
                    (node.metadata["flow.kind"] == "INVOCATION" || !node.signature.isNullOrBlank())
            }
            .distinctBy(GraphNode::id)
            .take(DEBUG_AUTO_EXPAND_SAMPLE_LIMIT)
            .map { node ->
                "${node.id}:${node.metadata["flow.kind"].orEmpty()}:" +
                    GraphBrowserDiagnostics.summarizePayloadText(node.signature ?: node.title, 96)
            }
            .toList()
        return candidates.joinToString("|")
    }

    private fun sampleExpandedNodes(nodes: List<GraphNode>): String =
        nodes.take(DEBUG_AUTO_EXPAND_SAMPLE_LIMIT).joinToString("|") { node ->
            "${node.id}:${GraphBrowserDiagnostics.summarizePayloadText(node.signature ?: node.title, 96)}"
        }

    /**
     * 在后台线程执行动作，失败时仅记录告警，不向上抛出。
     * 入参 [actionLabel] 仅用于日志标识。
     */
    private fun runBackground(
        actionLabel: String,
        action: () -> Unit,
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching(action).onFailure { error ->
                logger.warn("debug 自动请求失败: $actionLabel", error)
            }
        }
    }

    /**
     * 等待架构图就绪后再执行动作。
     * 若节点数仍为空，则按固定间隔轮询，直到达到最大尝试次数后放弃并记录告警。
     */
    private fun runWhenArchitectureGraphReady(
        actionLabel: String,
        attempt: Int = 0,
        action: () -> Unit,
    ) {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        if (snapshot.architectureGraphView.visibleGraph.nodes.isNotEmpty()) {
            action()
            return
        }
        if (attempt >= DEBUG_GRAPH_READY_MAX_ATTEMPTS) {
            logger.warn("debug 自动请求放弃: $actionLabel, 架构图在等待窗口内仍为空")
            return
        }
        logger.warn("debug 自动请求等待架构图: $actionLabel, attempt=${attempt + 1}")
        schedule(DEBUG_GRAPH_READY_POLL_MS) {
            runWhenArchitectureGraphReady(actionLabel, attempt + 1, action)
        }
    }

    /**
     * 等待类图就绪后再执行动作。
     * 与 [runWhenArchitectureGraphReady] 行为一致，只是面向类图视图。
     */
    private fun runWhenClassDiagramReady(
        actionLabel: String,
        attempt: Int = 0,
        action: () -> Unit,
    ) {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        if (snapshot.classDiagramView.visibleGraph.nodes.isNotEmpty()) {
            action()
            return
        }
        if (attempt >= DEBUG_GRAPH_READY_MAX_ATTEMPTS) {
            logger.warn("debug 自动请求放弃: $actionLabel, 类图在等待窗口内仍为空")
            return
        }
        logger.warn("debug 自动请求等待类图: $actionLabel, attempt=${attempt + 1}")
        schedule(DEBUG_GRAPH_READY_POLL_MS) {
            runWhenClassDiagramReady(actionLabel, attempt + 1, action)
        }
    }

    /**
     * 在调度线程上延迟执行动作，并切换到智能模式下的项目线程。
     * 如果项目已被释放则直接跳过，避免在已关闭的项目上触发命令。
     */
    private fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                DumbService.getInstance(project).smartInvokeLater {
                    if (project.isDisposed) {
                        return@smartInvokeLater
                    }
                    action()
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private companion object {
        /** 调试自动载图前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTOLOAD_DELAY_MS = 3000L
        /** 触发架构图请求前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_DELAY_MS = 3000L
        /** 触发架构图讲解前等待图就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_DELAY_MS = 7000L
        /** 触发架构图问答前等待图就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_DELAY_MS = 9000L
        /** 触发类图请求前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_DELAY_MS = 5000L
        /** 触发类使用处请求前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_USAGE_DELAY_MS = 5000L
        /** 触发类图讲解前等待图就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_DELAY_MS = 9000L
        /** 触发类图问答前等待图就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_DELAY_MS = 12000L
        /** 触发源码导航前等待图就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_DELAY_MS = 7000L
        /** 等待图就绪时的轮询间隔（毫秒）。 */
        private const val DEBUG_GRAPH_READY_POLL_MS = 2000L
        /** 等待图就绪的最大轮询次数。 */
        private const val DEBUG_GRAPH_READY_MAX_ATTEMPTS = 180
        /** 源码导航状态轮询间隔（毫秒）。 */
        private const val DEBUG_SOURCE_NAVIGATION_STATE_POLL_MS = 1000L
        /** 源码导航状态轮询最大次数。 */
        private const val DEBUG_SOURCE_NAVIGATION_STATE_MAX_ATTEMPTS = 30
        /** 自动调用展开的初始等待时间（毫秒）。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_DELAY_MS = 5000L
        /** 自动调用展开等待图就绪时的轮询间隔（毫秒）。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_POLL_MS = 2000L
        /** 自动调用展开等待节点出现的最大轮询次数。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_MAX_ATTEMPTS = 180
        /** 自动调用展开结果轮询间隔（毫秒）。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_RESULT_POLL_MS = 1000L
        /** 自动调用展开结果轮询最大次数。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_RESULT_MAX_ATTEMPTS = 60
        /** 自动调用展开等待日志的输出间隔。 */
        private const val DEBUG_AUTO_EXPAND_INVOCATION_LOG_INTERVAL = 10
        /** 调试日志里保留的节点样本上限。 */
        private const val DEBUG_AUTO_EXPAND_SAMPLE_LIMIT = 8
        /** 触发生成计划前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_PLAN_DELAY_MS = 6000L
        /** 触发生成代码草稿前等待界面就绪的延迟（毫秒）。 */
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS = 10000L
        /** 协调器日志器。 */
        private val logger = Logger.getInstance(LinkGraphDebugAutomationCoordinator::class.java)
    }
}
