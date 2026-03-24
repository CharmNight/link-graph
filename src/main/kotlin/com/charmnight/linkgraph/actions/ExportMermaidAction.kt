package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

class ExportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.export-mermaid.text"),
    LinkGraphBundle.message("action.export-mermaid.description"),
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val mermaid = project.getService(LinkGraphProjectService::class.java).exportMermaid()
        CopyPasteManager.getInstance().setContents(StringSelection(mermaid))
        Messages.showInfoMessage(
            project,
            LinkGraphBundle.message("dialog.export-mermaid.copied"),
            LinkGraphBundle.message("dialog.export-mermaid.title"),
        )
    }
}
