package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
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
    fun snapshot(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = stateService.snapshot()

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
        workingGraphDirty: Boolean = true,
        syncBrowser: Boolean = true,
    ) {
        stateService.graph.markGraphChanged(
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            workingGraphDirty = workingGraphDirty,
        )
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }

    /**
     * 记录当前展示视图内的图结构变化，并按需通知浏览器同步。
     */
    fun markViewGraphChanged(
        graph: GraphDocument,
        displayMode: AnalysisDisplayMode,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        syncBrowser: Boolean = true,
    ) {
        stateService.graph.markViewGraphChanged(
            graph = graph,
            displayMode = displayMode,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            workingGraphDirty = workingGraphDirty,
        )
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }

    /**
     * 将 runtime 产物摘要写回到 UI 状态。
     * session 只负责状态同步，不参与 runtime 产物解析。
     */
    fun markRuntimeArtifactSummaries(
        scene: String,
        summaries: List<com.charmnight.linkgraph.ui.RuntimeArtifactSummary>,
        syncBrowser: Boolean = true,
    ) {
        stateService.workbench.markRuntimeArtifactSummaries(scene, summaries)
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }
}
