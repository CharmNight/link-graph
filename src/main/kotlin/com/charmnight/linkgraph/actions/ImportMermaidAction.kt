package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * 从用户输入的 Mermaid 文本导入图结构。
 *
 * 让用户把外部 Mermaid 文档（来自文档、其他工具）导入为图文档，
 * 便于在 link-graph 中继续编辑或做差异比对。
 */
class ImportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.import-mermaid.text"),
    LinkGraphBundle.message("action.import-mermaid.description"),
    null,
) {
    /**
     * 弹出输入框并触发 Mermaid 导入。
     *
     * @param event 触发动作的事件
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 没有关联项目时无法执行导入。
        val project = event.project ?: return
        // 先从弹窗收集 Mermaid 文本，用户取消时直接返回（结果为 null）。
        val mermaid = Messages.showMultilineInputDialog(
            project,
            LinkGraphBundle.message("dialog.import-mermaid.message"),
            LinkGraphBundle.message("dialog.import-mermaid.title"),
            "",
            null,
            null,
        ) ?: return
        // Mermaid 文本交给项目服务统一解析与落图。
        project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.ImportMermaid(mermaid))
    }
}
