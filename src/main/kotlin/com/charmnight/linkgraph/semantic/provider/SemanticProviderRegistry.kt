package com.charmnight.linkgraph.semantic.provider

import com.charmnight.linkgraph.semantic.provider.code.CodeSubjectSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 维护语义 Provider 列表并负责按主题选择匹配实现。
 */
class SemanticProviderRegistry(
    /** 保存当前可用的语义 Provider 列表。 */
    private val providers: List<SemanticProvider>,
) {
    private val codeProvidersByKind: Map<CodeSubjectKind, SemanticProvider> by lazy(LazyThreadSafetyMode.NONE) {
        providers
            .filterIsInstance<CodeSubjectSemanticProvider>()
            .flatMap { provider -> provider.supportedKinds.map { kind -> kind to provider } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (kind, providersForKind) ->
                check(providersForKind.size == 1) {
                    "重复注册 CodeSubjectKind provider: $kind"
                }
                providersForKind.single()
            }
    }

    /**
     * 为指定主题句柄选择可处理的 Provider。
     */
    fun providerFor(handle: SubjectHandle): SemanticProvider {
        if (handle is CodeSubjectHandle) {
            return codeProvidersByKind[handle.kind]
                ?: error("缺少 CodeSubjectKind provider: ${handle.kind} (${handle.displayName})")
        }
        // 按注册顺序选择第一个声明支持该主题的 Provider。
        return providers.firstOrNull { provider -> provider.supports(handle) }
            ?: error("未找到匹配的语义 Provider: ${handle::class.simpleName}(${handle.displayName})")
    }

}
