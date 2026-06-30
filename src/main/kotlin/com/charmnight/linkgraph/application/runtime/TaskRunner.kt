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
    /**
     * UI 调度的模态策略，application 自己的概念，与 IntelliJ ModalityState 解耦。
     *
     * 设计原则：策略值要能映射到具体平台行为，避免「同名但同义」造成歧义。
     * - [ANY] 对应 IntelliJ `defaultModalityState`（从 UI 线程发起时返回当前模态；从后台线程返回 non-modal）
     * - [NON_MODAL] 对应 IntelliJ `nonModal()`（必须等到无模态对话框时才执行）
     */
    enum class UiPolicy {
        /**
         * 在当前默认模态下执行。
         *
         * 在 UI 线程上调用 `invokeAndWait` 时，IntelliJ 会取调用线程的当前模态；
         * 在后台线程上调用时取 non-modal。覆盖「跟当前 UI 流绑定」「任意模态都执行」两种语义。
         */
        ANY,

        /** 仅在非模态时执行；如果有 modal 打开，会等到 modal 关闭再跑。 */
        NON_MODAL,
    }

    /** 在后台线程执行 [block]，返回 future。 */
    fun <T> background(block: () -> T): CompletableFuture<T>

    /** 在 UI 线程按 [policy] 模态同步执行 [block]。 */
    fun <T> ui(policy: UiPolicy, block: () -> T): T

    /** 在 IDE 进入 smart mode 后于 UI 线程执行 [block]。 */
    fun smartUi(policy: UiPolicy, block: () -> Unit)

    /** 在读锁内同步执行 [block]（PSI / 索引访问需要）。 */
    fun <T> read(block: () -> T): T

    /**
     * 以平台的非阻塞读动作执行 [readAction]，完成后在 UI 线程派发 [onUi]。
     *
     * workflow 层不直接持有 IntelliJ 的 `ReadAction.nonBlocking`、`finishOnUiThread`、
     * `ModalityState` 或 executor；这些平台细节由具体 adapter 处理。
     */
    fun <T> readAsync(
        requireSmartMode: Boolean = false,
        uiPolicy: UiPolicy = UiPolicy.ANY,
        readAction: () -> T,
        onUi: (T) -> Unit,
    ): CancellableTaskHandle
}

/** workflow 保存异步任务引用时只需要取消能力，不暴露平台 promise 类型。 */
interface CancellableTaskHandle {
    fun cancel()
}

/**
 * 单线程同步执行的测试桩 [TaskRunner] 实现。
 *
 * 所有操作在当前线程立即执行，便于单测隔离 IntelliJ 平台依赖。
 * 测试若需通过 [com.charmnight.linkgraph.application.composition.InfrastructureComposition]
 * 注入桩，使用 `project.replaceService(TaskRunner::class.java, SameThreadTaskRunner(), testDisposable)`。
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

    override fun smartUi(policy: TaskRunner.UiPolicy, block: () -> Unit) {
        block()
    }

    override fun <T> read(block: () -> T): T = block()

    override fun <T> readAsync(
        requireSmartMode: Boolean,
        uiPolicy: TaskRunner.UiPolicy,
        readAction: () -> T,
        onUi: (T) -> Unit,
    ): CancellableTaskHandle {
        val result = read(readAction)
        ui(uiPolicy) { onUi(result) }
        return object : CancellableTaskHandle {
            override fun cancel() = Unit
        }
    }
}
