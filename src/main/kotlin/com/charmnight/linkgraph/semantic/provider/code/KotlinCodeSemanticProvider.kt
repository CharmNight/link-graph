package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 提供 Kotlin 代码主题的语义分析能力。
 *
 * 在 [CodeSubjectSemanticProvider] 之上特化 Kotlin 的函数/属性访问器/构造器等种类，
 * 并在进入提取前先用 [KotlinLightMethodDecoder] 把 light method 还原为正常句柄，
 * 让 Kotlin 与 Java 走统一提取路径。
 */
class KotlinCodeSemanticProvider(
    /** 保存代码流提取器。 */
    private val extractor: CodeFlowSemanticExtractor = CodeFlowSemanticExtractor(),
    /** 保存 Kotlin light method 解码器。 */
    private val lightMethodDecoder: KotlinLightMethodDecoder = KotlinLightMethodDecoder(),
) : CodeSubjectSemanticProvider {
    /** 支持的 Kotlin 代码主题种类：函数、属性访问器、主构造器、次构造器。 */
    override val supportedKinds: Set<CodeSubjectKind> = setOf(
        CodeSubjectKind.KOTLIN_FUNCTION,
        CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR,
        CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR,
        CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR,
    )

    /**
     * 对 Kotlin 代码主题执行语义分析。
     *
     * @param handle 主题句柄；必须是 [CodeSubjectHandle]
     * @param capturePolicy 捕获策略
     * @param budgetPolicy 遍历预算
     * @return 完整的语义分析结果
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
