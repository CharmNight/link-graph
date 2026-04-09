package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * 从用户输入的 Mermaid 文本导入图结构。
 */
class ImportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.import-mermaid.text"),
    LinkGraphBundle.message("action.import-mermaid.description"),
    null,
) {
    /**
     * 弹出输入框并触发 Mermaid 导入。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 没有关联项目时无法执行导入。
        val project = event.project ?: return
        // 先从弹窗收集 Mermaid 文本，用户取消时直接返回。
        val mermaid = Messages.showMultilineInputDialog(
            project,
            LinkGraphBundle.message("dialog.import-mermaid.message"),
            LinkGraphBundle.message("dialog.import-mermaid.title"),
            "",
            null,
            null,
        ) ?: return
        // Mermaid 文本交给项目服务统一解析与落图。
        project.getService(LinkGraphProjectService::class.java).importMermaid(mermaid)
    }
}
