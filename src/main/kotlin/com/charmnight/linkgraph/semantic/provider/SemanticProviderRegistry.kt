package com.charmnight.linkgraph.semantic.provider

import com.charmnight.linkgraph.semantic.provider.code.CodeSubjectSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 维护语义 Provider 列表并负责按主题选择匹配实现。
 *
 * 启动时把所有 Provider 收集到本注册中心，调用方传入主题句柄后由本类负责路由。
 * 这种"集中路由"让上层不必关心每种主题对应哪个 Provider，也便于按种类去重检查。
 */
class SemanticProviderRegistry(
    /** 保存当前可用的语义 Provider 列表。 */
    private val providers: List<SemanticProvider>,
) {
    /**
     * 代码主题种类 → Provider 的派生索引。
     *
     * 启动时一次性建立，运行时 O(1) 查找。
     * 同一种类若有多个 Provider 会直接报错（不允许重复注册），
     * 避免运行时随机命中某个 Provider 造成不可预测的行为。
     */
    private val codeProvidersByKind: Map<CodeSubjectKind, SemanticProvider> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        providers
            .filterIsInstance<CodeSubjectSemanticProvider>()
            // 把"Provider → 多个 supportedKinds"展开为"(kind, provider)"对
            .flatMap { provider -> provider.supportedKinds.map { kind -> kind to provider } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (kind, providersForKind) ->
                // 同一种类只允许一个 Provider
                check(providersForKind.size == 1) {
                    "重复注册 CodeSubjectKind provider: $kind"
                }
                providersForKind.single()
            }
    }

    /**
     * 为指定主题句柄选择可处理的 Provider。
     *
     * @param handle 主题句柄
     * @return 匹配的 Provider；找不到时抛出 IllegalStateException
     */
    fun providerFor(handle: SubjectHandle): SemanticProvider {
        // 代码主题走种类索引，快速定位
        if (handle is CodeSubjectHandle) {
            return codeProvidersByKind[handle.kind]
                ?: error("缺少 CodeSubjectKind provider: ${handle.kind} (${handle.displayName})")
        }
        // 非代码主题：按注册顺序选择第一个声明支持的 Provider
        return providers.firstOrNull { provider -> provider.supports(handle) }
            ?: error("未找到匹配的语义 Provider: ${handle::class.simpleName}(${handle.displayName})")
    }

}
