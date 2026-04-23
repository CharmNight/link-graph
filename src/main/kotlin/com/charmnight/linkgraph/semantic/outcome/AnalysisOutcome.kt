package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument

/**
 * 表示语义分析经过投影后的展示结果。
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
    val feedbackLevel: com.charmnight.linkgraph.ui.OperationFeedbackLevel,
    /** 保存反馈消息。 */
    val feedbackMessage: String,
    /** 保存投影阶段的统计信息。 */
    val projectionStats: AnalysisProjectionStats = AnalysisProjectionStats(),
    /** 保存事实链路视图专用文档。 */
    val factGraphView: FactGraphViewDocument? = null,
    /** 保存流程图视图专用文档。 */
    val flowchartView: FlowchartViewDocument? = null,
    /** 保存资源关系视图专用文档。 */
    val resourceRelationView: ResourceRelationViewDocument? = null,
)
