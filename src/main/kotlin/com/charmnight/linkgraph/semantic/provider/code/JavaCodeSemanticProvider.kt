package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 提供 Java 方法的语义分析能力。
 */
class JavaCodeSemanticProvider(
    /** 保存真正执行代码流提取的提取器。 */
    private val extractor: CodeFlowSemanticExtractor = CodeFlowSemanticExtractor(),
) : CodeSubjectSemanticProvider {
    override val supportedKinds: Set<CodeSubjectKind> = setOf(CodeSubjectKind.JAVA_METHOD)

    /**
     * 对 Java 方法句柄执行语义分析。
     */
    override fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult {
        // 仅允许 CodeSubjectHandle 进入提取流程，避免调用方传入不匹配的主题类型。
        val codeHandle = handle as? CodeSubjectHandle
            ?: error("JavaCodeSemanticProvider 只支持 CodeSubjectHandle")
        // 提取器负责展开控制流、调用关系和资源引用等完整语义信息。
        return extractor.extract(codeHandle, capturePolicy, budgetPolicy)
    }
}
