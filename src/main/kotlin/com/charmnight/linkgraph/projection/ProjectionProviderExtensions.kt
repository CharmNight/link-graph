package com.charmnight.linkgraph.projection

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * 公共投影 Provider 扩展 API。
 *
 * 实现方可通过 `linkGraph.projectionProvider` 扩展点贡献具名投影能力。
 * 具体投影契约仍由 Provider 自身持有，避免把既有内置投影代码强行压平成最低公共 DTO。
 */
interface ProjectionProvider {
    /** 稳定的 Provider ID，供集成方做诊断与路由。 */
    val id: String
}

object ProjectionProviderExtensions {
    val EP_NAME: ExtensionPointName<ProjectionProvider> =
        ExtensionPointName.create("linkGraph.projectionProvider")

    fun registeredProviders(): List<ProjectionProvider> =
        try {
            EP_NAME.extensionList
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
}
