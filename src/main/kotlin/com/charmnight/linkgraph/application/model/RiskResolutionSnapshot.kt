package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

/**
 * 风险化解流程在某一时点的快照。
 *
 * 把"草稿工作台状态"和"QA 返回结果"打包在一起作为不可变快照，
 * 便于撤销栈、回放、跨组件传递。任何时候需要"风险化解流程当前长什么样"
 * 都可以拿一份本快照，而不是分别持有可变状态。
 *
 * @property draftWorkbenchState 当前草稿工作台的完整状态（变更条目、备注等）。
 * @property qaResult 最近一次 QA 返回结果；尚无 QA 时为 null。
 */
data class RiskResolutionSnapshot(
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val qaResult: GraphPatchResult? = null,
)
