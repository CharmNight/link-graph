package com.charmnight.linkgraph.actions

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

/**
 * 直接打开 Settings/Preferences 中的 Link Graph 配置页。
 */
class OpenLinkGraphSettingsAction : DumbAwareAction(
    LinkGraphBundle.message("action.open-link-graph-settings.text"),
    LinkGraphBundle.message("action.open-link-graph-settings.description"),
    null,
) {
    /**
     * 打开链路图配置页面。
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 直接定位到插件配置页，避免用户自行在设置树中查找。
        ShowSettingsUtil.getInstance().showSettingsDialog(event.project, LinkGraphSettingsConfigurable::class.java)
    }
}
