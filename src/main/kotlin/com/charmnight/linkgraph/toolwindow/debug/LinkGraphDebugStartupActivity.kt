package com.charmnight.linkgraph.toolwindow.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 仅供 debug 描述符显式注册的启动活动。
 * 默认发布路径不会注册这个入口。
 */
class LinkGraphDebugStartupActivity(
    private val requestProvider: () -> LinkGraphDebugAutomationRequest = {
        LinkGraphDebugAutomationRequest.fromEnvironment()
    },
    private val startupAction: (Project, LinkGraphDebugAutomationRequest) -> Unit = { project, request ->
        project.getService(LinkGraphDebugAutomationCoordinator::class.java).scheduleIfRequested(request)
    },
) : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val request = requestProvider()
        if (!request.hasAnyAction) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            startupAction(project, request)
        }
    }
}
