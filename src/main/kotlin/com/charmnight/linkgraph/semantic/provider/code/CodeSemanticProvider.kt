package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 按代码主题种类路由到 Java 或 Kotlin 语义 Provider。
 */
class CodeSemanticProvider(
    /** 保存 Java 语义 Provider。 */
    private val javaProvider: JavaCodeSemanticProvider = JavaCodeSemanticProvider(),
    /** 保存 Kotlin 语义 Provider。 */
    private val kotlinProvider: KotlinCodeSemanticProvider = KotlinCodeSemanticProvider(),
) : SemanticProvider {
    /**
     * 判断当前主题是否为代码主题。
     */
    override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

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
        // Java 与 Kotlin 的语义分析细节不同，这里按主题种类分发。
        return when (codeHandle.kind) {
            CodeSubjectKind.JAVA_METHOD -> javaProvider.analyze(codeHandle, capturePolicy, budgetPolicy)
            CodeSubjectKind.KOTLIN_FUNCTION,
            CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR,
            CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR,
            CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR -> kotlinProvider.analyze(codeHandle, capturePolicy, budgetPolicy)
        }
    }
}
