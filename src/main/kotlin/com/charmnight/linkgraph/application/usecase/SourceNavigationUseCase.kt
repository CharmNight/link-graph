package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.SourceNavigationAnchors

/**
 * 源码导航用例的输出联合类型。
 * - [MissingTrustedNode]：在可信导航索引中找不到该节点；
 * - [NotNavigable]：节点存在但缺少可定位的源码信息；
 * - [Ready]：导航准备就绪，可以跳转；
 * - [SettingsOpenRequested]：用户请求打开设置页（例如配置源码路径）。
 */
sealed interface SourceNavigationUseCaseResult {
    /** 节点不在可信索引中；通常意味着节点已被删除或 ID 错误。 */
    data class MissingTrustedNode(val nodeId: String) : SourceNavigationUseCaseResult

    /** 节点存在但无法跳转到源码（缺少位置和签名）。 */
    data class NotNavigable(val node: GraphNode) : SourceNavigationUseCaseResult

    /** 导航准备就绪。 */
    data class Ready(val node: GraphNode) : SourceNavigationUseCaseResult

    /** 用户请求打开设置页。 */
    data object SettingsOpenRequested : SourceNavigationUseCaseResult
}

/**
 * 源码导航用例。
 *
 * 把"找出节点 → 判断是否可跳转 → 返回可派发结果"这个流程封装为单个 use case，
 * 让 UI 层只关心结果类型，不需要自己组装校验逻辑。
 *
 * @param navigationNodeFinder 节点查找函数；通常委托给 [findTrustedNavigationNode]
 */
class SourceNavigationUseCase(
    private val navigationNodeFinder: (WorkflowEditorSnapshot, String) -> GraphNode?,
) {
    /**
     * 请求对指定节点做源码导航。
     *
     * @param snapshot 当前编辑器快照
     * @param nodeId 待跳转的节点 ID
     * @return 导航结果；调用方按子类分支处理
     */
    fun requestSourceNavigation(
        snapshot: WorkflowEditorSnapshot,
        nodeId: String,
    ): SourceNavigationUseCaseResult {
        val node = navigationNodeFinder(snapshot, nodeId)
            ?: return SourceNavigationUseCaseResult.MissingTrustedNode(nodeId)
        // 节点不可跳转（无位置/签名）时返回 NotNavigable 让 UI 提示用户
        if (!canNavigateToSource(node)) {
            return SourceNavigationUseCaseResult.NotNavigable(node)
        }
        return SourceNavigationUseCaseResult.Ready(node)
    }

    /** 请求打开设置页。供 UI 在缺失配置时引导用户调整。 */
    fun requestOpenSettings(): SourceNavigationUseCaseResult.SettingsOpenRequested =
        SourceNavigationUseCaseResult.SettingsOpenRequested

    /** 判断节点是否携带足够的源码定位信息。 */
    private fun canNavigateToSource(node: GraphNode): Boolean {
        return SourceNavigationAnchors.canNavigate(node)
    }
}
