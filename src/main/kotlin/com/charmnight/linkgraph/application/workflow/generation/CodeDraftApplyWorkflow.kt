package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.ProjectScopedPathPolicy
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.CodeDraftWriteResult
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files

/**
 * 代码草稿应用工作流：负责把生成出来的代码草稿批量/单个写入磁盘，
 * 并提供原生 diff/merge 入口以及源码导航能力。
 */
internal class CodeDraftApplyWorkflow(
    /** 工作流运行所需的依赖集合（项目、日志、快照、写入服务等）。 */
    private val dependencies: GenerationWorkflowDependencies,
) {
    /** 用于校验草稿目标路径必须落在项目目录内的策略。 */
    private val pathPolicy = ProjectScopedPathPolicy()

    /** 批量写入当前快照中的全部代码草稿，并广播写入报告，最后跳转到首个产物。 */
    fun applyCodeDrafts() {
        val snapshot = dependencies.snapshotProvider.snapshot()
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "开始批量写入代码草稿: drafts=${snapshot.generatedCodeDrafts.size}"
        }
        val report = dependencies.codeDraftWriterService.writeDrafts(
            dependencies.project.basePath,
            snapshot.generatedCodeDrafts,
        )
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "批量写入代码草稿完成: ${GenerationDiagnostics.summarizeWriteReport(report)}"
        }
        dependencies.emit(
            GraphEditorApplicationEvent.CodeDraftWriteReported(
                CodeDraftWriteResult(report = report),
            ),
        )
        report.writtenFiles.firstOrNull()?.let(dependencies.sourceNavigationServiceProvider()::navigateToPath)
    }

    /**
     * 按 ID 写入单个代码草稿，并依据写入结果选择成功/警告级别反馈给用户。
     *
     * @param draftId 待写入的代码草稿 ID
     */
    fun applySingleCodeDraft(draftId: String) {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val draft = snapshot.generatedCodeDrafts.firstOrNull { it.id == draftId }
        if (draft == null) {
            dependencies.emitGenerationFeedback(
                ApplicationFeedbackLevel.ERROR,
                "未找到代码草稿 '$draftId'，无法写入当前文件。",
            )
            return
        }
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "开始写入单个代码草稿: draftId=$draftId, targetPath=${draft.targetPath}"
        }
        val report = dependencies.codeDraftWriterService.writeDrafts(dependencies.project.basePath, listOf(draft))
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "单个代码草稿写入完成: ${GenerationDiagnostics.summarizeWriteReport(report)}"
        }
        val (level, message) = when {
            report.writtenFiles.contains(draft.targetPath) -> ApplicationFeedbackLevel.SUCCESS to
                "代码草稿已写入当前文件。"

            report.warnings.isNotEmpty() -> ApplicationFeedbackLevel.WARNING to
                report.warnings.first()

            report.skippedFiles.contains(draft.targetPath) -> ApplicationFeedbackLevel.WARNING to
                "当前文件未写入，请查看代码 diff 写入报告。"

            else -> ApplicationFeedbackLevel.WARNING to
                "当前文件未写入，请查看代码 diff 写入报告。"
        }
        dependencies.emit(
            GraphEditorApplicationEvent.CodeDraftWriteReported(
                CodeDraftWriteResult(
                    report = mergeWriteReport(snapshot.generatedCodeDraftWriteReport, report),
                    feedbackLevel = level,
                    statusMessage = message,
                ),
            ),
        )
        report.writtenFiles.firstOrNull()?.let(dependencies.sourceNavigationServiceProvider()::navigateToPath)
    }

    /**
     * 打开指定代码草稿的原生三向 merge 窗口，让用户在 IDE 内审阅并选择最终内容。
     * 会做项目路径约束校验，并禁止对已存在文件直接用纯 content 覆盖。
     *
     * @param draftId 目标代码草稿 ID
     */
    fun openCodeDraftNativeDiff(draftId: String) {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val draft = snapshot.generatedCodeDrafts.firstOrNull { it.id == draftId }
        if (draft == null) {
            dependencies.emitGenerationFeedback(
                ApplicationFeedbackLevel.ERROR,
                "未找到代码草稿 '$draftId'，无法打开原生 diff。",
            )
            return
        }
        val projectBasePath = dependencies.project.basePath
        if (projectBasePath.isNullOrBlank()) {
            dependencies.emitGenerationFeedback(
                ApplicationFeedbackLevel.ERROR,
                "项目根路径不可用，无法打开代码草稿 diff。",
            )
            return
        }
        val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
        val scopedTarget = pathPolicy.resolveWritableDraftTarget(projectBasePath, normalizedDraft.targetPath)
        if (scopedTarget == null) {
            dependencies.emitGenerationFeedback(
                ApplicationFeedbackLevel.ERROR,
                "已跳过 '${normalizedDraft.targetPath}'，因为它不存在或解析到了项目目录之外。",
            )
            return
        }
        val target = scopedTarget.path
        val targetExists = scopedTarget.existed
        val beforeText = when {
            targetExists -> Files.readString(target)
            normalizedDraft.content != null -> ""
            else -> null
        }
        if (beforeText == null) {
            dependencies.emitGenerationFeedback(
                ApplicationFeedbackLevel.ERROR,
                "目标文件 '${normalizedDraft.targetPath}' 不存在，无法打开代码草稿 diff。",
            )
            return
        }
        val afterText = when {
            normalizedDraft.editOperations.isNotEmpty() -> {
                val prepared = dependencies.codeDraftWriterService.prepareExistingFileDraft(projectBasePath, normalizedDraft)
                if (!prepared.canApply) {
                    dependencies.emitGenerationFeedback(
                        ApplicationFeedbackLevel.ERROR,
                        prepared.warnings.firstOrNull() ?: "无法为代码草稿准备原生 diff。",
                    )
                    return
                }
                prepared.previewText
            }
            normalizedDraft.content != null && !targetExists -> normalizedDraft.content
            normalizedDraft.content != null -> {
                dependencies.emitGenerationFeedback(
                    ApplicationFeedbackLevel.ERROR,
                    "现有文件代码草稿必须使用结构化 editOperations；已拒绝用 content 打开可写 merge，避免删除未授权逻辑。",
                )
                return
            }
            else -> {
                dependencies.emitGenerationFeedback(
                    ApplicationFeedbackLevel.ERROR,
                    "当前代码草稿没有可展示的 diff 内容。",
                )
                return
            }
        }
        ApplicationManager.getApplication().invokeLater(
            {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                if (virtualFile == null) {
                    dependencies.emitGenerationFeedback(
                        ApplicationFeedbackLevel.ERROR,
                        "无法定位目标文件 '${normalizedDraft.targetPath}' 的 IDE VirtualFile，无法打开可写入 merge。",
                    )
                    return@invokeLater
                }
                val request = runCatching {
                    DiffRequestFactory.getInstance().createMergeRequest(
                        dependencies.project,
                        virtualFile,
                        listOf(
                            beforeText.toByteArray(virtualFile.charset),
                            beforeText.toByteArray(virtualFile.charset),
                            afterText.toByteArray(virtualFile.charset),
                        ),
                        "代码草稿 Merge · ${normalizedDraft.title}",
                        listOf("当前文件", "当前基线", "生成预览"),
                    ) { result ->
                        when (result) {
                            MergeResult.CANCEL,
                            MergeResult.LEFT,
                            -> dependencies.emitGenerationFeedback(
                                ApplicationFeedbackLevel.INFO,
                                "已取消代码草稿 merge，当前文件未写入。",
                                preservePreviousStatusKind = true,
                            )

                            MergeResult.RIGHT,
                            MergeResult.RESOLVED,
                            -> dependencies.emitMergeWriteReport(
                                report = mergeWriteReport(
                                    dependencies.snapshotProvider.snapshot().generatedCodeDraftWriteReport,
                                    GeneratedCodeDraftWriteReport(
                                        writtenFiles = listOf(normalizedDraft.targetPath),
                                    ),
                                ),
                                level = ApplicationFeedbackLevel.SUCCESS,
                                message = "代码草稿 merge 已写入当前文件。",
                            )
                        }
                    }
                }.getOrElse { error ->
                    dependencies.emitGenerationFeedback(
                        ApplicationFeedbackLevel.ERROR,
                        error.message ?: "无法为代码草稿创建可写入 merge 请求。",
                    )
                    return@invokeLater
                }
                dependencies.showCodeDraftMergeRequest(dependencies.project, request)
            },
            ModalityState.defaultModalityState(),
        )
    }

    /** 请求跳转到指定项目相对路径对应的源码位置。 */
    fun requestDraftNavigation(targetPath: String) {
        dependencies.sourceNavigationServiceProvider().navigateToProjectPath(targetPath)
    }

    /** 合并历史写入报告与本次写入报告，去重产物，移除已写入的跳过项，并拼接告警信息。 */
    private fun mergeWriteReport(
        previous: GeneratedCodeDraftWriteReport?,
        current: GeneratedCodeDraftWriteReport,
    ) = GeneratedCodeDraftWriteReport(
        writtenFiles = (previous?.writtenFiles.orEmpty() + current.writtenFiles).distinct(),
        skippedFiles = (previous?.skippedFiles.orEmpty() + current.skippedFiles)
            .filterNot { it in current.writtenFiles || it in previous?.writtenFiles.orEmpty() }
            .distinct(),
        warnings = (previous?.warnings.orEmpty() + current.warnings).distinct(),
    )
}
