package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * 切换到图差异展示模式。
 *
 * 用于把工具窗口切换到差异视图，对比代码侧与设计侧的图。
 * 继承 DumbAwareAction 让动作在索引未完成时也可触发，
 * 因为差异模式本身可以在数据不全时给出提示。
 */
class ShowDiffModeAction : DumbAwareAction(
    LinkGraphBundle.message("action.show-diff-mode.text"),
    LinkGraphBundle.message("action.show-diff-mode.description"),
    null,
) {
    /**
     * 请求项目服务展示差异视图。
     *
     * @param event 触发动作的事件
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 无项目时无法读取图状态，直接返回。
        val project = event.project ?: return
        // 派发差异模式命令；返回的差异结果非空表示切换成功。
        val diff = project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.ShowDiffMode)
        // 切换成功时无需额外提示，避免干扰用户。
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
