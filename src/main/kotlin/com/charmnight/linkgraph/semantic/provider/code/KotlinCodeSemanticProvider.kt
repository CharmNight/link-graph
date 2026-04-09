package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
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
) : SemanticProvider {
    /**
     * 判断当前主题是否属于 Kotlin 代码主题。
     */
    override fun supports(handle: SubjectHandle): Boolean {
        // 只有代码句柄且非 Java 方法时，才交给 Kotlin Provider 处理。
        val codeHandle = handle as? CodeSubjectHandle ?: return false
        return codeHandle.kind != CodeSubjectKind.JAVA_METHOD
    }

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
