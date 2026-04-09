package com.charmnight.linkgraph.semantic.provider

import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 维护语义 Provider 列表并负责按主题选择匹配实现。
 */
class SemanticProviderRegistry(
    /** 保存当前可用的语义 Provider 列表。 */
    private val providers: List<SemanticProvider>,
) {
    /**
     * 为指定主题句柄选择可处理的 Provider。
     */
    fun providerFor(handle: SubjectHandle): SemanticProvider {
        // 按注册顺序选择第一个声明支持该主题的 Provider。
        return providers.firstOrNull { provider -> provider.supports(handle) }
            ?: error("未找到匹配的语义 Provider: ${handle::class.simpleName}(${handle.displayName})")
    }
}
