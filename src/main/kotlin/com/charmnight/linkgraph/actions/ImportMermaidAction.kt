package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class ImportMermaidAction : DumbAwareAction(
    LinkGraphBundle.message("action.import-mermaid.text"),
    LinkGraphBundle.message("action.import-mermaid.description"),
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val mermaid = Messages.showMultilineInputDialog(
            project,
            LinkGraphBundle.message("dialog.import-mermaid.message"),
            LinkGraphBundle.message("dialog.import-mermaid.title"),
            "",
            null,
            null,
        ) ?: return
        project.getService(LinkGraphProjectService::class.java).importMermaid(mermaid)
    }
}
