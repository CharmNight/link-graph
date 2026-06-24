package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink

/**
 * 图编辑器对外部"展示能力"的依赖端口。
 *
 * 应用层不直接依赖 IntelliJ 平台 API，而是通过该端口取得若干"展示器"——
 * 编辑器快照、应用快照、工具图快照、工作台提交器、事件接收器。
 * 这种间接让应用层可以在测试中被桩替换，也便于未来替换底层实现。
 */
interface GraphEditorPresentationProvider {
    /** 编辑器快照提供者：读取当前活动编辑器的可见内容、光标、选区等。 */
    fun editorSnapshotProvider(): EditorSnapshotProvider

    /** 应用快照提供者：读取整个应用层级的全局状态（项目、模块等）。 */
    fun applicationSnapshotProvider(): ApplicationSnapshotProvider

    /** 工具图快照提供者：读取工具窗口相关的图状态。 */
    fun toolGraphSnapshotProvider(): ToolGraphSnapshotProvider

    /** 工作台图提交器：把工作台图变更写回底层存储或协同更新通道。 */
    fun workspaceGraphCommitter(): WorkspaceGraphCommitter

    /** 事件接收器：把应用层事件分发到外部监听者（UI、日志、桥接层等）。 */
    fun eventSink(): GraphEditorApplicationEventSink
}
