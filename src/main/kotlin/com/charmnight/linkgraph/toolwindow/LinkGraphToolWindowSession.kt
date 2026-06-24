package com.charmnight.linkgraph.toolwindow

import com.charmnight.linkgraph.toolwindow.IntelliJUiThreadExecutor
import com.charmnight.linkgraph.ui.GraphEditorSyncNotifier
import com.charmnight.linkgraph.toolwindow.UiThreadOwnedResource
import com.charmnight.linkgraph.ui.GraphBrowserPanel
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content

/**
 * Tool window 侧的浏览器会话宿主。
 *
 * 统一负责 GraphBrowserPanel 创建、释放、工具窗口打开与状态同步。
 * 使用项目级 @Service 保证每个项目只有一个会话实例。
 *
 * 通过订阅 [GraphEditorSyncNotifier] 接收同步请求，
 * 把后端状态变化推送到前端浏览器。
 */
@Service(Service.Level.PROJECT)
internal class LinkGraphToolWindowSession(
    /** 当前项目。 */
    private val project: Project,
) : Disposable {
    /**
     * 浏览器面板的 UI 线程独占资源包装。
     * 保证面板的创建与释放都在 EDT 上执行，符合 Swing 单线程模型。
     */
    private val browserPanelHost = UiThreadOwnedResource(
        uiThreadExecutor = IntelliJUiThreadExecutor(),
        factory = { GraphBrowserPanel(project) },
        disposer = { panel -> Disposer.dispose(panel) },
    )

    init {
        // 订阅同步请求：收到请求时把项目状态推送到浏览器
        project.messageBus.connect(this).subscribe(
            GraphEditorSyncNotifier.TOPIC,
            object : GraphEditorSyncNotifier.Listener {
                override fun onSyncRequested() {
                    syncFromProjectState()
                }
            },
        )
    }

    /** 取得或惰性创建浏览器面板。 */
    fun getOrCreateBrowserPanel(): GraphBrowserPanel {
        return browserPanelHost.getOrCreate()
    }

    /**
     * 给 Content 安装销毁回调。
     * 当 Content 被销毁时（例如工具窗口关闭），同时释放浏览器面板。
     */
    fun installPanelDisposer(content: Content) {
        content.setDisposer(Disposable {
            releaseBrowserPanel()
        })
    }

    /**
     * 打开链路图工具窗口。
     * 同时标记状态服务"工具窗口已打开"，并激活窗口让用户看到。
     */
    fun openToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LinkGraphToolWindowFactory.TOOL_WINDOW_ID) ?: return
        project.getService(GraphEditorStateService::class.java).markToolWindowOpened()
        toolWindow.show()
        toolWindow.activate(null)
    }

    /**
     * 把项目当前状态同步到浏览器（如果浏览器已存在）。
     * 项目已销毁时跳过，避免触发异常。
     */
    fun syncFromProjectState() {
        browserPanelHost.withExisting { panel ->
            if (project.isDisposed) {
                return@withExisting
            }
            panel.syncFromProjectState()
        }
    }

    /** 释放浏览器面板资源。无活动面板时为空操作。 */
    fun releaseBrowserPanel() {
        browserPanelHost.release()
    }

    /** Disposable 接口实现：作为 IntelliJ 服务被销毁时释放面板。 */
    override fun dispose() {
        releaseBrowserPanel()
    }
}
