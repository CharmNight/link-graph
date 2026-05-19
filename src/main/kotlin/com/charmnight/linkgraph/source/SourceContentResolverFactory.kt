package com.charmnight.linkgraph.source

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

data class SourceContentResolverComponents(
    val resolver: SourceContentResolver,
    val attachedJarIndex: AttachedJarIndex,
    val settings: LinkGraphSettingsState,
)

class SourceContentResolverFactory(
    private val project: Project,
    private val settingsProvider: () -> LinkGraphSettingsState = ::applicationSettingsSnapshot,
) {
    fun create(
        budget: JvmResolutionBudget,
        settings: LinkGraphSettingsState = settingsProvider(),
    ): SourceContentResolverComponents {
        val attachedJarIndex = AttachedJarIndex.build(settings.attachedJars)
        val resolvers = buildList {
            add(
                IdeSourceContentResolver(
                    project,
                    SourceContentAccessPolicy(
                        allowExternalLibraries = budget.includeExternalLibraries,
                        allowJdk = budget.includeJdk,
                    ),
                ),
            )
            if (budget.includeUserAttachedJars) {
                add(
                    AttachedJarContentResolver(attachedJarIndex, settings.allowClassJarDecompile),
                )
            }
        }
        return SourceContentResolverComponents(
            resolver = CompositeSourceContentResolver(resolvers),
            attachedJarIndex = attachedJarIndex,
            settings = settings,
        )
    }
}

private fun applicationSettingsSnapshot(): LinkGraphSettingsState =
    runCatching {
        ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java).nonSecretSnapshot()
    }.getOrDefault(LinkGraphSettingsState())
