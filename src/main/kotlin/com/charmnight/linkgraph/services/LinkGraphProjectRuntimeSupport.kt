package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一承接项目级运行时辅助能力，避免主服务继续混入线程切换、设置解析和 trace 逻辑。
 */
internal class LinkGraphProjectRuntimeSupport(
    private val project: Project,
    private val logger: Logger,
    private val openSettingsOverrideProvider: () -> (() -> Unit)?,
    private val effectiveGenerationSettingsOverrideProvider: () -> LinkGraphSettingsState?,
) {
    private val runtimeTraceEnabled: Boolean =
        System.getenv("LINKGRAPH_DEBUG_TRACE")?.trim()?.equals("true", ignoreCase = true) == true

    fun stateService(): GraphEditorStateService = project.getService(GraphEditorStateService::class.java)

    fun effectiveGenerationSettings(): LinkGraphSettingsState = effectiveGenerationSettingsOverrideProvider()
        ?: ApplicationManager.getApplication()
            .getService(LinkGraphSettingsService::class.java)
            .snapshot()

    fun openSettingsDialog() {
        computeOnIdeThread {
            openSettingsOverrideProvider()?.invoke()
                ?: ShowSettingsUtil.getInstance().showSettingsDialog(project, LinkGraphSettingsConfigurable::class.java)
        }
    }

    fun runtimeTrace(message: () -> String) {
        if (runtimeTraceEnabled) {
            logger.warn(message())
        }
    }

    fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return WriteIntentReadAction.compute<T, RuntimeException>(action)
        }

        val completed = AtomicBoolean(false)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(WriteIntentReadAction.compute<T, RuntimeException>(action))
                    completed.set(true)
                } catch (throwable: Throwable) {
                    error.set(throwable)
                }
            },
            ModalityState.defaultModalityState(),
        )
        error.get()?.let { throw it }
        check(completed.get()) { "未能在 IDEA 线程中完成链路图请求" }
        return result.get()
    }
}
