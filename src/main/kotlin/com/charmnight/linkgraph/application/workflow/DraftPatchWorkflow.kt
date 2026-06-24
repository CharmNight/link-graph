package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchUseCase
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewSource
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.sync.GraphPatchApplyService

/**
 * 草稿补丁工作流。
 *
 * 路由器迁移到 application API 之前的临时项目级入口。
 * 把草稿补丁相关命令（预览/应用/清除/恢复/撤销）转交给 use case，
 * 并把结果以应用事件形式广播给监听者。
 *
 * @param snapshotProvider 应用快照提供者
 * @param eventSink 应用事件接收器
 * @param graphPatchApplyService 图补丁应用服务
 */
internal class DraftPatchWorkflow(
    private val snapshotProvider: ApplicationSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    graphPatchApplyService: GraphPatchApplyService,
) {
    /** 实际执行业务的 use case。 */
    private val useCase = DraftPatchUseCase(graphPatchApplyService)

    /** 准备一份草稿补丁预览（不应用）。 */
    fun previewDraftPatch(patch: GraphPatch) {
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchPreviewReady(useCase.previewDraftPatch(patch)))
    }

    /**
     * 应用草稿补丁预览。
     *
     * @param operationIds 指定操作 ID 集合；为 null 表示应用全部
     * @return 应用后的图；未应用时返回 null
     */
    fun applyDraftPatchPreview(operationIds: Set<String>? = null): GraphDocument? {
        val result = useCase.applyDraftPatchPreview(
            snapshot = snapshotProvider.snapshot(),
            operationIds = operationIds,
        )
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchApplied(result))
        return (result as? ApplyDraftPatchUseCaseResult.Applied)?.graph
    }

    /** 清除草稿补丁预览（不应用）。 */
    fun clearDraftPatchPreview() {
        val result = useCase.clearDraftPatchPreview(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchCleared(result))
    }

    /**
     * 从指定来源恢复草稿补丁预览。
     *
     * @param source 来源（QA / DIFF_REVIEW / LAST_APPLIED）
     * @return 恢复的补丁；无可恢复时返回 null
     */
    fun restoreDraftPatchPreview(source: DraftPatchPreviewSource): GraphPatch? {
        val result = useCase.restoreDraftPatchPreview(
            snapshot = snapshotProvider.snapshot(),
            source = source.toUseCaseSource(),
        )
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchRestored(result))
        return (result as? RestoreDraftPatchPreviewUseCaseResult.Restored)?.patch
    }

    /** 撤销最近一次应用的草稿补丁。 */
    fun undoLastDraftPatchApply(): GraphDocument? {
        val result = useCase.undoLastDraftPatchApply(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchUndone(result))
        return (result as? UndoDraftPatchApplyUseCaseResult.Undone)?.graph
    }

    /** 把 application 层的来源枚举映射到 use case 层的来源枚举。 */
    private fun DraftPatchPreviewSource.toUseCaseSource(): RestoreDraftPatchPreviewSource {
        return when (this) {
            DraftPatchPreviewSource.QA -> RestoreDraftPatchPreviewSource.QA
            DraftPatchPreviewSource.DIFF_REVIEW -> RestoreDraftPatchPreviewSource.DIFF_REVIEW
            DraftPatchPreviewSource.LAST_APPLIED -> RestoreDraftPatchPreviewSource.LAST_APPLIED
        }
    }
}
