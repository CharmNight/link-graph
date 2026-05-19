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
 */
class CodeSemanticProvider(
    architectureIndexProvider: (() -> ArchitectureGraphIndex?)? = null,
    providers: List<CodeSubjectSemanticProvider> = listOf(
        JavaCodeSemanticProvider(CodeFlowSemanticExtractor(architectureIndexProvider = architectureIndexProvider)),
        KotlinCodeSemanticProvider(CodeFlowSemanticExtractor(architectureIndexProvider = architectureIndexProvider)),
    ),
) : CodeSubjectSemanticProvider {
    private val providersByKind: Map<CodeSubjectKind, CodeSubjectSemanticProvider> = providers
        .flatMap { provider -> provider.supportedKinds.map { kind -> kind to provider } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (kind, providersForKind) ->
            require(providersForKind.size == 1) {
                "重复注册 CodeSubjectKind provider: $kind"
            }
            providersForKind.single()
        }

    override val supportedKinds: Set<CodeSubjectKind> = providersByKind.keys

    /**
     * 根据代码主题种类路由到对应实现执行分析。
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
