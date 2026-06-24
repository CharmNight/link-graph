package com.charmnight.linkgraph.semantic.provider

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 定义语义分析 Provider 的统一接口。
 *
 * 不同主题类型（代码、资源、文档等）有自己的 Provider 实现。
 * 上层通过 [supports] 询问每个 Provider 是否能处理某主题，
 * 然后用 [analyze] 在策略约束下执行实际分析。
 */
interface SemanticProvider {
    /**
     * 判断当前 Provider 是否支持指定主题。
     *
     * @param handle 待判断的主题句柄
     * @return true 表示本 Provider 可以分析该主题
     */
    fun supports(handle: SubjectHandle): Boolean

    /**
     * 基于主题和策略执行语义分析。
     *
     * @param handle 待分析的主题句柄
     * @param capturePolicy 控制捕获哪些类型的信息（控制流、调用、资源等）
     * @param budgetPolicy 控制遍历深度与规模
     * @return 完整的语义分析结果
     */
    fun analyze(
        handle: SubjectHandle,
        capturePolicy: SemanticCapturePolicy,
        budgetPolicy: TraversalBudgetPolicy,
    ): SemanticAnalysisResult
}
