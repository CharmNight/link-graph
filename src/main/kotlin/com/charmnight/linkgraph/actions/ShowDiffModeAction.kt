package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * 切换到图差异展示模式。
 */
class ShowDiffModeAction : DumbAwareAction(
    LinkGraphBundle.message("action.show-diff-mode.text"),
    LinkGraphBundle.message("action.show-diff-mode.description"),
    null,
) {
    /**
     * 请求项目服务展示差异视图。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 无项目时无法读取图状态，直接返回。
        val project = event.project ?: return
        // 如果已经成功切到差异模式，则不再弹出额外提示。
        val diff = project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.ShowDiffMode)
        if (diff != null) {
            return
        }
        // 当前缺少可比较的图数据时，向用户展示警告信息。
        Messages.showWarningDialog(
            project,
            LinkGraphBundle.message("dialog.show-diff-mode.missing-graph"),
            LinkGraphBundle.message("action.show-diff-mode.text"),
        )
    }
}
