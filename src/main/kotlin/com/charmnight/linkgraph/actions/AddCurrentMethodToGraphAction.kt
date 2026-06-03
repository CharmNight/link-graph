package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowSession

/**
 * 将当前编辑器所在主题追加到现有图中。
 */
class AddCurrentMethodToGraphAction : DumbAwareAction(
    LinkGraphBundle.message("action.add-current-method-to-graph.text"),
    LinkGraphBundle.message("action.add-current-method-to-graph.description"),
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
        // 非空项目是动作可用的基础条件。
        val hasProject = event.project != null
        if (event.place == ActionPlaces.EDITOR_POPUP) {
            // 编辑器右键菜单下需要根据当前主题动态调整文案。
            val project = event.project
            val previewKind = if (project != null) {
                project.getService(GraphEditorApplicationService::class.java)
                    .commandDispatcher
                    .dispatch(ApplicationCommand.PreviewCurrentEditorSubjectKind(event.getData(CommonDataKeys.EDITOR)))
            } else {
                null
            }
            // 无法识别当前主题时，不展示该入口。
            event.presentation.isEnabledAndVisible = previewKind != null
            if (previewKind == SubjectPreviewKind.RESOURCE_SUBJECT) {
                event.presentation.text = LinkGraphBundle.message("action.add-current-node-to-graph.text")
                event.presentation.description = LinkGraphBundle.message("action.add-current-node-to-graph.description")
            } else {
                event.presentation.text = LinkGraphBundle.message("action.add-current-method-to-graph.text")
                event.presentation.description = LinkGraphBundle.message("action.add-current-method-to-graph.description")
            }
            return
        }
        event.presentation.isEnabled = hasProject
    }

    /**
     * 把当前编辑器主题追加到图中，并在成功后打开工具窗口。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 无项目时无法执行追加动作。
        val project = event.project ?: return
        // 统一交给项目服务追加当前主题节点。
        val appended = project.getService(GraphEditorApplicationService::class.java)
            .commandDispatcher
            .dispatch(ApplicationCommand.AddCurrentEditorContextNode)
        if (appended) {
            project.getService(LinkGraphToolWindowSession::class.java).openToolWindow()
        }
    }
}
