package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

/**
 * 源码导航状态呈现器。
 *
 * 把"源码跳转"流程的状态变化应用到状态服务并触发浏览器同步。
 * 让命令处理层只关心业务结果，UI 同步由本呈现器统一处理。
 *
 * @param stateService 编辑器状态服务
 * @param requestBrowserSync 触发浏览器同步的回调
 */
class SourceNavigationStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    /** 呈现"用户请求跳转源码"。 */
    fun presentNavigationRequested(nodeId: String) {
        stateService.graph.requestSourceNavigation(nodeId)
    }

    /** 呈现"开始定位源码"。 */
    fun presentNavigationStarting(title: String) {
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.INFO,
            "正在定位源码：$title",
        )
        requestBrowserSync()
    }

    /** 呈现"已打开源码"。带文件路径、行号、列号等信息。 */
    fun presentNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
        title: String,
    ) {
        stateService.graph.markSourceNavigationOpened(nodeId, targetPath, line, column)
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.SUCCESS,
            "已打开源码：$title",
        )
        requestBrowserSync()
    }

    /** 呈现"未找到源码位置"。 */
    fun presentNavigationNotFound(
        nodeId: String,
        label: String,
    ) {
        stateService.graph.markSourceNavigationNotFound(nodeId)
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.WARNING,
            "未找到源码位置：$label",
        )
        requestBrowserSync()
    }

    /**
     * 呈现"打开源码失败"。
     * 默认等级为 ERROR，调用方可按需覆盖（例如改为 WARNING）。
     */
    fun presentNavigationFailed(
        nodeId: String,
        message: String,
        statusMessage: String = "打开源码失败：$message",
        level: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    ) {
        stateService.graph.markSourceNavigationFailed(nodeId, message)
        stateService.workbench.markOperationFeedback(level, statusMessage)
        requestBrowserSync()
    }

    /** 呈现"已打开插件设置页"。 */
    fun presentSettingsOpened() {
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.SUCCESS,
            "已打开 IDE 设置 > Link Graph。",
        )
        requestBrowserSync()
    }

    /** 呈现"打开插件设置失败"。 */
    fun presentSettingsOpenFailed(message: String) {
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.ERROR,
            "打开插件设置失败：$message",
        )
        requestBrowserSync()
    }
}
