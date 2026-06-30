package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.usecase.SourceNavigationUseCase
import com.charmnight.linkgraph.application.usecase.SourceNavigationUseCaseResult
import com.charmnight.linkgraph.application.runtime.SameThreadTaskRunner
import com.charmnight.linkgraph.application.runtime.TaskRunner
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理源码跳转与设置页打开流程。
 */
internal class SourceNavigationWorkflow(
    /** 当前项目。 */
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    /** 源码跳转服务提供器。 */
    private val sourceNavigationServiceProvider: () -> SourceNavigationService,
    /** 节点查找器。 */
    private val navigationNodeFinder: (WorkflowEditorSnapshot, String) -> GraphNode?,
    /** 实际打开设置页动作。 */
    private val showSettingsDialog: () -> Unit,
    /** 日志记录器。 */
    private val logger: Logger,
    /** 平台无关任务调度入口。 */
    private val taskRunner: TaskRunner = SameThreadTaskRunner(),
) {
    private val useCase = SourceNavigationUseCase(navigationNodeFinder)

    /**
     * 按节点位置尝试打开源码，并把结果明确回写到前端状态。
     */
    fun requestSourceNavigation(nodeId: String): SourceNavigationService.NavigationTarget? {
        val snapshot = snapshotProvider.snapshot()
        val requestStartedAt = System.nanoTime()
        eventSink.emit(GraphEditorApplicationEvent.NavigationRequested(nodeId))
        val node = when (val result = useCase.requestSourceNavigation(snapshot, nodeId)) {
            is SourceNavigationUseCaseResult.MissingTrustedNode -> {
                eventSink.emit(
                    GraphEditorApplicationEvent.NavigationFailed(
                        nodeId = nodeId,
                        message = "当前节点没有可信源码锚点，无法打开源码。",
                        statusMessage = "当前节点没有可信源码锚点，无法打开源码。",
                        level = ApplicationFeedbackLevel.WARNING,
                    ),
                )
                return null
            }
            is SourceNavigationUseCaseResult.NotNavigable -> {
                eventSink.emit(
                    GraphEditorApplicationEvent.NavigationFailed(
                        nodeId = nodeId,
                        message = "节点 ${result.node.title} 暂无可跳转的源码位置。",
                        statusMessage = "节点 ${result.node.title} 暂无可跳转的源码位置。",
                        level = ApplicationFeedbackLevel.WARNING,
                    ),
                )
                return null
            }
            is SourceNavigationUseCaseResult.Ready -> result.node
            SourceNavigationUseCaseResult.SettingsOpenRequested -> return null
        }

        eventSink.emit(GraphEditorApplicationEvent.NavigationStarting(node.title))

        val navigationService = sourceNavigationServiceProvider()
        taskRunner.background {
            val result = runCatching {
                val resolveStartedAt = System.nanoTime()
                val resolvedTarget = taskRunner.read {
                    navigationService.resolve(node)
                }
                val resolveDurationMs = (System.nanoTime() - resolveStartedAt) / 1_000_000
                if (resolvedTarget == null) {
                    debugLazy(logger.isDebugEnabled, logger::debug) {
                        "源码定位未命中: nodeId=$nodeId, title=${node.title}, resolveMs=$resolveDurationMs, totalMs=${(System.nanoTime() - requestStartedAt) / 1_000_000}"
                    }
                    null
                } else {
                    val openStartedAt = System.nanoTime()
                    val openedTarget = navigationService.open(resolvedTarget)
                    debugLazy(logger.isDebugEnabled, logger::debug) {
                        "源码定位完成: nodeId=$nodeId, title=${node.title}, resolveMs=$resolveDurationMs, openMs=${(System.nanoTime() - openStartedAt) / 1_000_000}, totalMs=${(System.nanoTime() - requestStartedAt) / 1_000_000}, target=${openedTarget?.filePath}:${openedTarget?.line}:${openedTarget?.column}"
                    }
                    openedTarget
                }
            }

            taskRunner.ui(TaskRunner.UiPolicy.ANY) {
                result.fold(
                    onSuccess = { target ->
                        if (target != null) {
                            eventSink.emit(
                                GraphEditorApplicationEvent.NavigationOpened(
                                    nodeId = nodeId,
                                    targetPath = target.filePath,
                                    line = target.line,
                                    column = target.column,
                                    title = node.title,
                                ),
                            )
                        } else {
                            eventSink.emit(
                                GraphEditorApplicationEvent.NavigationNotFound(
                                    nodeId = nodeId,
                                    label = node.location ?: node.signature ?: node.title,
                                ),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        logger.warn("打开源码失败", throwable)
                        val errorMessage = throwable.message ?: throwable.javaClass.simpleName
                        eventSink.emit(GraphEditorApplicationEvent.NavigationFailed(nodeId, errorMessage))
                    },
                )
            }
        }
        return null
    }

    /**
     * 打开插件设置页。
     */
    fun openSettings() {
        runCatching {
            if (useCase.requestOpenSettings() == SourceNavigationUseCaseResult.SettingsOpenRequested) {
                eventSink.emit(GraphEditorApplicationEvent.SettingsOpened)
            }
            showSettingsDialog()
        }.onFailure { throwable ->
            logger.warn("打开 Link Graph 设置失败", throwable)
            eventSink.emit(GraphEditorApplicationEvent.SettingsOpenFailed(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    /**
     * 直接按节点信息跳转源码。
     */
    fun navigate(node: GraphNode): SourceNavigationService.NavigationTarget? {
        return sourceNavigationServiceProvider().navigate(node)
    }
}
