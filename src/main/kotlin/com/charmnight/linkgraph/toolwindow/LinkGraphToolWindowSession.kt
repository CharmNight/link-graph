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
 * 统一负责 GraphBrowserPanel 创建、释放、工具窗口打开与状态同步。
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
        return browserPanelHost.getOrCreate()
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
}
