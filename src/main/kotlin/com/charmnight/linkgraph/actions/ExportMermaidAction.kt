package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * 将当前图导出为 Mermaid 文本并复制到剪贴板。
 */
class ExportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.export-mermaid.text"),
    LinkGraphBundle.message("action.export-mermaid.description"),
    null,
) {
    /**
     * 执行 Mermaid 导出动作。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 没有关联项目时无法访问项目级服务，直接结束。
        val project = event.project ?: return
        // 从项目服务中读取当前图对应的 Mermaid 文本。
        val mermaid = project.getService(LinkGraphProjectService::class.java).exportMermaid()
        // 导出结果直接写入剪贴板，便于用户粘贴到外部文档或工具中。
        CopyPasteManager.getInstance().setContents(StringSelection(mermaid))
        Messages.showInfoMessage(
            project,
            LinkGraphBundle.message("dialog.export-mermaid.copied"),
            LinkGraphBundle.message("dialog.export-mermaid.title"),
        )
    }
}
