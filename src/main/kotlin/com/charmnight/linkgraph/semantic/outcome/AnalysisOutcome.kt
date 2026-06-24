package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 表示语义分析经过投影后的展示结果。
 *
 * 该结构是分析管线的最终输出：包含当前展示模式、可见/完整图、锚点、反馈消息以及
 * 各视图专用文档（事实/流程/资源）。UI 拿到本结果即可完整渲染工具窗口。
 */
data class AnalysisOutcome(
    /** 保存当前展示模式。 */
    val displayMode: AnalysisDisplayMode,
    /** 保存当前可见图。 */
    val visibleGraph: GraphDocument,
    /** 保存完整图数据。 */
    val fullGraph: GraphDocument,
    /** 保存当前锚点节点标识。 */
    val anchorNodeId: String? = null,
    /** 保存当前选中的方法签名。 */
    val selectedMethodSignature: String? = null,
    /** 保存展示名称。 */
    val displayName: String,
    /** 保存操作反馈等级。 */
    val feedbackLevel: ApplicationFeedbackLevel,
    /** 保存反馈消息。 */
    val statusMessage: String,
    /** 保存投影阶段的统计信息。 */
    val projectionStats: AnalysisProjectionStats = AnalysisProjectionStats(),
    /** 保存事实链路视图专用文档。 */
    val factGraphView: FactGraphViewDocument? = null,
    /** 保存流程图视图专用文档。 */
    val flowchartView: FlowchartViewDocument? = null,
    /** 保存资源关系视图专用文档。 */
    val resourceRelationView: ResourceRelationViewDocument? = null,
)
