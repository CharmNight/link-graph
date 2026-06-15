package com.charmnight.linkgraph.ui

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal class GraphEditorTransportRenderScheduler(
    private val renderExecutor: Executor,
    private val dispatchExecutor: ((() -> Unit) -> Unit),
) {
    private val latestRevision = AtomicLong(Long.MIN_VALUE)
    private val renderLock = Any()

    fun <T> schedule(
        revision: Long,
        render: () -> T?,
        dispatch: (T) -> Unit,
    ) {
        latestRevision.updateAndGet { current -> maxOf(current, revision) }
        renderExecutor.execute {
            if (revision != latestRevision.get()) {
                return@execute
            }
            val rendered = synchronized(renderLock) {
                if (revision != latestRevision.get()) {
                    return@execute
                }
                render()
            } ?: return@execute
            dispatchExecutor {
                if (revision == latestRevision.get()) {
                    dispatch(rendered)
                }
            }
        }
    }
}
