package com.charmnight.linkgraph.ui

import com.intellij.openapi.project.Project

/**
 * JCEF 前端与项目级服务之间的消息桥。
 *
 * 前端只认识简单消息，真正的状态变更、文件导航和生成动作都从这里转给项目服务。
 * 本桥负责：
 * - 把前端发来的 [GraphEditorMessage] 路由到正确的服务（状态服务或命令路由）；
 * - 在前端就绪时触发上层回调，便于做初始化握手；
 * - 暴露当前快照给前端桥接层。
 */
class GraphEditorBridge(
    /** 当前桥接器所属项目。 */
    private val project: Project,
    /** 前端准备完成时触发的回调；携带前端最后应用的 revision。 */
    private val onFrontendReady: (Long?) -> Unit = {},
    /** 前端确认收到快照后的回调；携带被确认的 revision。 */
    private val onSnapshotAck: (Long) -> Unit = {},
) {
    /** 编辑器状态服务；持有所有视图状态。 */
    private val stateService: GraphEditorStateService = project.getService(GraphEditorStateService::class.java)
    /** 图编辑器 bridge 命令路由；处理需要业务逻辑的命令。 */
    private val commandRouter: GraphEditorCommandRouter = project.getService(GraphEditorCommandRouter::class.java)

    /** 前端页面加载完成后登记入口地址；让上层知道前端已可通信。 */
    fun onFrontendLoaded(entryUrl: String) {
        stateService.markFrontendLoaded(entryUrl)
    }

    /**
     * 把前端消息分发到对应的项目服务或状态服务。
     * 这里不做复杂业务判断，只负责路由与最小上下文衔接。
     *
     * @param message 前端发来的消息
     */
    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            // 节点选中：直接更新状态服务
            is GraphEditorMessage.NodeSelected -> stateService.selectNode(message.nodeId)
            is GraphEditorMessage.CollapseInvocationExpansion ->
                stateService.collapseInvocationExpansion(message.expansionId)
            is GraphEditorMessage.OpenInvocationExpansion ->
                stateService.openInvocationExpansion(message.expansionId)
            is GraphEditorMessage.ActivateInvocationExpansion ->
                stateService.activateInvocationExpansion(message.expansionId)
            // 前端就绪：触发上层握手回调
            is GraphEditorMessage.FrontendReady -> onFrontendReady(message.lastAppliedRevision)
            // 快照确认：触发上层确认回调
            is GraphEditorMessage.SnapshotAck -> onSnapshotAck(message.revision)
            // 链路讲解结果：直接写入异步请求状态
            is GraphEditorMessage.GraphBeautificationResult -> stateService.asyncRequests.markGraphBeautificationResult(message.result)
            // 其他命令都走命令路由
            else -> commandRouter.dispatch(message)
        }
    }

    /** 返回当前桥接器观察到的最新状态快照。 */
    fun currentState(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        return stateService.snapshot()
    }
}
