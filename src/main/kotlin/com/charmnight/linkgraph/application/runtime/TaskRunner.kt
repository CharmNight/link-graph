package com.charmnight.linkgraph.application.runtime

import java.util.concurrent.CompletableFuture

/**
 * 平台无关的任务调度端口（P4-3：IntelliJ threading 集中到 port）。
 *
 * 之前 application/workflow 直接调用 `ReadAction.compute` / `ApplicationManager.invokeLater` /
 * `AppExecutorUtil.getAppExecutorService` / `ModalityState.defaultModalityState` 等 IntelliJ 类型，
 * 让 application 层绑定到 IntelliJ 平台。
 *
 * 本接口提供 3 个平台无关操作：
 * - [background]：在后台线程执行（对应 IntelliJ AppExecutorUtil）
 * - [ui]：在 UI 线程执行，按 [UiPolicy] 决定模态（对应 invokeAndWait + ModalityState）
 * - [read]：在读锁内执行（对应 ReadAction.compute）
 *
 * 调用方只依赖本接口，IntelliJ 类型由 [IntelliJTaskRunnerAdapter]（ui 层）映射。
 */
interface TaskRunner {
    /** UI 调度的模态策略，application 自己的概念，与 IntelliJ ModalityState 解耦。 */
    enum class UiPolicy {
        /** 任意模态都执行（含 modal）— 对应 defaultModalityState。 */
        ANY,
        /** 仅在非模态时执行 — 对应 nonModal。 */
        NON_MODAL,
        /** 在当前模态执行（包括 modal）— 对应 defaultModalityState，常用于「跟当前 UI 流绑定」。 */
        CURRENT_MODAL,
    }

    /** 在后台线程执行 [block]，返回 future。 */
    fun <T> background(block: () -> T): CompletableFuture<T>

    /** 在 UI 线程按 [policy] 模态同步执行 [block]。 */
    fun <T> ui(policy: UiPolicy, block: () -> T): T

    /** 在读锁内同步执行 [block]（PSI / 索引访问需要）。 */
    fun <T> read(block: () -> T): T
}

/**
 * 单线程同步执行的测试桩 [TaskRunner] 实现。
 *
 * 所有操作在当前线程立即执行，便于单测隔离 IntelliJ 平台依赖。
 */
class SameThreadTaskRunner : TaskRunner {
    override fun <T> background(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        try {
            future.complete(block())
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        }
        return future
    }

    override fun <T> ui(policy: TaskRunner.UiPolicy, block: () -> T): T = block()

    override fun <T> read(block: () -> T): T = block()
}
