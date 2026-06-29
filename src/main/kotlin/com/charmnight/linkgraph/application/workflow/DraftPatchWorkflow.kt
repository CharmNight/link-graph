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
import com.intellij.openapi.diagnostic.Logger

/**
 * 草稿补丁工作流。
 *
 * 路由器迁移到 application API 之前的临时项目级入口。
 * 把草稿补丁相关命令（预览/应用/清除/恢复/撤销）转交给 use case，
 * 并把结果以应用事件形式广播给监听者。
 *
 * P2-2 事务边界：所有公开方法走 [runDraftPatchTransaction]，
 * 保证 use case 的纯计算和 emit 之间任何异常都不会让 workflow 进入半应用状态——
 * use case 没有副作用，emit 失败时记录 warn 日志但不再向上抛，
 * 避免上游（命令路由 / Action）拿到半成功的语义。
 *
 * @param snapshotProvider 应用快照提供者
 * @param eventSink 应用事件接收器
 * @param graphPatchApplyService 图补丁应用服务
 * @param logger 诊断日志，便于 emit 失败时落到 IDE 日志
 */
internal class DraftPatchWorkflow(
    private val snapshotProvider: ApplicationSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    graphPatchApplyService: GraphPatchApplyService,
    private val logger: Logger = Logger.getInstance(DraftPatchWorkflow::class.java),
) {
    /** 实际执行业务的 use case。 */
    private val useCase = DraftPatchUseCase(graphPatchApplyService)

    /** 准备一份草稿补丁预览（不应用）。 */
    fun previewDraftPatch(patch: GraphPatch) {
        runDraftPatchTransaction(
            operation = { useCase.previewDraftPatch(patch) },
            buildEvent = GraphEditorApplicationEvent::DraftPatchPreviewReady,
        )
    }

    /**
     * 应用草稿补丁预览。
     *
     * @param operationIds 指定操作 ID 集合；为 null 表示应用全部
     * @return 应用后的图；未应用时返回 null
     */
    fun applyDraftPatchPreview(operationIds: Set<String>? = null): GraphDocument? {
        val result = runDraftPatchTransaction(
            operation = {
                useCase.applyDraftPatchPreview(
                    snapshot = snapshotProvider.snapshot(),
                    operationIds = operationIds,
                )
            },
            buildEvent = GraphEditorApplicationEvent::DraftPatchApplied,
        ) ?: return null
        return (result as? ApplyDraftPatchUseCaseResult.Applied)?.graph
    }

    /** 清除草稿补丁预览（不应用）。 */
    fun clearDraftPatchPreview() {
        runDraftPatchTransaction(
            operation = { useCase.clearDraftPatchPreview(snapshotProvider.snapshot()) },
            buildEvent = GraphEditorApplicationEvent::DraftPatchCleared,
        )
    }

    /**
     * 从指定来源恢复草稿补丁预览。
     *
     * @param source 来源（QA / DIFF_REVIEW / LAST_APPLIED）
     * @return 恢复的补丁；无可恢复时返回 null
     */
    fun restoreDraftPatchPreview(source: DraftPatchPreviewSource): GraphPatch? {
        val result = runDraftPatchTransaction(
            operation = {
                useCase.restoreDraftPatchPreview(
                    snapshot = snapshotProvider.snapshot(),
                    source = source.toUseCaseSource(),
                )
            },
            buildEvent = GraphEditorApplicationEvent::DraftPatchRestored,
        ) ?: return null
        return (result as? RestoreDraftPatchPreviewUseCaseResult.Restored)?.patch
    }

    /** 撤销最近一次应用的草稿补丁。 */
    fun undoLastDraftPatchApply(): GraphDocument? {
        val result = runDraftPatchTransaction(
            operation = { useCase.undoLastDraftPatchApply(snapshotProvider.snapshot()) },
            buildEvent = GraphEditorApplicationEvent::DraftPatchUndone,
        ) ?: return null
        return (result as? UndoDraftPatchApplyUseCaseResult.Undone)?.graph
    }

    /**
     * Draft patch 操作事务封装。
     *
     * - [operation] 是 use case 调用，纯计算无副作用，异常会被上抛给调用者（视为业务失败）
     * - operation 成功后 [buildEvent] 把结果包装为事件，再 emit
     * - emit 抛异常时仅记录 warn 日志，**不再向上传播**——use case 已计算出结果，
     *   把结果返回给调用方让其继续后续动作（UI 反馈、其他工作流等）。
     *
     * **残余风险（调用方需要知晓）**：emit 抛异常意味着至少一个订阅者没处理本次事件。
     * 若订阅者中包含真正改 snapshot 的 presenter，可能出现「use case 算出新图、调用方拿到 graph、
     * 但实际 snapshot 没更新」的瞬时不一致。设计选择：宁可继续把结果给调用方，让 UI 显示
     * use case 推导出的新图，也不要把整个调用打成失败（因为 use case 没失败）。
     * 真正的回滚责任在 presenter 侧——presenter 应保证多个 markXxx 步骤要么全成要么全败。
     *
     * 返回 use case 的结果；若调用者不需要结果，使用 [runDraftPatchTransactionUnit]。
     */
    private inline fun <T : Any> runDraftPatchTransaction(
        operation: () -> T,
        buildEvent: (T) -> GraphEditorApplicationEvent,
    ): T? {
        val result = operation()
        return try {
            eventSink.emit(buildEvent(result))
            result
        } catch (e: Exception) {
            // emit 失败：use case 结果仍返回给调用方（设计选择，见函数 KDoc）；事件订阅者可能不一致
            logger.warn(
                "DraftPatchWorkflow emit failed; use case result still returned to caller (event subscribers may be inconsistent)",
                e,
            )
            result
        }
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
