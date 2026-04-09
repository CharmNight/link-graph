package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.GraphBeautificationResult as GraphBeautificationPayload
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/**
 * 前后端之间约定的编辑器消息协议。
 * 一期只保留最小消息集合，保证导入、导出、diff、单节点编辑、代码草案写入都能串起来。
 */
sealed interface GraphEditorMessage {
    /**
     * 标识草稿补丁预览的来源。
     */
    enum class DraftPatchPreviewSource {
        /** 表示预览来自审计结果。 */
        AUDIT,
        /** 表示预览来自差异评审。 */
        DIFF_REVIEW,
        /** 表示预览来自最近一次应用结果。 */
        LAST_APPLIED,
    }

    /**
     * 加载整张图到前端。
     */
    data class LoadGraph(
        /** 保存要加载的图。 */
        val graph: GraphDocument,
        /** 保存图来源描述。 */
        val source: String,
    ) : GraphEditorMessage

    /**
     * 请求导入 Mermaid 文本。
     */
    data class ImportMermaid(
        /** 保存 Mermaid 原文。 */
        val mermaid: String,
    ) : GraphEditorMessage

    /** 请求导出当前图为 Mermaid。 */
    data object ExportMermaid : GraphEditorMessage

    /** 请求切换到差异模式。 */
    data object ShowDiffMode : GraphEditorMessage

    /**
     * 通知前端当前选中的节点。
     */
    data class NodeSelected(
        /** 保存选中节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /**
     * 通知前端图结构已变更。
     */
    data class GraphChanged(
        /** 保存最新图数据。 */
        val graph: GraphDocument,
    ) : GraphEditorMessage

    /**
     * 通知前端布局已变更。
     */
    data class LayoutChanged(
        /** 保存节点布局位置映射。 */
        val positions: Map<String, GraphLayoutPosition>,
    ) : GraphEditorMessage

    /**
     * 通知后端前端桥接层已就绪。
     */
    data class FrontendReady(
        /** 保存前端最近一次已应用的修订号。 */
        val lastAppliedRevision: Long? = null,
    ) : GraphEditorMessage

    /**
     * 通知后端某个快照已被前端确认。
     */
    data class SnapshotAck(
        /** 保存已确认的修订号。 */
        val revision: Long,
    ) : GraphEditorMessage

    /**
     * 请求执行源码跳转。
     */
    data class RequestSourceNavigation(
        /** 保存目标节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /**
     * 请求展开溢出摘要节点。
     */
    data class RequestExpandOverflowNode(
        /** 保存待展开节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /** 请求计算同步预览。 */
    data object RequestSyncPreview : GraphEditorMessage

    /**
     * 请求执行图审计。
     */
    data class RequestAudit(
        /** 保存用户问题。 */
        val question: String,
        /** 保存选中的节点标识列表。 */
        val selectedNodeIds: List<String> = emptyList(),
    ) : GraphEditorMessage

    /**
     * 请求执行差异评审。
     */
    data class RequestDiffReview(
        /** 保存用户问题。 */
        val question: String,
        /** 保存选中的差异条目标识列表。 */
        val selectedDiffItemIds: List<String> = emptyList(),
    ) : GraphEditorMessage

    /**
     * 请求执行图讲解。
     */
    data class RequestGraphBeautification(
        /** 保存用户目标。 */
        val goal: String = "",
        /** 保存风格偏好。 */
        val preferredStyle: String? = null,
        /** 保存讲解关注点。 */
        val explanationFocus: String? = null,
    ) : GraphEditorMessage

    /**
     * 返回图讲解结果。
     */
    data class GraphBeautificationResult(
        /** 保存讲解结果载荷。 */
        val result: GraphBeautificationPayload,
    ) : GraphEditorMessage

    /**
     * 请求应用草稿补丁预览。
     */
    data class ApplyDraftPatchPreview(
        /** 保存待应用的操作标识集合。 */
        val operationIds: Set<String>? = null,
    ) : GraphEditorMessage

    /** 请求清空草稿补丁预览。 */
    data object ClearDraftPatchPreview : GraphEditorMessage

    /**
     * 请求恢复指定来源的草稿补丁预览。
     */
    data class RestoreDraftPatchPreview(
        /** 保存预览来源。 */
        val source: DraftPatchPreviewSource,
    ) : GraphEditorMessage

    /** 请求撤销最近一次草稿补丁应用。 */
    data object UndoLastDraftPatchApply : GraphEditorMessage

    /** 请求生成改动计划。 */
    data object RequestGenerationPlan : GraphEditorMessage

    /** 请求生成代码草稿。 */
    data object RequestCodeDrafts : GraphEditorMessage

    /** 请求加载当前方法图。 */
    data object RequestCurrentMethodGraph : GraphEditorMessage

    /**
     * 请求切换分析展示模式。
     */
    data class RequestAnalysisDisplayMode(
        /** 保存目标展示模式。 */
        val displayMode: AnalysisDisplayMode,
    ) : GraphEditorMessage

    /** 请求打开设置页。 */
    data object OpenSettings : GraphEditorMessage

    /** 请求批量应用代码草稿。 */
    data object ApplyCodeDrafts : GraphEditorMessage

    /**
     * 请求应用单个代码草稿。
     */
    data class ApplySingleCodeDraft(
        /** 保存草稿标识。 */
        val draftId: String,
    ) : GraphEditorMessage

    /**
     * 请求跳转到草稿文件。
     */
    data class RequestDraftNavigation(
        /** 保存目标文件路径。 */
        val targetPath: String,
    ) : GraphEditorMessage
}
