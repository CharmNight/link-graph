package com.charmnight.linkgraph.semantic.provider

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 定义语义分析 Provider 的统一接口。
 */
interface SemanticProvider {
    /**
     * 判断当前 Provider 是否支持指定主题。
     */
    fun supports(handle: SubjectHandle): Boolean

    /**
     * 基于主题和策略执行语义分析。
     */
    fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult
}
