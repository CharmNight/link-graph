package com.charmnight.linkgraph.application.composition

/**
 * 应用层工作流组合的入口包装。
 *
 * 用一个惰性 provider 暴露 [ApplicationWorkflows]，让上层无需关心工作流集合是如何构造的
 * （可能是同步构建，也可能是按需装配）。这种间接也让测试可以替换 provider 返回桩集合。
 */
internal class ApplicationWorkflowComposition(
    /** 返回当前可用工作流集合的回调；每次调用都可能拿到新构造的实例。 */
    private val workflowsProvider: () -> ApplicationWorkflows,
) {
    /** 通过 provider 取得当前可用的工作流集合。 */
    fun workflows(): ApplicationWorkflows = workflowsProvider()
}
