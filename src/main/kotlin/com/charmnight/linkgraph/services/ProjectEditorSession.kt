package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 封装项目级图编辑器状态读写，以及状态变化后的浏览器同步。
 */
internal class ProjectEditorSession(
    /** 保存图编辑器状态服务。 */
    private val stateService: GraphEditorStateService,
    /** 保存浏览器同步请求回调。 */
    private val onBrowserSyncRequested: () -> Unit,
) {
    /**
     * 读取当前状态快照。
     */
    fun snapshot(): GraphEditorStateService.Snapshot = stateService.snapshot()

    /**
     * 对编辑器状态执行一次原子修改。
     */
    fun mutate(
        syncBrowser: Boolean = true,
        action: GraphEditorStateService.() -> Unit,
    ) {
        mutateBatch(syncBrowser) {
            apply(action)
        }
    }

    /**
     * 在同步会话中批量修改编辑器状态。
     */
    fun <T> mutateBatch(
        syncBrowser: Boolean = true,
        block: GraphEditorStateSyncSession.() -> T,
    ): T {
        val onSyncRequested: () -> Unit = if (syncBrowser) {
            onBrowserSyncRequested
        } else {
            {}
        }
        return withGraphEditorStateSyncSession(
            stateService = stateService,
            onSyncRequested = onSyncRequested,
            block = block,
        )
    }

    /**
     * 记录工作图变化，并按需通知浏览器同步。
     */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        syncBrowser: Boolean = true,
    ) {
        stateService.markGraphChanged(
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
        )
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }
}
