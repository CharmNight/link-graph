package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

class SourceNavigationStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentNavigationRequested(nodeId: String) {
        stateService.graph.requestSourceNavigation(nodeId)
    }

    fun presentNavigationStarting(title: String) {
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.INFO,
            "正在定位源码：$title",
        )
        requestBrowserSync()
    }

    fun presentNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
        title: String,
    ) {
        stateService.graph.markSourceNavigationOpened(nodeId, targetPath, line, column)
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.SUCCESS,
            "已打开源码：$title",
        )
        requestBrowserSync()
    }

    fun presentNavigationNotFound(
        nodeId: String,
        label: String,
    ) {
        stateService.graph.markSourceNavigationNotFound(nodeId)
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.WARNING,
            "未找到源码位置：$label",
        )
        requestBrowserSync()
    }

    fun presentNavigationFailed(
        nodeId: String,
        message: String,
        statusMessage: String = "打开源码失败：$message",
        level: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    ) {
        stateService.graph.markSourceNavigationFailed(nodeId, message)
        stateService.workbench.markOperationFeedback(level.toOperationFeedbackLevel(), statusMessage)
        requestBrowserSync()
    }

    fun presentSettingsOpened() {
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.SUCCESS,
            "已打开 IDE 设置 > Link Graph。",
        )
        requestBrowserSync()
    }

    fun presentSettingsOpenFailed(message: String) {
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.ERROR,
            "打开插件设置失败：$message",
        )
        requestBrowserSync()
    }
}
