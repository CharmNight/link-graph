package com.charmnight.linkgraph.toolwindow.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 仅供 debug 描述符显式注册的启动活动。
 *
 * 默认发布路径不会注册这个入口，仅在专门的 debug run configuration 下加载，
 * 用于在启动时自动执行调试自动化（例如加载示例图、回放某种交互）。
 * 这种隔离避免调试代码污染正常用户路径。
 *
 * @param requestProvider 返回当前环境下的调试自动化请求；默认从环境变量解析
 * @param startupAction 实际调度动作；默认提交给协调器
 */
class LinkGraphDebugStartupActivity(
    private val requestProvider: () -> LinkGraphDebugAutomationRequest = {
        LinkGraphDebugAutomationRequest.fromEnvironment()
    },
    private val startupAction: (Project, LinkGraphDebugAutomationRequest) -> Unit = { project, request ->
        project.getService(LinkGraphDebugAutomationCoordinator::class.java).scheduleIfRequested(request)
    },
) : StartupActivity.DumbAware {
    /**
     * 启动时被调用。
     * 先解析请求，没有任何动作时直接返回；
     * 否则在 EDT 上调度实际动作（避免在启动线程做重活）。
     */
    override fun runActivity(project: Project) {
        val request = requestProvider()
        // 无任何调试动作时跳过，避免无意义调度
        if (!request.hasAnyAction) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            // 项目已关闭时跳过，避免抛出 AlreadyDisposedException
            if (project.isDisposed) {
                return@invokeLater
            }
            startupAction(project, request)
        }
    }
}
