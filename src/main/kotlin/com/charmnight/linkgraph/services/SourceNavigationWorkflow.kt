package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理源码跳转与设置页打开流程。
 */
internal class SourceNavigationWorkflow(
    /** 当前项目。 */
    private val project: Project,
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 源码跳转服务提供器。 */
    private val sourceNavigationServiceProvider: () -> SourceNavigationService,
    /** 节点查找器。 */
    private val navigationNodeFinder: (GraphEditorStateService.Snapshot, String) -> GraphNode?,
    /** 实际打开设置页动作。 */
    private val showSettingsDialog: () -> Unit,
    /** 日志记录器。 */
    private val logger: Logger,
) {
    /**
     * 按节点位置尝试打开源码，并把结果明确回写到前端状态。
     */
    fun requestSourceNavigation(nodeId: String): SourceNavigationService.NavigationTarget? {
        val snapshot = session.snapshot()
        val node = navigationNodeFinder(snapshot, nodeId)
        val requestStartedAt = System.nanoTime()
        session.mutate(syncBrowser = false) {
            requestSourceNavigation(nodeId)
        }
        when {
            node == null -> {
                session.mutateBatch {
                    apply {
                        markSourceNavigationFailed(nodeId, "未找到节点，无法打开源码。")
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.WARNING,
                            "未找到节点，无法打开源码。",
                        )
                    }
                }
                return null
            }

            !canNavigateToSource(node) -> {
                session.mutateBatch {
                    apply {
                        markSourceNavigationFailed(nodeId, "节点 ${node.title} 暂无可跳转的源码位置。")
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.WARNING,
                            "节点 ${node.title} 暂无可跳转的源码位置。",
                        )
                    }
                }
                return null
            }
        }

        session.mutate {
            markOperationFeedback(
                OperationFeedbackLevel.INFO,
                "正在定位源码：${node.title}",
            )
        }

        val navigationService = sourceNavigationServiceProvider()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val resolveStartedAt = System.nanoTime()
                val resolvedTarget = ReadAction.compute<SourceNavigationService.NavigationTarget?, RuntimeException> {
                    navigationService.resolve(node)
                }
                val resolveDurationMs = (System.nanoTime() - resolveStartedAt) / 1_000_000
                if (resolvedTarget == null) {
                    logger.info(
                        "源码定位未命中: nodeId=$nodeId, title=${node.title}, resolveMs=$resolveDurationMs, totalMs=${(System.nanoTime() - requestStartedAt) / 1_000_000}",
                    )
                    null
                } else {
                    val openStartedAt = System.nanoTime()
                    val openedTarget = navigationService.open(resolvedTarget)
                    logger.info(
                        "源码定位完成: nodeId=$nodeId, title=${node.title}, resolveMs=$resolveDurationMs, openMs=${(System.nanoTime() - openStartedAt) / 1_000_000}, totalMs=${(System.nanoTime() - requestStartedAt) / 1_000_000}, target=${openedTarget?.filePath}:${openedTarget?.line}:${openedTarget?.column}",
                    )
                    openedTarget
                }
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    result.fold(
                        onSuccess = { target ->
                            if (target != null) {
                                session.mutateBatch {
                                    apply {
                                        markSourceNavigationOpened(
                                            nodeId = nodeId,
                                            targetPath = target.filePath,
                                            line = target.line,
                                            column = target.column,
                                        )
                                    }
                                    apply {
                                        markOperationFeedback(
                                            OperationFeedbackLevel.SUCCESS,
                                            "已打开源码：${node.title}",
                                        )
                                    }
                                }
                            } else {
                                session.mutateBatch {
                                    apply {
                                        markSourceNavigationNotFound(nodeId)
                                    }
                                    apply {
                                        markOperationFeedback(
                                            OperationFeedbackLevel.WARNING,
                                            "未找到源码位置：${node.location ?: node.signature ?: node.title}",
                                        )
                                    }
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("打开源码失败", throwable)
                            val errorMessage = throwable.message ?: throwable.javaClass.simpleName
                            session.mutateBatch {
                                apply {
                                    markSourceNavigationFailed(nodeId, errorMessage)
                                }
                                apply {
                                    markOperationFeedback(
                                        OperationFeedbackLevel.ERROR,
                                        "打开源码失败：$errorMessage",
                                    )
                                }
                            }
                        },
                    )
                },
                ModalityState.defaultModalityState(),
            )
        }
        return null
    }

    /**
     * 打开插件设置页。
     */
    fun openSettings() {
        runCatching {
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.SUCCESS,
                    "已打开 IDE 设置 > Link Graph。",
                )
            }
            showSettingsDialog()
        }.onFailure { throwable ->
            logger.warn("打开 Link Graph 设置失败", throwable)
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.ERROR,
                    "打开插件设置失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * 直接按节点信息跳转源码。
     */
    fun navigate(node: GraphNode): SourceNavigationService.NavigationTarget? {
        return sourceNavigationServiceProvider().navigate(node)
    }

    /**
     * 判断节点是否具备源码跳转条件。
     */
    private fun canNavigateToSource(node: GraphNode): Boolean {
        if (!node.location.isNullOrBlank()) {
            return true
        }
        return (node.type == NodeType.METHOD || node.type == NodeType.CLASS) && !node.signature.isNullOrBlank()
    }
}
