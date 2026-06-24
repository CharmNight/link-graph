package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * 将当前图导出为 Mermaid 文本并复制到剪贴板。
 *
 * 继承 DumbAwareAction 让动作在索引未完成时也可用——
 * 因为导出只依赖内存中的图，不依赖索引。
 */
class ExportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.export-mermaid.text"),
    LinkGraphBundle.message("action.export-mermaid.description"),
    null,
) {
    /**
     * 执行 Mermaid 导出动作。
     *
     * @param event 触发动作的事件，提供项目上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 没有关联项目时无法访问项目级服务，直接结束。
        val project = event.project ?: return
        // 从项目服务中读取当前图对应的 Mermaid 文本。
        val mermaid = project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.ExportMermaid)
        // 导出结果直接写入剪贴板，便于用户粘贴到外部文档或工具中。
        CopyPasteManager.getInstance().setContents(StringSelection(mermaid))
        // 弹窗提示导出成功，让用户明确知道操作完成。
        Messages.showInfoMessage(
            project,
            LinkGraphBundle.message("dialog.export-mermaid.copied"),
            LinkGraphBundle.message("dialog.export-mermaid.title"),
        )
    }
}
