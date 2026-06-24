package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 按代码主题种类路由到 Java 或 Kotlin 语义 Provider。
 *
 * 这是一个组合 Provider：内部维护 JavaCodeSemanticProvider 与 KotlinCodeSemanticProvider，
 * 按主题种类分发到具体实现。上层只需依赖本类即可覆盖所有代码主题，
 * 不必关心 Java/Kotlin 的具体差异。
 *
 * @param architectureIndexProvider 架构索引提供者；用于跨方法分析时的回退查询
 * @param providers 内部使用的具体 Provider 列表；可注入以便测试
 */
class CodeSemanticProvider(
    architectureIndexProvider: (() -> ArchitectureGraphIndex?)? = null,
    providers: List<CodeSubjectSemanticProvider> = listOf(
        JavaCodeSemanticProvider(CodeFlowSemanticExtractor(architectureIndexProvider = architectureIndexProvider)),
        KotlinCodeSemanticProvider(CodeFlowSemanticExtractor(architectureIndexProvider = architectureIndexProvider)),
    ),
) : CodeSubjectSemanticProvider {
    /** 代码种类 → Provider 的派生索引；构造时一次性建立。 */
    private val providersByKind: Map<CodeSubjectKind, CodeSubjectSemanticProvider> = providers
        // 把每个 Provider 展开为多个 (kind, provider) 对
        .flatMap { provider -> provider.supportedKinds.map { kind -> kind to provider } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (kind, providersForKind) ->
            // 同一种类只允许一个 Provider，否则启动时直接报错
            require(providersForKind.size == 1) {
                "重复注册 CodeSubjectKind provider: $kind"
            }
            providersForKind.single()
        }

    /** 本 Provider 支持的代码主题种类（由内部所有 Provider 共同决定）。 */
    override val supportedKinds: Set<CodeSubjectKind> = providersByKind.keys

    /**
     * 根据代码主题种类路由到对应实现执行分析。
     *
     * @param handle 主题句柄；必须是 [CodeSubjectHandle]
     * @return 完整的语义分析结果
     */
    override fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 入口统一要求是代码主题句柄。
        val codeHandle = handle as? CodeSubjectHandle
            ?: error("代码语义 Provider 只支持 CodeSubjectHandle")
        val provider = providersByKind[codeHandle.kind]
            ?: error("缺少 CodeSubjectKind provider: ${codeHandle.kind}")
        return provider.analyze(codeHandle, capturePolicy, budgetPolicy)
    }
}
