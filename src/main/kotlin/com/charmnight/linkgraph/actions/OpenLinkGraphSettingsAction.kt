package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

/**
 * 直接打开 Settings/Preferences 中的 Link Graph 配置页。
 *
 * 提供这个动作是为了让用户不必在设置树中手动查找——尤其是新用户首次需要配置 LLM
 * 连接时，跳过搜索步骤能显著降低上手成本。
 * 继承 DumbAwareAction 让该动作在 dumb 模式（索引未完成）下也可用。
 */
class OpenLinkGraphSettingsAction : DumbAwareAction(
    LinkGraphBundle.message("action.open-link-graph-settings.text"),
    LinkGraphBundle.message("action.open-link-graph-settings.description"),
    null,
) {
    /**
     * 打开链路图配置页面。
     *
     * @param event 触发动作的菜单/工具栏事件，提供项目上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 直接定位到插件配置页，避免用户自行在设置树中查找。
        ShowSettingsUtil.getInstance().showSettingsDialog(event.project, LinkGraphSettingsConfigurable::class.java)
    }
}
