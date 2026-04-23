package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 把多次状态修改合并到一次同步通知中的会话对象。
 */
internal class GraphEditorStateSyncSession(
    /** 保存批量修改所使用的草稿上下文。 */
    private val mutationContext: GraphEditorStateMutationContext,
    /** 保存最终提交动作。 */
    private val commit: () -> Unit,
    /** 保存触发同步请求的回调。 */
    private val onSyncRequested: () -> Unit,
) {
    /** 标记会话期间是否产生了状态变更。 */
    private var dirty: Boolean = false

    /**
     * 执行一次状态修改，并把会话标记为脏。
     */
    fun apply(action: GraphEditorStateMutationContext.() -> Unit) {
        mutationContext.action()
        dirty = true
    }

    /**
     * 读取当前状态快照。
     */
    fun snapshot(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = mutationContext.snapshot()

    /**
     * 在会话结束时按需触发一次同步。
     */
    fun flush() {
        if (!dirty) {
            return
        }
        commit()
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
    val draftContext = stateService.newDraftMutationContext()
    val session = GraphEditorStateSyncSession(
        mutationContext = draftContext,
        commit = {
            stateService.replaceSnapshot(draftContext.committedState())
        },
        onSyncRequested = onSyncRequested,
    )
    val result = session.block()
    try {
        session.flush()
    } catch (throwable: Throwable) {
        throw throwable
    }
    return result
}
