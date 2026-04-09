package com.charmnight.linkgraph.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 负责创建链路图工具窗口内容。
 */
class LinkGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    /**
     * 初始化工具窗口面板并挂接到 IDEA 内容管理器。
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowSession = project.getService(LinkGraphToolWindowSession::class.java)
        if (toolWindow.contentManager.contentCount > 0) {
            return
        }
        // 已经存在内容时不重复创建，避免工具窗口被重复初始化。
        val panel = toolWindowSession.getOrCreateBrowserPanel()
        // 把浏览器面板包装为 IDEA Content 后挂到工具窗口中。
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindowSession.installPanelDisposer(content)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** 定义 IDEA 中展示的工具窗口标识。 */
        const val TOOL_WINDOW_ID: String = "链路图"
    }
}
