package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.services.IntelliJUiThreadExecutor
import com.charmnight.linkgraph.services.GraphEditorSyncNotifier
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.services.UiThreadOwnedResource
import com.charmnight.linkgraph.services.debugLazy
import com.charmnight.linkgraph.ui.GraphBrowserPanel
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * Tool window 侧的浏览器会话宿主。
 * 统一负责 GraphBrowserPanel 创建、释放、工具窗口打开以及调试自动载图编排。
 */
@Service(Service.Level.PROJECT)
internal class LinkGraphToolWindowSession(
    private val project: Project,
) : Disposable {
    private val browserPanelHost = UiThreadOwnedResource(
        uiThreadExecutor = IntelliJUiThreadExecutor(),
        factory = { GraphBrowserPanel(project) },
        disposer = { panel -> Disposer.dispose(panel) },
    )

    @Volatile
    private var debugGraphAutoloadScheduled: Boolean = false

    init {
        project.messageBus.connect(this).subscribe(
            GraphEditorSyncNotifier.TOPIC,
            object : GraphEditorSyncNotifier.Listener {
                override fun onSyncRequested() {
                    syncFromProjectState()
                }
            },
        )
    }

    fun getOrCreateBrowserPanel(): GraphBrowserPanel {
        return browserPanelHost.getOrCreate { panel ->
            scheduleDebugGraphAutoloadIfRequested(panel)
            scheduleDebugWorkbenchAutomationIfRequested(panel)
        }
    }

    fun installPanelDisposer(content: Content) {
        content.setDisposer(Disposable {
            releaseBrowserPanel()
        })
    }

    fun openToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) ?: return
        project.getService(GraphEditorStateService::class.java).markToolWindowOpened()
        toolWindow.show()
        toolWindow.activate(null)
    }

    fun syncFromProjectState() {
        browserPanelHost.withExisting { panel ->
            if (project.isDisposed) {
                return@withExisting
            }
            panel.syncFromProjectState()
        }
    }

    fun releaseBrowserPanel() {
        browserPanelHost.release()
    }

    override fun dispose() {
        releaseBrowserPanel()
    }

    private fun scheduleDebugGraphAutoloadIfRequested(panel: GraphBrowserPanel) {
        val projectService = project.getService(LinkGraphProjectService::class.java)
        projectService.prepareDebugRequestedAnalysisDisplayModeIfPresent()

        val methodSignature = System.getenv(DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (methodSignature != null) {
            if (debugGraphAutoloadScheduled) {
                return
            }
            debugGraphAutoloadScheduled = true
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到真实方法调试自动载图环境变量 $DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV=$methodSignature，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后提取真实方法链路"
            }
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    DumbService.getInstance(project).smartInvokeLater {
                        if (project.isDisposed || !browserPanelHost.isCurrent(panel)) {
                            return@smartInvokeLater
                        }
                        projectService.loadDebugMethodGraphBySignatureAsync(methodSignature)
                    }
                },
                DEBUG_AUTOLOAD_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
            return
        }

        val mode = System.getenv(DEBUG_AUTOLOAD_GRAPH_ENV)
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: return
        if (debugGraphAutoloadScheduled) {
            return
        }
        debugGraphAutoloadScheduled = true
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "检测到调试自动载图环境变量 $DEBUG_AUTOLOAD_GRAPH_ENV=$mode，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后注入诊断链路图"
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                DumbService.getInstance(project).smartInvokeLater {
                    if (project.isDisposed || !browserPanelHost.isCurrent(panel)) {
                        return@smartInvokeLater
                    }
                    projectService.loadDebugGraph(mode)
                }
            },
            DEBUG_AUTOLOAD_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleDebugWorkbenchAutomationIfRequested(panel: GraphBrowserPanel) {
        val autoRequestPlan = debugFlag(DEBUG_AUTO_REQUEST_PLAN_ENV)
        val autoRequestCodeDrafts = debugFlag(DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV)
        if (!autoRequestPlan && !autoRequestCodeDrafts) {
            return
        }
        val projectService = project.getService(LinkGraphProjectService::class.java)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "检测到调试工作台自动请求环境变量: " +
                "$DEBUG_AUTO_REQUEST_PLAN_ENV=$autoRequestPlan, " +
                "$DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV=$autoRequestCodeDrafts"
        }
        if (autoRequestPlan) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    DumbService.getInstance(project).smartInvokeLater {
                        if (project.isDisposed || !browserPanelHost.isCurrent(panel)) {
                            return@smartInvokeLater
                        }
                        debugLazy(logger.isDebugEnabled, logger::debug) { "开始执行调试自动请求：生成计划" }
                        projectService.requestGenerationPlanAsync()
                    }
                },
                DEBUG_AUTO_REQUEST_PLAN_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        if (autoRequestCodeDrafts) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    DumbService.getInstance(project).smartInvokeLater {
                        if (project.isDisposed || !browserPanelHost.isCurrent(panel)) {
                            return@smartInvokeLater
                        }
                        debugLazy(logger.isDebugEnabled, logger::debug) { "开始执行调试自动请求：生成代码草稿" }
                        projectService.requestCodeDraftsAsync()
                    }
                },
                DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun debugFlag(envName: String): Boolean {
        return System.getenv(envName)?.trim()?.equals("true", ignoreCase = true) == true
    }

    private companion object {
        private const val DEBUG_AUTOLOAD_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_GRAPH"
        private const val DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_METHOD_SIGNATURE"
        private const val DEBUG_AUTO_REQUEST_PLAN_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"
        private const val DEBUG_AUTOLOAD_DELAY_MS = 3000L
        private const val DEBUG_AUTO_REQUEST_PLAN_DELAY_MS = 6000L
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS = 10000L
        private val logger = Logger.getInstance(LinkGraphToolWindowSession::class.java)
    }
}
