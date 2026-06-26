package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 主题解析工作流。
 *
 * 负责在 IDEA 编辑器与后台线程之间协调"当前光标位置对应的主题（方法/类等）"的定位与解析工作，
 * 是上层链路图、调查等流程获取主题句柄（SubjectHandle）的统一入口。
 */
internal class SubjectResolutionWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val requestCoordinator: SubjectGraphRequestCoordinator,
) {
    /**
     * 定位当前编辑器中光标所在的主题句柄。
     *
     * 若未显式传入 [editor]，则使用项目中当前选中的文本编辑器；整个过程会被调度到 IDEA 的 UI 线程执行。
     *
     * @param editor 指定的编辑器，传 null 表示使用当前选中的编辑器
     * @return 命中的主题句柄，若没有合适的编辑器或主题则返回 null
     */
    fun locateCurrentSubject(): SubjectHandle? {
        return computeOnIdeThread {
            // 不再接受 explicit editor：编辑器选择由 subject locator 内部从 FileEditorManager 拿
            val targetEditor = FileEditorManager.getInstance(dependencies.project).selectedTextEditor
                ?: return@computeOnIdeThread null
            dependencies.subjectLocatorProvider().locate(dependencies.project, targetEditor)
        }
    }

    /**
     * 仅在当前主题是代码主题（如方法）时返回其句柄。
     *
     * 内部先调用 [locateCurrentSubject]，再过滤出 [CodeSubjectHandle]；若当前光标不在方法上，则记录调试日志并返回 null。
     *
     * @return 当前光标所在的代码主题句柄；不是代码主题时返回 null
     */
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

    /**
     * 预览当前编辑器光标处的主题类型（不提交文档改动）。
     *
     * 主要用于在不影响编辑器内容的前提下，快速判断光标位置是否对应一个可解析的主题。
     *
     * @param editor 指定的编辑器，传 null 表示使用当前选中的编辑器
     * @return 预览到的主题类型；无法判断时返回 null
     */
    fun previewCurrentEditorSubjectKind(): SubjectPreviewKind? {
        // 不再接受 explicit editor：编辑器选择由 subject locator 内部从 FileEditorManager 拿
        return dependencies.subjectLocatorProvider().previewKind(dependencies.project, null, commitDocument = false)
    }

    /**
     * 判断是否需要将"当前方法解析"推迟到智能模式（Dumb Service 结束）之后再执行。
     *
     * 当项目仍处于 dumb（索引重建中）状态且当前光标预览为代码主题时返回 true，提示调用方稍后再试。
     *
     * @return true 表示应当推迟解析；否则表示可以立即执行
     */
    fun shouldDeferCurrentMethodResolutionUntilSmart(): Boolean {
        // 项目已进入智能模式时，无需推迟
        if (!DumbService.isDumb(dependencies.project)) {
            return false
        }
        return currentEditorPreviewKind() == SubjectPreviewKind.CODE_SUBJECT
    }

    /**
     * 在后台线程中根据方法签名异步解析出对应的代码主题句柄。
     *
     * 解析完成后会校验请求是否仍然是最新的（避免过期请求覆盖最新结果），并依据成功/失败/未找到三种情况分别回调或上报反馈。
     *
     * @param signature 目标方法的方法签名
     * @param requestId 本次请求的唯一标识，用于过期请求过滤
     * @param failureAction 失败时的描述文本，用于日志与用户反馈中区分场景
     * @param onResolved 解析成功且方法存在时的回调
     */
    fun resolveCodeSubjectBySignatureAsync(
        signature: String,
        requestId: Long,
        failureAction: String,
        onResolved: (CodeSubjectHandle) -> Unit,
    ) {
        // 记录起始时间，用于后续输出耗时统计
        val startedAt = System.nanoTime()
        dependencies.asyncRequestLifecycle.runBackgroundTask(
            work = {
                ReadAction.compute<CodeSubjectHandle?, RuntimeException> {
                    locateCodeSubjectBySignatureInReadAction(signature)
                }
            },
            onCompleted = { result ->
                // 项目已销毁或请求被更新请求覆盖时，直接丢弃本次结果
                if (dependencies.project.isDisposed || !requestCoordinator.isLatest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { handle ->
                        if (handle == null) {
                            // 未找到匹配方法：仅告警并以 WARNING 级别反馈给用户
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
                        // 解析过程中抛出异常：记录错误并以 ERROR 级别反馈
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

    /**
     * 在读操作上下文中根据方法签名定位代码主题句柄。
     *
     * 内部借助 [DebugMethodSignatureLocator] 找到方法元素，再向上取其所在文件，最后通过工厂构造出 [CodeSubjectHandle]。
     *
     * @param signature 目标方法签名
     * @return 解析得到的代码主题句柄；未找到方法或文件时返回 null
     */
    private fun locateCodeSubjectBySignatureInReadAction(signature: String): CodeSubjectHandle? {
        val method = DebugMethodSignatureLocator.find(dependencies.project, signature) ?: return null
        // 优先使用元素自身的 containingFile，退一步再尝试 navigationElement 所在文件
        val file = method.containingFile ?: method.navigationElement.containingFile ?: return null
        return dependencies.codeSubjectHandleFactory.create(file, method)
    }

    /**
     * 在 IDEA UI 线程上读取当前编辑器光标位置的主题预览类型。
     *
     * 用于在不依赖外部传入编辑器的前提下，安全地在 UI 线程中获取预览结果。
     *
     * @return 当前编辑器的主题预览类型；没有活动编辑器时返回 null
     */
    private fun currentEditorPreviewKind(): SubjectPreviewKind? {
        return computeOnIdeThread {
            previewCurrentEditorSubjectKind()
        }
    }

    /**
     * 在 IDEA 的 UI 派发线程上执行指定操作并返回结果。
     *
     * 若当前已处于派发线程则直接同步执行；否则通过 [ApplicationManager.invokeAndWait] 切换线程，
     * 并使用原子引用收集结果与可能抛出的异常，确保跨线程调用安全。
     *
     * @param T 操作返回值类型
     * @param action 实际要执行的操作
     * @return 操作的返回值
     */
    private fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        // 已在派发线程上则无需切换，直接同步执行
        if (application.isDispatchThread) {
            return action()
        }
        // 跨线程执行：用原子变量分别记录完成状态、返回值与异常
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
