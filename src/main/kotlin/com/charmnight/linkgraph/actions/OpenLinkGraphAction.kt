package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class OpenLinkGraphAction : DumbAwareAction(
    LinkGraphBundle.message("action.open-link-graph.text"),
    LinkGraphBundle.message("action.open-link-graph.description"),
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Link Graph") ?: return
        toolWindow.show()
        toolWindow.activate(null)
    }
}
