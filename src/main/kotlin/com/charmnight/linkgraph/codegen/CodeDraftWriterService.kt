package com.charmnight.linkgraph.codegen

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
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
        val basePath = Path.of(projectBasePath).normalize()
        val target = basePath.resolve(normalizedDraft.targetPath).normalize()
        if (!target.startsWith(basePath)) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = normalizedDraft.content.orEmpty(),
                warnings = listOf("已跳过 '${normalizedDraft.targetPath}'，因为它解析到了项目目录之外。"),
            )
        }
        if (!Files.exists(target)) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = normalizedDraft.content.orEmpty(),
                warnings = listOf("目标文件 '${normalizedDraft.targetPath}' 不存在，无法准备 existing-file patch。"),
            )
        }
        val beforeText = currentFileText(target)
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

        val basePath = Path.of(projectBasePath).normalize()
        val writtenFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        drafts.forEach { draft ->
            val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
            val target = basePath.resolve(normalizedDraft.targetPath).normalize()
            if (!target.startsWith(basePath)) {
                skippedFiles += normalizedDraft.targetPath
                warnings += "已跳过 '${normalizedDraft.targetPath}'，因为它解析到了项目目录之外。"
                return@forEach
            }
            if (!Files.exists(target)) {
                target.parent?.let(Files::createDirectories)
                Files.writeString(target, normalizedDraft.content ?: "")
                refreshFile(target)
                writtenFiles += normalizedDraft.targetPath
                return@forEach
            }
            if (normalizedDraft.editOperations.isNotEmpty()) {
                val prepared = prepareExistingFileDraft(projectBasePath, normalizedDraft)
                warnings += prepared.warnings
                if (!prepared.canApply || !prepared.hasPreparedEdits()) {
                    skippedFiles += normalizedDraft.targetPath
                    return@forEach
                }
                val applyWarnings = applyPreparedEdits(target, prepared)
                warnings += applyWarnings
                if (applyWarnings.isNotEmpty()) {
                    skippedFiles += normalizedDraft.targetPath
                    return@forEach
                }
                refreshFile(target)
                writtenFiles += normalizedDraft.targetPath
                return@forEach
            }
            if (normalizedDraft.content != null && Files.isRegularFile(target) && Files.readString(target) == normalizedDraft.content) {
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
            WriteCommandAction.runWriteCommandAction(ideProject) {
                prepared.preparedEdits.forEach { edit ->
                    val safeStart = edit.startOffset.coerceIn(0, document.textLength)
                    val safeEnd = edit.endOffset.coerceIn(safeStart, document.textLength)
                    val currentSlice = document.charsSequence.subSequence(safeStart, safeEnd).toString()
                    if (currentSlice != edit.beforeText) {
                        warnings += "操作 '${edit.operationId}' 的 patch 锚点已失效，拒绝写入 '${edit.filePath}'。"
                        return@runWriteCommandAction
                    }
                    document.replaceString(safeStart, safeEnd, edit.afterText)
                }
                PsiDocumentManager.getInstance(ideProject).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            warnings
        }
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
        check(completed.get()) { "未能在 IDEA 线程中完成 existing-file 草稿预处理" }
        return result.get()
    }
}
