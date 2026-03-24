package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class ShowDiffModeAction : DumbAwareAction(
    LinkGraphBundle.message("action.show-diff-mode.text"),
    LinkGraphBundle.message("action.show-diff-mode.description"),
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val diff = project.getService(LinkGraphProjectService::class.java).showDiffMode()
        if (diff != null) {
            return
        }
        Messages.showWarningDialog(
            project,
            LinkGraphBundle.message("dialog.show-diff-mode.missing-graph"),
            LinkGraphBundle.message("action.show-diff-mode.text"),
        )
    }
}
