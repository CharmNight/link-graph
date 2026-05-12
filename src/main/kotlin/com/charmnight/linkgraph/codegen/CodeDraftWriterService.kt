package com.charmnight.linkgraph.codegen

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CodeDraftWriterService(
    private val project: Project? = null,
    private val pathPolicy: ProjectScopedPathPolicy = ProjectScopedPathPolicy(),
) {
    private val codeEditApplyService = project?.let { CodeEditApplyService(it) }

    fun prepareExistingFileDraft(
        projectBasePath: String?,
        draft: GeneratedCodeDraft,
    ): PreparedCodeEditBatch {
        if (projectBasePath.isNullOrBlank()) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = draft.content.orEmpty(),
                warnings = listOf("项目根路径不可用，无法准备 existing-file patch。"),
            )
        }
        val applyService = codeEditApplyService
            ?: return PreparedCodeEditBatch(
                canApply = false,
                previewText = draft.content.orEmpty(),
                warnings = listOf("scope-safe apply 需要 IDE project 上下文。"),
            )
        val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
        if (normalizedDraft.editOperations.isEmpty()) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = normalizedDraft.content.orEmpty(),
                warnings = listOf("当前草稿不包含 existing-file 局部 patch。"),
            )
        }
        val target = safeFileSystemRead(normalizedDraft.targetPath) {
            pathPolicy.resolveExistingFile(projectBasePath, normalizedDraft.targetPath)
        }?.path ?: return PreparedCodeEditBatch(
            canApply = false,
            previewText = normalizedDraft.content.orEmpty(),
            warnings = listOf("已跳过 '${normalizedDraft.targetPath}'，因为它不存在或解析到了项目目录之外。"),
        )
        val beforeText = safeFileSystemRead(normalizedDraft.targetPath) {
            currentFileText(target)
        } ?: return PreparedCodeEditBatch(
            canApply = false,
            previewText = normalizedDraft.content.orEmpty(),
            warnings = listOf(fileSystemFailureWarning(normalizedDraft.targetPath, lastFileSystemError.get())),
        )
        return computeOnIdeThread {
            applyService.prepareEdits(
                filePath = normalizedDraft.targetPath,
                beforeText = beforeText,
                operations = normalizedDraft.editOperations,
                editScopes = normalizedDraft.editScopes,
            )
        }
    }

    fun writeDrafts(
        projectBasePath: String?,
        drafts: List<GeneratedCodeDraft>,
    ): GeneratedCodeDraftWriteReport {
        if (projectBasePath.isNullOrBlank()) {
            return GeneratedCodeDraftWriteReport(
                warnings = listOf("项目根路径不可用，无法写入草稿。"),
            )
        }

        val writtenFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        drafts.forEach { draft ->
            val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
            val scopedTarget = safeFileSystemRead(normalizedDraft.targetPath) {
                pathPolicy.resolveWritableDraftTarget(projectBasePath, normalizedDraft.targetPath)
            }
            if (scopedTarget == null) {
                skippedFiles += normalizedDraft.targetPath
                warnings += lastFileSystemError.get()?.let { error ->
                    fileSystemFailureWarning(normalizedDraft.targetPath, error)
                } ?: "已跳过 '${normalizedDraft.targetPath}'，因为它不存在或解析到了项目目录之外。"
                return@forEach
            }
            val target = scopedTarget.path
            if (!scopedTarget.existed) {
                runDraftFileSystemOperation(normalizedDraft.targetPath, skippedFiles, warnings) {
                    target.parent?.let(Files::createDirectories)
                    Files.writeString(target, normalizedDraft.content ?: "")
                    refreshFile(target)
                    writtenFiles += normalizedDraft.targetPath
                }
                return@forEach
            }
            if (normalizedDraft.editOperations.isNotEmpty()) {
                val prepared = runDraftFileSystemOperation(normalizedDraft.targetPath, skippedFiles, warnings) {
                    prepareExistingFileDraft(projectBasePath, normalizedDraft)
                } ?: return@forEach
                warnings += prepared.warnings
                if (!prepared.canApply || !prepared.hasPreparedEdits()) {
                    skippedFiles += normalizedDraft.targetPath
                    return@forEach
                }
                val applyWarnings = runDraftFileSystemOperation(normalizedDraft.targetPath, skippedFiles, warnings) {
                    val existingTarget = pathPolicy.resolveExistingFile(projectBasePath, normalizedDraft.targetPath)?.path
                    if (existingTarget == null) {
                        listOf("已跳过 '${normalizedDraft.targetPath}'，因为它不存在或解析到了项目目录之外。")
                    } else {
                        applyPreparedEdits(existingTarget, prepared)
                    }
                } ?: return@forEach
                warnings += applyWarnings
                if (applyWarnings.isNotEmpty()) {
                    skippedFiles += normalizedDraft.targetPath
                    return@forEach
                }
                refreshFile(target)
                writtenFiles += normalizedDraft.targetPath
                return@forEach
            }
            val contentMatches = if (normalizedDraft.content != null) {
                runDraftFileSystemOperation(normalizedDraft.targetPath, skippedFiles, warnings) {
                    Files.isRegularFile(target) && Files.readString(target) == normalizedDraft.content
                } ?: return@forEach
            } else {
                false
            }
            if (contentMatches) {
                refreshFile(target)
                writtenFiles += normalizedDraft.targetPath
                return@forEach
            }
            skippedFiles += normalizedDraft.targetPath
            warnings += if (normalizedDraft.editScopes.isEmpty()) {
                "已跳过 '${normalizedDraft.targetPath}'，因为 existing-file writeback 缺少 validated scope。"
            } else {
                "已跳过 '${normalizedDraft.targetPath}'，因为 existing-file 写回仅支持局部 patch apply。"
            }
        }

        return GeneratedCodeDraftWriteReport(
            writtenFiles = writtenFiles,
            skippedFiles = skippedFiles,
            warnings = warnings,
        )
    }

    private fun applyPreparedEdits(
        target: Path,
        prepared: PreparedCodeEditBatch,
    ): List<String> {
        val ideProject = project ?: return listOf("scope-safe apply 需要 IDE project 上下文。")
        return computeOnIdeThread {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                ?: return@computeOnIdeThread listOf("无法定位目标文件 '${target.fileName}' 的 IDE VirtualFile。")
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: return@computeOnIdeThread listOf("无法获取目标文件 '${target.fileName}' 的 IDE Document。")
            val warnings = mutableListOf<String>()
            var simulatedText = document.text
            val verifiedEdits = prepared.preparedEdits.mapNotNull { edit ->
                val safeStart = edit.startOffset.coerceIn(0, simulatedText.length)
                val safeEnd = edit.endOffset.coerceIn(safeStart, simulatedText.length)
                val currentSlice = simulatedText.substring(safeStart, safeEnd)
                if (currentSlice != edit.beforeText) {
                    warnings += "操作 '${edit.operationId}' 的 patch 锚点已失效，拒绝写入 '${edit.filePath}'。"
                    null
                } else {
                    simulatedText = simulatedText.replaceRange(safeStart, safeEnd, edit.afterText)
                    VerifiedPreparedCodeEdit(edit, safeStart, safeEnd)
                }
            }
            if (warnings.isNotEmpty()) {
                return@computeOnIdeThread warnings
            }
            WriteCommandAction.runWriteCommandAction(ideProject) {
                verifiedEdits.forEach { verified ->
                    document.replaceString(verified.startOffset, verified.endOffset, verified.edit.afterText)
                }
                PsiDocumentManager.getInstance(ideProject).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            warnings
        }
    }

    private data class VerifiedPreparedCodeEdit(
        val edit: PreparedCodeEdit,
        val startOffset: Int,
        val endOffset: Int,
    )

    private val lastFileSystemError: AtomicReference<Throwable?> = AtomicReference(null)

    private fun <T> runDraftFileSystemOperation(
        targetPath: String,
        skippedFiles: MutableList<String>,
        warnings: MutableList<String>,
        operation: () -> T,
    ): T? {
        return safeFileSystemRead(targetPath, operation).also { result ->
            if (result == null) {
                skippedFiles += targetPath
                warnings += fileSystemFailureWarning(targetPath, lastFileSystemError.get())
            }
        }
    }

    private fun <T> safeFileSystemRead(
        targetPath: String,
        operation: () -> T,
    ): T? {
        lastFileSystemError.set(null)
        return runCatching(operation).getOrElse { error ->
            lastFileSystemError.set(error)
            null
        }
    }

    private fun fileSystemFailureWarning(
        targetPath: String,
        error: Throwable?,
    ): String {
        val detail = error?.message?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName ?: "未知文件系统错误"
        return "已跳过 '$targetPath'，文件系统操作失败：$detail。"
    }

    private fun refreshFile(target: Path) {
        runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        }
    }

    private fun currentFileText(target: Path): String {
        val ideProject = project
        if (ideProject == null) {
            return Files.readString(target)
        }
        return computeOnIdeThread {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            val document = virtualFile?.let(FileDocumentManager.getInstance()::getDocument)
            document?.text ?: Files.readString(target)
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
        check(completed.get()) { "未能在 IDEA 线程中完成 existing-file 草稿预处理" }
        return result.get()
    }
}
