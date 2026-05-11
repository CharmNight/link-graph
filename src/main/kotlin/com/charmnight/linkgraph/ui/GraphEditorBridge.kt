package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorCommandRouter
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.intellij.openapi.project.Project

/**
 * JCEF 前端与项目级服务之间的消息桥。
 * 前端只认识简单消息，真正的状态变更、文件导航和生成动作都从这里转给项目服务。
 */
class GraphEditorBridge(
    /** 当前桥接器所属项目。 */
    private val project: Project,
    /** 前端准备完成时触发的回调。 */
    private val onFrontendReady: (Long?) -> Unit = {},
    /** 前端确认收到快照后的回调。 */
    private val onSnapshotAck: (Long) -> Unit = {},
) {
    /** 编辑器状态服务。 */
    private val stateService: GraphEditorStateService = project.getService(GraphEditorStateService::class.java)
    /** 图编辑器 bridge 命令路由。 */
    private val commandRouter: GraphEditorCommandRouter = project.getService(GraphEditorCommandRouter::class.java)
    /** 工作台布局偏好服务。 */
    private val workbenchLayoutPreferencesService: WorkbenchLayoutPreferencesService = project.getService(WorkbenchLayoutPreferencesService::class.java)
    private val workbenchPreferencesHydrationLock = Any()
    @Volatile
    private var workbenchPreferencesHydrated: Boolean = false

    /** 前端页面加载完成后登记入口地址。 */
    fun onFrontendLoaded(entryUrl: String) {
        stateService.markFrontendLoaded(entryUrl)
    }

    /**
     * 把前端消息分发到对应的项目服务或状态服务。
     * 这里不做复杂业务判断，只负责路由与最小上下文衔接。
     */
    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            is GraphEditorMessage.NodeSelected -> stateService.selectNode(message.nodeId)
            is GraphEditorMessage.FrontendReady -> onFrontendReady(message.lastAppliedRevision)
            is GraphEditorMessage.SnapshotAck -> onSnapshotAck(message.revision)
            is GraphEditorMessage.GraphBeautificationResult -> stateService.asyncRequests.markGraphBeautificationResult(message.result)
            else -> commandRouter.dispatch(message)
        }
    }

    /** 兼容旧接口，直接触发加载图消息。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        dispatch(GraphEditorMessage.LoadGraph(graph, source))
    }

    /** 返回当前桥接器观察到的最新状态快照。 */
    fun currentState(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        ensureWorkbenchPreferencesHydrated()
        return stateService.snapshot()
    }

    private fun ensureWorkbenchPreferencesHydrated() {
        if (workbenchPreferencesHydrated) {
            return
        }
        synchronized(workbenchPreferencesHydrationLock) {
            if (workbenchPreferencesHydrated) {
                return
            }
            val currentSnapshot = stateService.snapshot()
            if (currentSnapshot.workbenchSectionPreferences.isEmpty()) {
                val persistedPreferences = workbenchLayoutPreferencesService.snapshot()
                if (persistedPreferences.isNotEmpty()) {
                    stateService.workbench.markWorkbenchSectionPreferences(persistedPreferences)
                }
            }
            workbenchPreferencesHydrated = true
        }
    }
}
