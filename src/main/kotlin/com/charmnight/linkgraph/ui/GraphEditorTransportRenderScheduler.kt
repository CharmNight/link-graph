package com.charmnight.linkgraph.ui

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 编辑器传输层的渲染调度器。
 *
 * 引导态频繁更新可能触发多次渲染请求，本调度器负责：
 * - 跟踪最新 revision，过期的渲染请求直接跳过；
 * - 通过 renderLock 保证同一时刻只有一个渲染在执行；
 * - 把派发动作转到指定调度器上执行，避免阻塞渲染线程。
 * 这样既能保证最终一致（最新 revision 一定会被渲染），又能避免无意义的中间渲染。
 *
 * @param renderExecutor 渲染动作执行器（通常是单线程 executor）
 * @param dispatchExecutor 派发动作调度器（通常切回 EDT）
 */
internal class GraphEditorTransportRenderScheduler(
    private val renderExecutor: Executor,
    private val dispatchExecutor: ((() -> Unit) -> Unit),
) {
    /** 当前已调度的最新 revision；用于过期判断。 */
    private val latestRevision = AtomicLong(Long.MIN_VALUE)
    /** 渲染同步锁；保证渲染串行。 */
    private val renderLock = Any()

    /**
     * 调度一次渲染。
     *
     * @param revision 本次渲染对应的 revision
     * @param render 渲染函数；返回 null 表示无产物可派发
     * @param dispatch 派发函数；接受 render 的结果
     */
    fun <T> schedule(
        revision: Long,
        render: () -> T?,
        dispatch: (T) -> Unit,
    ) {
        // 推进最新 revision，让旧请求在执行时发现自己过期
        latestRevision.updateAndGet { current -> maxOf(current, revision) }
        renderExecutor.execute {
            // 进入渲染前先做一次轻量检查，避免无意义的锁竞争
            if (revision != latestRevision.get()) {
                return@execute
            }
            val rendered = synchronized(renderLock) {
                // 进入锁后再检查一次，防止竞态
                if (revision != latestRevision.get()) {
                    return@execute
                }
                render()
            } ?: return@execute
            // 派发动作转到指定调度器，并在执行前再校验 revision
            dispatchExecutor {
                if (revision == latestRevision.get()) {
                    dispatch(rendered)
                }
            }
        }
    }
}
