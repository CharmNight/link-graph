package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.services.debugLazy
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅供 debug 入口使用的启动后自动化编排器。
 * 所有调试环境变量驱动的自动开窗、自动载图和自动请求都集中在这里。
 */
@Service(Service.Level.PROJECT)
internal class LinkGraphDebugAutomationCoordinator(
    private val project: Project,
) {
    private val automationScheduled = AtomicBoolean(false)

    fun scheduleIfRequested(request: LinkGraphDebugAutomationRequest) {
        if (!request.hasAnyAction) {
            return
        }
        if (!automationScheduled.compareAndSet(false, true)) {
            return
        }

        val toolWindowSession = project.getService(LinkGraphToolWindowSession::class.java)
        val projectService = project.getService(LinkGraphProjectService::class.java)
        if (request.requiresToolWindowOpen) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "debug 入口触发工具窗口自动打开" }
            toolWindowSession.openToolWindow()
        }

        if (request.autoloadMethodSignature != null || request.autoloadGraphMode != null) {
            projectService.prepareDebugRequestedAnalysisDisplayModeIfPresent()
        }

        request.autoloadMethodSignature?.let { signature ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到真实方法调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后提取真实方法链路: $signature"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                projectService.loadDebugMethodGraphBySignatureAsync(signature)
            }
            return
        }

        request.autoloadGraphMode?.let { mode ->
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "检测到调试自动载图请求，将在 ${DEBUG_AUTOLOAD_DELAY_MS}ms 后注入诊断链路图: $mode"
            }
            schedule(DEBUG_AUTOLOAD_DELAY_MS) {
                projectService.loadDebugGraph(mode)
            }
        }

        if (request.autoRequestPlan) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成计划" }
            schedule(DEBUG_AUTO_REQUEST_PLAN_DELAY_MS) {
                projectService.requestGenerationPlanAsync()
            }
        }

        if (request.autoRequestCodeDrafts) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "检测到调试自动请求：生成代码草稿" }
            schedule(DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS) {
                projectService.requestCodeDraftsAsync()
            }
        }
    }

    private fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                DumbService.getInstance(project).smartInvokeLater {
                    if (project.isDisposed) {
                        return@smartInvokeLater
                    }
                    action()
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private companion object {
        private const val DEBUG_AUTOLOAD_DELAY_MS = 3000L
        private const val DEBUG_AUTO_REQUEST_PLAN_DELAY_MS = 6000L
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_DELAY_MS = 10000L
        private val logger = Logger.getInstance(LinkGraphDebugAutomationCoordinator::class.java)
    }
}
