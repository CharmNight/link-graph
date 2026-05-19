package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.QaMode
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
    private val automationScheduled = AtomicBoolean(false)

    fun scheduleIfRequested(request: LinkGraphDebugAutomationRequest) {
        if (!request.hasAnyAction) {
            return
        }
        if (!automationScheduled.compareAndSet(false, true)) {
            return
        }

        val toolWindowSession = project.getService(LinkGraphToolWindowSession::class.java)
        val applicationService = project.getService(GraphEditorApplicationService::class.java)
        if (request.requiresToolWindowOpen) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "debug 入口触发工具窗口自动打开" }
            toolWindowSession.openToolWindow()
        }

        if (request.autoloadMethodSignature != null || request.autoloadGraphMode != null) {
            applicationService.prepareDebugRequestedAnalysisDisplayModeIfPresent(
                GraphEditorApplicationService.DEBUG_ANALYSIS_DISPLAY_MODE_ENV,
            )
        }

        request.autoloadMethodSignature?.let { signature ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到真实方法调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后提取真实方法链路: $signature"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                applicationService.loadDebugMethodGraphBySignatureAsync(signature)
            }
            return
        }

        request.autoloadGraphMode?.let { mode ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后注入诊断链路图: $mode"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                applicationService.loadDebugGraph(mode)
            }
        }

        if (request.autoRequestArchitectureGraph) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：架构图" }
            schedule(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_DELAY_MS) {
                applicationService.requestArchitectureGraph()
            }
        }

        if (request.autoRequestArchitectureGraphBeautification) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：架构图讲解" }
            schedule(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_DELAY_MS) {
                runWhenArchitectureGraphReady("架构图讲解") {
                    val focusNodeId = currentArchitectureGraphFocusNodeId()
                    logger.warn("debug 自动触发架构图讲解: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("架构图讲解") {
                        applicationService.requestGraphBeautificationAsync(
                            goal = "验证架构图讲解上下文",
                            preferredStyle = "调试验证",
                            explanationFocus = "请只围绕当前架构图中的模块、包、层和资源关系解释。",
                            focusNodeId = focusNodeId,
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
                        applicationService.requestQaAsync(
                            question = "请说明当前架构图里这个节点和相邻模块/包/资源是什么关系。",
                            selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            mode = QaMode.ANSWER,
                        )
                    }
                }
            }
        }

        if (request.autoRequestClassDiagram) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：类图" }
            schedule(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_DELAY_MS) {
                applicationService.requestClassDiagram()
            }
        }

        if (request.autoRequestClassDiagramBeautification) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：类图讲解" }
            schedule(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_DELAY_MS) {
                runWhenClassDiagramReady("类图讲解") {
                    val focusNodeId = currentClassDiagramFocusNodeId()
                    logger.warn("debug 自动触发类图讲解: focusNodeId=${focusNodeId.orEmpty()}")
                    runBackground("类图讲解") {
                        applicationService.requestGraphBeautificationAsync(
                            goal = "验证类图讲解上下文",
                            preferredStyle = "调试验证",
                            explanationFocus = "请只围绕当前类图中的类、接口、枚举和它们的关系解释。",
                            focusNodeId = focusNodeId,
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
                        applicationService.requestQaAsync(
                            question = "请说明当前类图里这个节点和相邻类/接口是什么关系。",
                            selectedNodeIds = focusNodeId?.let(::listOf).orEmpty(),
                            mode = QaMode.ANSWER,
                        )
                    }
                }
            }
        }

        if (request.autoRequestPlan) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成计划" }
            schedule(DEBUG_AUTO_REQUEST_PLAN_DELAY_MS) {
                applicationService.requestGenerationPlanAsync()
            }
        }

        if (request.autoRequestCodeDrafts) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成代码草稿" }
            schedule(DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS) {
                applicationService.requestCodeDraftsAsync()
            }
        }
    }

    private fun currentClassDiagramFocusNodeId(): String? {
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val currentSceneSelection = snapshot.currentSceneState().selectedNodeId
        return currentSceneSelection
            ?.takeIf { nodeId -> snapshot.classDiagramView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.classDiagramView.anchorNodeId
                ?.takeIf { nodeId -> snapshot.classDiagramView.visibleGraph.nodes.any { node -> node.id == nodeId } }
            ?: snapshot.classDiagramView.visibleGraph.nodes.firstOrNull()?.id
    }

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

    private fun GraphNode.hasArchitectureSourceSamples(): Boolean =
        metadata["architecture.sourceSample.count"]?.toIntOrNull()?.let { count -> count > 0 } == true

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
        private const val DEBUG_AUTOLOAD_DELAY_MS = 3000L
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_DELAY_MS = 3000L
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_DELAY_MS = 7000L
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_DELAY_MS = 9000L
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_DELAY_MS = 5000L
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_DELAY_MS = 9000L
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_DELAY_MS = 12000L
        private const val DEBUG_GRAPH_READY_POLL_MS = 2000L
        private const val DEBUG_GRAPH_READY_MAX_ATTEMPTS = 60
        private const val DEBUG_AUTO_REQUEST_PLAN_DELAY_MS = 6000L
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS = 10000L
        private val logger = Logger.getInstance(LinkGraphDebugAutomationCoordinator::class.java)
    }
}
