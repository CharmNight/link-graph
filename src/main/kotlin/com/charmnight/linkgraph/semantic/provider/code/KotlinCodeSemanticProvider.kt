package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 提供 Kotlin 代码主题的语义分析能力。
 */
class KotlinCodeSemanticProvider(
    /** 保存代码流提取器。 */
    private val extractor: CodeFlowSemanticExtractor = CodeFlowSemanticExtractor(),
    /** 保存 Kotlin light method 解码器。 */
    private val lightMethodDecoder: KotlinLightMethodDecoder = KotlinLightMethodDecoder(),
) : CodeSubjectSemanticProvider {
    override val supportedKinds: Set<CodeSubjectKind> = setOf(
        CodeSubjectKind.KOTLIN_FUNCTION,
        CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR,
        CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR,
        CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR,
    )

    /**
     * 对 Kotlin 代码主题执行语义分析。
     */
    override fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 仅支持代码主题句柄，其他主题直接视为调用错误。
        val codeHandle = handle as? CodeSubjectHandle
            ?: error("KotlinCodeSemanticProvider 只支持 CodeSubjectHandle")
        // 先把 light method 还原为更稳定的 Kotlin 语义入口。
        val decodedHandle = lightMethodDecoder.decode(codeHandle)
        return extractor.extract(decodedHandle, capturePolicy, budgetPolicy)
    }
}
