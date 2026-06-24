package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.sync.GraphPatchApplyService

/**
 * 草稿补丁应用结果的汇总信息。
 *
 * 描述本次应用涉及的变更数量、所影响到的节点与边、需要聚焦展示的节点，以及面向用户的目标摘要列表。
 */
data class DraftPatchApplySummary(
    val summary: String,
    val appliedOperationCount: Int,
    val appliedNodeIds: List<String> = emptyList(),
    val appliedEdgeIds: List<String> = emptyList(),
    val focusNodeId: String? = null,
    val appliedTargets: List<String> = emptyList(),
)

/**
 * 预览草稿补丁用例的结果类型。
 */
sealed interface PreviewDraftPatchUseCaseResult {
    /** 成功生成预览，携带本次草稿补丁内容。 */
    data class Previewed(val patch: GraphPatch) : PreviewDraftPatchUseCaseResult
}

/**
 * 应用草稿补丁预览用例的结果类型。
 */
sealed interface ApplyDraftPatchUseCaseResult {
    /** 应用失败：当前快照中不存在可被应用的草稿补丁预览。 */
    data object MissingPreview : ApplyDraftPatchUseCaseResult

    /**
     * 应用成功：包含应用前后的图谱、补丁内容，以及本次应用的汇总信息。
     */
    data class Applied(
        val graphBeforeApply: GraphDocument,
        val patch: GraphPatch,
        val graph: GraphDocument,
        val applyResult: DraftPatchApplySummary,
    ) : ApplyDraftPatchUseCaseResult
}

/**
 * 清除草稿补丁预览用例的结果类型。
 */
sealed interface ClearDraftPatchPreviewUseCaseResult {
    /** 当前没有可清除的草稿补丁预览。 */
    data object MissingPreview : ClearDraftPatchPreviewUseCaseResult
    /** 已成功清除草稿补丁预览。 */
    data object Cleared : ClearDraftPatchPreviewUseCaseResult
}

/**
 * 草稿补丁预览的恢复来源枚举。
 *
 * 用于标识从何处恢复一份草稿补丁预览，例如问答结果、差异评审结果或最近一次应用的撤销记录。
 */
enum class RestoreDraftPatchPreviewSource {
    QA,
    DIFF_REVIEW,
    LAST_APPLIED,
}

/**
 * 恢复草稿补丁预览用例的结果类型。
 */
sealed interface RestoreDraftPatchPreviewUseCaseResult {
    /** 恢复失败：指定来源中不存在可恢复的草稿补丁。 */
    data object MissingPreview : RestoreDraftPatchPreviewUseCaseResult

    /**
     * 恢复成功：携带被恢复的草稿补丁内容。
     */
    data class Restored(
        val patch: GraphPatch,
    ) : RestoreDraftPatchPreviewUseCaseResult
}

/**
 * 撤销草稿补丁应用用例的结果类型。
 */
sealed interface UndoDraftPatchApplyUseCaseResult {
    /** 撤销失败：当前不存在可撤销的应用记录。 */
    data object MissingUndo : UndoDraftPatchApplyUseCaseResult

    /**
     * 撤销成功：携带回滚后的图谱，以及恢复出的草稿补丁预览（可能为空）。
     */
    data class Undone(
        val graph: GraphDocument,
        val patchPreview: GraphPatch?,
    ) : UndoDraftPatchApplyUseCaseResult
}

/**
 * 草稿补丁用例。
 *
 * 围绕草稿补丁的预览、应用、清除、恢复与撤销提供统一的应用层入口，
 * 接收应用快照作为输入并以密封类型返回结果，避免直接修改底层图谱状态。
 */
