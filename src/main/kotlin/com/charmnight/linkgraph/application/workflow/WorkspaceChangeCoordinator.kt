package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 协调工作台图写入与随之需要失效的分析/请求状态。
 *
 * 当工作台图发生变化时，相关的语义分析缓存与未完成的异步请求都需要失效，
 * 否则上游可能基于过期数据做出错误判断。本类把这些副作用集中起来，
 * 让调用方只描述"图变了"，由协调器决定还要清掉哪些东西。
 */
internal class WorkspaceChangeCoordinator(
    /** 工作台图提交器，负责把新图写入底层存储。 */
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    /** 清空主题分析缓存的回调。 */
    private val clearSubjectAnalysisCache: () -> Unit,
    /** 失效异步请求的回调。 */
    private val invalidateAsyncRequests: () -> Unit,
) {
    /**
     * 重置工作台图上下文：清缓存 + 失效请求，但不写入新图。
     * 用于"切换主题"等不需要新图但需要重新分析的场景。
     */
    fun resetWorkspaceGraphContext() {
        clearSubjectAnalysisCache()
        invalidateAsyncRequests()
    }

    /**
     * 仅失效异步请求，不动主题分析缓存。
     * 用于"图未变但请求结果不再有效"的场景（例如外部索引刷新）。
     */
    fun invalidateRequests() {
        invalidateAsyncRequests()
    }

    /**
     * 标记工作台图变更：重置上下文后提交新图。
     *
     * @param graph 新图
     * @param selectedMethodSignature 当前选中的方法签名
     * @param preserveDraftPatchUndo 是否保留草稿补丁撤销栈
     * @param syncBrowser 是否同步刷新前端
     */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        syncBrowser: Boolean = true,
    ) {
        resetWorkspaceGraphContext()
        workspaceGraphCommitter.commitWorkspaceGraph(
            expectedSnapshotRevision = null,
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            syncBrowser = syncBrowser,
        )
    }
}
