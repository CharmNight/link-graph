package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 把多次状态修改合并到一次同步通知中的会话对象。
 */
internal class GraphEditorStateSyncSession(
    /** 保存图编辑器状态服务。 */
    private val stateService: GraphEditorStateService,
    /** 保存触发同步请求的回调。 */
    private val onSyncRequested: () -> Unit,
) {
    /** 标记会话期间是否产生了状态变更。 */
    private var dirty: Boolean = false

    /**
     * 执行一次状态修改，并把会话标记为脏。
     */
    fun apply(action: GraphEditorStateService.() -> Unit) {
        stateService.action()
        dirty = true
    }

    /**
     * 读取当前状态快照。
     */
    fun snapshot(): GraphEditorStateService.Snapshot = stateService.snapshot()

    /**
     * 在会话结束时按需触发一次同步。
     */
    fun flush() {
        if (!dirty) {
            return
        }
        // 只有实际发生状态变更时才通知前端同步。
        onSyncRequested()
        dirty = false
    }
}

/**
 * 在一个同步会话内执行状态修改，并在结束时自动 flush。
 */
internal fun <T> withGraphEditorStateSyncSession(
    stateService: GraphEditorStateService,
    onSyncRequested: () -> Unit,
    block: GraphEditorStateSyncSession.() -> T,
): T {
    // 会话对象负责聚合多次状态写入后的同步请求。
    val session = GraphEditorStateSyncSession(
        stateService = stateService,
        onSyncRequested = onSyncRequested,
    )
    return try {
        session.block()
    } finally {
        // 无论 block 是否抛错，都尝试把已产生的变更同步出去。
        session.flush()
    }
}
