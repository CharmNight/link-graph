package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.review.ReviewGraphResult

/**
 * 架构图状态呈现器。
 *
 * 把"索引图请求"的状态变化应用到状态服务，并触发浏览器同步。
 * 让命令处理层只关心业务结果，UI 同步由本呈现器统一处理。
 *
 * @param stateService 编辑器状态服务
 * @param requestBrowserSync 触发浏览器同步的回调
 */
class ArchitectureGraphStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    /**
     * 呈现"索引图请求已开始"。
     *
     * @param view 当前索引图视图
     * @param requestState 异步请求状态
     * @param statusMessage 状态消息
     */
    fun presentIndexedGraphRequestStarted(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.beginIndexedGraphRequest(view, requestState, statusMessage)
        requestBrowserSync()
    }

    /** 呈现"索引图请求失败"。 */
    fun presentIndexedGraphRequestFailed(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.markIndexedGraphRequestFailed(view, requestState, statusMessage)
        requestBrowserSync()
    }

    /** 呈现"架构图加载完成"。 */
    fun presentArchitectureGraph(
        view: ArchitectureGraphResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadArchitectureGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    /** 呈现"类图加载完成"。 */
    fun presentClassDiagram(
        view: ClassDiagramResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadClassDiagramView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    /** 呈现"审查图加载完成"。 */
    fun presentReviewGraph(
        view: ReviewGraphResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadReviewGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }
}
