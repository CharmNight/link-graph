package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SubjectResolutionWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val requestCoordinator: SubjectGraphRequestCoordinator,
) {
    fun locateCurrentSubject(editor: Editor? = null): SubjectHandle? {
        return computeOnIdeThread {
            val targetEditor = editor ?: FileEditorManager.getInstance(dependencies.project).selectedTextEditor
                ?: return@computeOnIdeThread null
            dependencies.subjectLocatorProvider().locate(dependencies.project, targetEditor)
        }
    }

    fun locateCurrentCodeSubject(): CodeSubjectHandle? {
        val handle = locateCurrentSubject()
        val codeHandle = handle as? CodeSubjectHandle
        if (codeHandle == null) {
            debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                "当前光标不在方法内，无法提取链路图"
            }
        }
        return codeHandle
    }

    fun previewCurrentEditorSubjectKind(editor: Editor? = null): SubjectPreviewKind? {
        return dependencies.subjectLocatorProvider().previewKind(dependencies.project, editor, commitDocument = false)
    }

    fun shouldDeferCurrentMethodResolutionUntilSmart(): Boolean {
        if (!DumbService.isDumb(dependencies.project)) {
            return false
        }
        return currentEditorPreviewKind() == SubjectPreviewKind.CODE_SUBJECT
    }

    fun resolveCodeSubjectBySignatureAsync(
        signature: String,
        requestId: Long,
        failureAction: String,
        onResolved: (CodeSubjectHandle) -> Unit,
    ) {
        val startedAt = System.nanoTime()
        dependencies.asyncRequestLifecycle.runBackgroundTask(
            work = {
                ReadAction.compute<CodeSubjectHandle?, RuntimeException> {
                    locateCodeSubjectBySignatureInReadAction(signature)
                }
            },
            onCompleted = { result ->
                if (dependencies.project.isDisposed || !requestCoordinator.isLatest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { handle ->
                        if (handle == null) {
                            dependencies.logger.warn("$failureAction 失败: signature=$signature, reason=methodNotFound")
                            dependencies.emitFeedback(
                                ApplicationFeedbackLevel.WARNING,
                                "未在当前项目中找到方法：$signature",
                            )
                        } else {
                            debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                                "$failureAction 定位主体完成: signature=${handle.methodSignature}, durationMs=${(System.nanoTime() - startedAt) / 1_000_000}"
                            }
                            onResolved(handle)
                        }
                    },
                    onFailure = { throwable ->
                        dependencies.logger.warn("$failureAction 异常", throwable)
                        dependencies.emitFeedback(
                            ApplicationFeedbackLevel.ERROR,
                            "$failureAction 失败：${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                    },
                )
            },
        )
    }

    private fun locateCodeSubjectBySignatureInReadAction(signature: String): CodeSubjectHandle? {
        val method = DebugMethodSignatureLocator.find(dependencies.project, signature) ?: return null
        val file = method.containingFile ?: method.navigationElement.containingFile ?: return null
        return dependencies.codeSubjectHandleFactory.create(file, method)
    }

    private fun currentEditorPreviewKind(): SubjectPreviewKind? {
        return computeOnIdeThread {
            val editor = FileEditorManager.getInstance(dependencies.project).selectedTextEditor
                ?: return@computeOnIdeThread null
            previewCurrentEditorSubjectKind(editor)
        }
    }

    private fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }
        val completed = AtomicBoolean(false)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(action())
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
