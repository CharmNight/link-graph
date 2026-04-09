package com.charmnight.linkgraph.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 仅供真实调试时使用的启动活动。
 * 当显式打开环境变量时，项目启动后自动打开链路图工具窗，
 * 这样 `runIde` 调试就不需要依赖系统自动化去点菜单。
 */
class LinkGraphDebugStartupActivity(
    /** 保存是否自动打开工具窗口的判定逻辑。 */
    private val autoOpenEnabled: () -> Boolean = {
        System.getenv(DEBUG_AUTOOPEN_ENV)?.trim()?.equals("true", ignoreCase = true) == true
    },
) : StartupActivity.DumbAware {
    /**
     * 在项目启动完成后按需自动打开工具窗口。
     */
    override fun runActivity(project: Project) {
        if (!autoOpenEnabled()) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            // 项目销毁后不再尝试打开工具窗口，避免无效调用。
            if (project.isDisposed) {
                return@invokeLater
            }
            project.getService(LinkGraphToolWindowSession::class.java).openToolWindow()
        }
    }

    companion object {
        /** 定义控制自动打开行为的环境变量名。 */
        const val DEBUG_AUTOOPEN_ENV: String = "LINKGRAPH_DEBUG_AUTOOPEN"
    }
}
