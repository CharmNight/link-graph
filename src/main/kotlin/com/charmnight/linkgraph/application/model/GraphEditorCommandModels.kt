package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/**
 * 图谱编辑器场景标识。
 *
 * 区分工作台中的不同图谱视图（事实图、流程图、资源关系图、架构图、类图、评审图）
 * 以及差异对比模式。同一个请求在编辑不同场景时需要走不同处理路径。
 */
enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    WORKSPACE_ARCHITECTURE_GRAPH,
    WORKSPACE_CLASS_DIAGRAM,
    WORKSPACE_REVIEW_GRAPH,
    DIFF,
}

/** 把分析层显示模式映射为工作台场景 ID，用于把分析结果路由到正确的视图。 */
fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
    AnalysisDisplayMode.ARCHITECTURE_GRAPH -> GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH
    AnalysisDisplayMode.CLASS_DIAGRAM -> GraphSceneId.WORKSPACE_CLASS_DIAGRAM
    AnalysisDisplayMode.REVIEW_GRAPH -> GraphSceneId.WORKSPACE_REVIEW_GRAPH
}

/** 反向把工作台场景 ID 映射回分析层显示模式；差异场景没有对应分析模式，因此返回 null。 */
fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = when (this) {
    GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
    GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
    GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> AnalysisDisplayMode.ARCHITECTURE_GRAPH
    GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> AnalysisDisplayMode.CLASS_DIAGRAM
    GraphSceneId.WORKSPACE_REVIEW_GRAPH -> AnalysisDisplayMode.REVIEW_GRAPH
    GraphSceneId.DIFF -> null
}

/** 节点在画布上的二维布局坐标。 */
data class GraphLayoutPosition(
    val x: Double,
    val y: Double,
)

/** 图谱编辑请求来源类型，用于决定后续审计、鉴权与回放行为。 */
enum class GraphEditRequestSource {
    FRONTEND,
    AI_TOOL,
    DEBUG_AUTOMATION,
}

/** 一次完整的图谱编辑请求，承载目标场景、基准版本号、待执行操作集合以及发起来源。 */
data class GraphEditRequest(
    val sceneId: GraphSceneId,
    val baseWorkspaceRevision: Long,
    val operations: List<GraphEditOperation>,
    val source: GraphEditRequestSource,
)

/** 图谱编辑操作的封闭接口，所有具体操作都以子类型形式存在。 */
sealed interface GraphEditOperation {
    /** 新增或更新一个节点；存在同 ID 时整体替换。 */
    data class UpsertNode(
        val node: GraphNode,
    ) : GraphEditOperation

    /** 按节点 ID 删除指定节点。 */
    data class RemoveNode(
        val nodeId: String,
    ) : GraphEditOperation

    /** 新增或更新一条边；存在同 ID 时整体替换。 */
    data class UpsertEdge(
        val edge: GraphEdge,
    ) : GraphEditOperation

    /** 按边 ID 删除指定边。 */
    data class RemoveEdge(
        val edgeId: String,
    ) : GraphEditOperation
}

/** 图谱编辑请求被拒绝时的具体原因码，覆盖版本、约束、格式等多种失败场景。 */
enum class GraphEditIssueCode {
    STALE_BASE_REVISION,
    EMPTY_OPERATIONS,
    DUPLICATE_NODE_ID,
    DUPLICATE_EDGE_ID,
    MISSING_NODE,
    MISSING_EDGE,
    INVALID_EDGE_ENDPOINT,
    READONLY_PROJECTION_NODE,
    READONLY_PROJECTION_EDGE,
    UNSUPPORTED_SCENE,
    INVALID_NODE_TYPE,
    INVALID_EDGE_TYPE,
    INVALID_SOURCE_TAG,
    PAYLOAD_TOO_LARGE,
    SANITIZER_REJECTED_NODE,
    INVALID_PAYLOAD_FIELD,
}

/** 描述一条具体的编辑失败原因，可定位到具体操作索引或目标实体。 */
data class GraphEditIssue(
    val code: GraphEditIssueCode,
    val message: String,
    val operationIndex: Int? = null,
    val targetId: String? = null,
    val retryable: Boolean = false,
)

/** 编辑请求被拒绝时的聚合信息，附带当前工作区版本号以便前端校准。 */
data class GraphEditRejected(
    val issues: List<GraphEditIssue>,
    val currentWorkspaceRevision: Long,
)

/**
 * 图编辑请求负载解析结果。
 *
 * 旧解析器对畸形字段直接抛 IllegalStateException，导致上游只能用 runCatching 兜底；
 * 新结构把解析问题以 [issues] 形式上抛，调用方可以在不依赖异常的前提下决定如何反馈给前端。
 *
 * @property request 解析成功时为完整的编辑请求；解析失败时为 null
 * @property issues 解析过程中检测到的问题列表；空列表代表解析成功
 */
data class GraphEditRequestParseResult(
    val request: GraphEditRequest?,
    val issues: List<GraphEditIssue>,
)

/** 图谱编辑请求的执行结果，分为成功应用与被拒绝两类。 */
sealed interface GraphEditResult {
    /** 编辑被成功应用，返回最新图文档以及本次操作的事务记录。 */
    data class Applied(
        val graph: com.charmnight.linkgraph.model.GraphDocument,
        val transaction: GraphEditTransaction,
    ) : GraphEditResult

    /** 编辑请求被拒绝，附带详细的拒绝信息。 */
    data class Rejected(
        val rejection: GraphEditRejected,
    ) : GraphEditResult
}

/** 一次成功编辑的事务记录，包含应用前后的图文档、操作明细以及版本变化，便于撤销与审计。 */
data class GraphEditTransaction(
    val graphBeforeApply: com.charmnight.linkgraph.model.GraphDocument,
    val graphAfterApply: com.charmnight.linkgraph.model.GraphDocument,
    val request: GraphEditRequest,
    val appliedOperations: List<GraphEditOperation>,
    val source: GraphEditRequestSource,
    val workspaceRevisionBefore: Long,
    val workspaceRevisionAfter: Long,
)
