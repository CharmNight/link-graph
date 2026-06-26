package com.charmnight.linkgraph.ui.runtime

import com.charmnight.linkgraph.application.runtime.TaskRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture

/**
 * IntelliJ 平台 [TaskRunner] 适配器（P4-3）。
 *
 * 把 [TaskRunner.UiPolicy] 映射到 IntelliJ ModalityState，
 * 把 [TaskRunner.background] 映射到 AppExecutorUtil，
 * 把 [TaskRunner.read] 映射到 ReadAction.compute。
 *
 * application/workflow 调用方只依赖 [TaskRunner]，本类是 IntelliJ 平台的具体实现。
 */
class IntelliJTaskRunnerAdapter(
    @Suppress("unused") private val project: Project,
) : TaskRunner {
    override fun <T> background(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    override fun <T> ui(policy: TaskRunner.UiPolicy, block: () -> T): T {
        val modality = when (policy) {
            TaskRunner.UiPolicy.ANY -> ModalityState.defaultModalityState()
            TaskRunner.UiPolicy.NON_MODAL -> ModalityState.nonModal()
            TaskRunner.UiPolicy.CURRENT_MODAL -> ModalityState.defaultModalityState()
        }
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait({
            result = block()
        }, modality)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun <T> read(block: () -> T): T =
        ReadAction.compute<T, RuntimeException> { block() }
}
