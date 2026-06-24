package com.charmnight.linkgraph.source

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * 源码内容解析器组件集合。
 *
 * 把"主解析器 + 附带 jar 索引 + 设置"打包返回，让调用方一次拿到所有需要的对象。
 */
data class SourceContentResolverComponents(
    /** 主解析器（通常是组合解析器，内部封装多个具体解析器）。 */
    val resolver: SourceContentResolver,
    /** 附带 jar 索引；用于解析项目外部 jar。 */
    val attachedJarIndex: AttachedJarIndex,
    /** 解析所用的设置快照。 */
    val settings: LinkGraphSettingsState,
)

/**
 * 源码内容解析器工厂。
 *
 * 根据解析预算（是否包含外部库、JDK、用户附带的 jar 等）和当前设置，
 * 组装合适的源码内容解析器组合。这种工厂模式让每次解析都能拿到"配置最新"的解析器。
 *
 * @param project 当前项目
 * @param settingsProvider 设置提供者；默认从应用层 service 读取
 */
class SourceContentResolverFactory(
    private val project: Project,
    private val settingsProvider: () -> LinkGraphSettingsState = ::applicationSettingsSnapshot,
) {
    /**
     * 创建解析器组件。
     *
     * @param budget 解析预算；决定是否包含外部库/JDK/jar
     * @param settings 当前设置；默认从 settingsProvider 取
     * @return 包含主解析器、jar 索引与设置的组件集合
     */
    fun create(
        budget: JvmResolutionBudget,
        settings: LinkGraphSettingsState = settingsProvider(),
    ): SourceContentResolverComponents {
        // 先构建 jar 索引（即使用不到也建好，避免后续重复构建）
        val attachedJarIndex = AttachedJarIndex.build(settings.attachedJars)
        val resolvers = buildList {
            // IDE 解析器总是存在：处理项目内源码与可访问的外部源码
            add(
                IdeSourceContentResolver(
                    project,
                    SourceContentAccessPolicy(
                        allowExternalLibraries = budget.includeExternalLibraries,
                        allowJdk = budget.includeJdk,
                    ),
                ),
            )
            // 仅当预算允许时才加入 jar 解析器，避免无意义的 jar 内容读取
            if (budget.includeUserAttachedJars) {
                add(
                    AttachedJarContentResolver(attachedJarIndex, settings.allowClassJarDecompile),
                )
            }
        }
        return SourceContentResolverComponents(
            // 组合多个解析器：按顺序尝试，由组合器决定命中规则
            resolver = CompositeSourceContentResolver(resolvers),
            attachedJarIndex = attachedJarIndex,
            settings = settings,
        )
    }
}

/**
 * 应用级设置快照获取。
 * 失败时返回默认设置（避免读取服务不可用时打断主流程）。
 */
private fun applicationSettingsSnapshot(): LinkGraphSettingsState =
    runCatching {
        ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java).nonSecretSnapshot()
    }.getOrDefault(LinkGraphSettingsState())
