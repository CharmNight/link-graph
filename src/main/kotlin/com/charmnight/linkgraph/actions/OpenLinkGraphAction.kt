package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession

/**
 * 打开链路图工具窗口并加载当前编辑器上下文。
 */
class OpenLinkGraphAction : DumbAwareAction(
    LinkGraphBundle.message("action.open-link-graph.text"),
    LinkGraphBundle.message("action.open-link-graph.description"),
    null,
) {
    /**
     * 指定动作更新在线程池中执行。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 根据当前上下文更新动作文案和可见性。
     */
    override fun update(event: AnActionEvent) {
        // 非空项目是动作可用的最基本前提。
        val hasProject = event.project != null
        if (event.place == ActionPlaces.EDITOR_POPUP) {
            // 编辑器右键菜单下需要根据当前光标主题动态切换文案。
            val project = event.project
            val previewKind = if (project != null) {
                project.getService(LinkGraphProjectService::class.java)
                    .previewCurrentEditorSubjectKind(event.getData(CommonDataKeys.EDITOR))
            } else {
                null
            }
            // 没有可识别主题时隐藏该菜单项，避免误触发。
            event.presentation.isEnabledAndVisible = previewKind != null
            if (previewKind == SubjectPreviewKind.RESOURCE_SUBJECT) {
                event.presentation.text = LinkGraphBundle.message("action.open-current-node-graph.text")
                event.presentation.description = LinkGraphBundle.message("action.open-current-node-graph.description")
            } else {
                event.presentation.text = LinkGraphBundle.message("action.open-link-graph.text")
                event.presentation.description = LinkGraphBundle.message("action.open-link-graph.description")
            }
            return
        }
        event.presentation.isEnabled = hasProject
    }

    /**
     * 打开工具窗口并异步加载当前编辑器上下文图。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 无项目时无法访问项目级服务。
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)
        project.getService(LinkGraphToolWindowSession::class.java).openToolWindow()
        val projectService = project.getService(LinkGraphProjectService::class.java)
        projectService.loadCurrentEditorContextGraphAsync(editor)
    }
}