class DraftPatchUseCase(
    private val graphPatchApplyService: GraphPatchApplyService,
) {
    /**
     * 基于给定补丁生成草稿补丁预览结果。
     */
    fun previewDraftPatch(patch: GraphPatch): PreviewDraftPatchUseCaseResult {
        return PreviewDraftPatchUseCaseResult.Previewed(patch)
    }

    /**
     * 将当前快照中的草稿补丁预览应用到工作区图谱。
     *
     * 若快照中没有草稿补丁预览则返回缺失结果；可通过操作标识集合进行部分应用，
     * 应用结果包含变更前后图谱以及汇总信息。
     */
    fun applyDraftPatchPreview(
        snapshot: ApplicationSnapshot,
        operationIds: Set<String>? = null,
    ): ApplyDraftPatchUseCaseResult {
        val patch = snapshot.draftPatchPreview ?: return ApplyDraftPatchUseCaseResult.MissingPreview
        val selectedOperations = patch.operations.filter { operationIds == null || it.id in operationIds }
        val applied = graphPatchApplyService.apply(snapshot.workspaceGraph, patch, operationIds)
        return ApplyDraftPatchUseCaseResult.Applied(
            graphBeforeApply = snapshot.workspaceGraph,
            patch = patch,
            graph = applied,
            applyResult = buildDraftPatchApplyResult(selectedOperations, applied),
        )
    }

    /**
     * 清除当前快照中的草稿补丁预览。
     *
     * 若原本就无预览则返回缺失结果，否则返回清除成功。
     */
    fun clearDraftPatchPreview(snapshot: ApplicationSnapshot): ClearDraftPatchPreviewUseCaseResult {
        return if (snapshot.draftPatchPreview == null) {
            ClearDraftPatchPreviewUseCaseResult.MissingPreview
        } else {
            ClearDraftPatchPreviewUseCaseResult.Cleared
        }
    }

    /**
     * 从指定来源恢复一份草稿补丁预览。
     *
     * 根据来源从快照中的对应位置取出补丁，若来源对应的补丁不存在则返回缺失结果。
     */
    fun restoreDraftPatchPreview(
        snapshot: ApplicationSnapshot,
        source: RestoreDraftPatchPreviewSource,
    ): RestoreDraftPatchPreviewUseCaseResult {
        val patch = when (source) {
            RestoreDraftPatchPreviewSource.QA -> snapshot.qaResult?.patch
            RestoreDraftPatchPreviewSource.DIFF_REVIEW -> snapshot.diffReviewResult?.patch
            RestoreDraftPatchPreviewSource.LAST_APPLIED -> snapshot.draftPatchUndo?.patchPreview
        } ?: return RestoreDraftPatchPreviewUseCaseResult.MissingPreview
        return RestoreDraftPatchPreviewUseCaseResult.Restored(patch)
    }

    /**
     * 撤销最近一次草稿补丁应用。
     *
     * 若快照中不存在可撤销的应用记录则返回缺失结果，否则返回应用前的图谱与当时对应的补丁预览。
     */
    fun undoLastDraftPatchApply(snapshot: ApplicationSnapshot): UndoDraftPatchApplyUseCaseResult {
        val undo = snapshot.draftPatchUndo ?: return UndoDraftPatchApplyUseCaseResult.MissingUndo
        return UndoDraftPatchApplyUseCaseResult.Undone(
            graph = undo.graphBeforeApply,
            patchPreview = undo.patchPreview,
        )
    }

    /**
     * 基于本次应用的操作集合和最终图谱，构建草稿补丁应用结果的汇总信息。
     *
     * 按动作类型提取受影响的节点标识与边标识，选取首个仍存在于图谱中的节点作为聚焦点，
     * 并结合操作标题生成面向用户的目标摘要。
     */
    private fun buildDraftPatchApplyResult(
        operations: List<GraphPatchOperation>,
        graph: GraphDocument,
    ): DraftPatchApplySummary {
        val appliedNodeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_NODE,
                GraphPatchAction.UPDATE_NODE,
                GraphPatchAction.ADD_ANNOTATION,
                GraphPatchAction.MARK_UNCERTAIN -> operation.node?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                GraphPatchAction.DELETE_NODE -> operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                else -> null
            }
        }.distinct()
        val appliedEdgeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_EDGE,
                GraphPatchAction.UPDATE_EDGE,
                GraphPatchAction.DELETE_EDGE -> operation.edge?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.EDGE }
                else -> null
            }
        }.distinct()
        val focusNodeId = appliedNodeIds.firstOrNull { nodeId -> graph.nodes.any { node -> node.id == nodeId } }
        return DraftPatchApplySummary(
            summary = "已应用 ${operations.size} 条草稿图变更。",
            appliedOperationCount = operations.size,
            appliedNodeIds = appliedNodeIds,
            appliedEdgeIds = appliedEdgeIds,
            focusNodeId = focusNodeId,
            appliedTargets = operations.mapNotNull(::resolveDraftPatchApplyTarget).distinct(),
        )
    }

    /**
     * 解析单个操作在面向用户展示时使用的目标摘要文本。
     *
     * 优先使用节点签名去掉参数列表后的形式或节点标题，其次使用边的标签或端点连接描述，
     * 最后回退到操作的标题、摘要或元素标识。
     */
    private fun resolveDraftPatchApplyTarget(operation: GraphPatchOperation): String? {
        operation.node?.let { node ->
            return node.signature?.substringBefore('(') ?: node.title
        }
        operation.edge?.let { edge ->
            return edge.label ?: "${edge.fromNodeId} -> ${edge.toNodeId}"
        }
        return operation.title ?: operation.summary ?: operation.elementId
    }
}
