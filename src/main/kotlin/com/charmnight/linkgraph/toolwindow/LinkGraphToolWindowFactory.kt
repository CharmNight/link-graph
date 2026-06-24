package com.charmnight.linkgraph.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 负责创建链路图工具窗口内容。
 *
 * 实现 IntelliJ ToolWindowFactory 接口，在用户首次打开工具窗口时被调用。
 * 继承 DumbAware 让窗口在 dumb 模式（索引未完成）下也可显示，
 * 因为前端 webview 自身可以处理这种状态。
 */
class LinkGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    /**
     * 初始化工具窗口面板并挂接到 IDEA 内容管理器。
     *
     * @param project 当前项目
     * @param toolWindow 待填充的工具窗口对象
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowSession = project.getService(LinkGraphToolWindowSession::class.java)
        // 已经存在内容时不重复创建，避免工具窗口被重复初始化。
        if (toolWindow.contentManager.contentCount > 0) {
            return
        }
        // 取得或惰性创建浏览器面板（JCEF 包装）
        val panel = toolWindowSession.getOrCreateBrowserPanel()
        // 把浏览器面板包装为 IDEA Content 后挂到工具窗口中。
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // 安装销毁回调，确保工具窗口关闭时浏览器面板也被正确清理
        toolWindowSession.installPanelDisposer(content)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** 定义 IDEA 中展示的工具窗口标识。 */
        const val TOOL_WINDOW_ID: String = "链路图"
    }
}
