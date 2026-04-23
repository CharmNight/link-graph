package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.GraphEditorStateService

/** 封装项目级编辑器状态提交；batch 内修改先落到草稿快照，再一次性提交并触发浏览器同步。 */
internal class ProjectEditorSession(
    private val stateService: GraphEditorStateService,
    private val onBrowserSyncRequested: () -> Unit,
) {
    fun snapshot(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = stateService.snapshot()

    fun mutate(
        syncBrowser: Boolean = true,
        action: GraphEditorStateMutationContext.() -> Unit,
    ) {
        mutateBatch(syncBrowser) {
            apply(action)
        }
    }

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
