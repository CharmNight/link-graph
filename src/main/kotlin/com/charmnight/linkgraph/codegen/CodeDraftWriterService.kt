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

/**
 * 代码草稿写入服务。
 *
 * 负责把 LLM 生成的代码草稿落到磁盘：新建文件直接写入，已存在文件则尝试以局部 patch
 * 的方式应用。所有文件路径都要经过 [ProjectScopedPathPolicy] 校验，避免越界写到项目目录之外。
 */
class CodeDraftWriterService(
    /** 当前 IDE 项目实例；为空时只能走纯文件系统读写，无法进行 scope-safe 的 PSI 写入。 */
    private val project: Project? = null,
    /** 项目根路径解析策略，负责把草稿中的相对路径映射为安全的绝对路径。 */
    private val pathPolicy: ProjectScopedPathPolicy = ProjectScopedPathPolicy(),
) {
    /** 用于在 IDE 上下文中执行基于 PSI 的局部 patch 准备工作；没有 project 时为空。 */
    private val codeEditApplyService = project?.let { CodeEditApplyService(it) }

    /**
     * 为已存在文件的草稿准备可应用的局部 patch。
     *
     * 会读取目标文件当前内容，校验路径是否落在项目目录内，再交给 [CodeEditApplyService]
     * 计算偏移量。任何前置条件不满足都会返回 [PreparedCodeEditBatch.canApply] 为 false 的结果。
     */
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

    /**
     * 批量写入草稿到磁盘，返回写入报告。
     *
     * 对每个草稿：路径解析失败或越界的直接跳过；新文件直接创建并写入；已存在文件根据是否
     * 携带局部 patch 操作走不同分支，没有 validated scope 时会拒绝整体覆写以避免误改。
     */
    fun writeDrafts(
        projectBasePath: String?,
        drafts: List<GeneratedCodeDraft>,
    ): GeneratedCodeDraftWriteReport {
        if (projectBasePath.isNullOrBlank()) {
            return GeneratedCodeDraftWriteReport(
                warnings = listOf("项目根路径不可用，无法写入草稿。"),
            )
        }

        val writtenFiles = mutableListOf<String>() // 成功写入的草稿相对路径集合
        val skippedFiles = mutableListOf<String>() // 因校验或写回失败被跳过的草稿路径集合
        val warnings = mutableListOf<String>() // 写入过程中产生的告警信息

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

    /**
     * 把已经准备好的局部 patch 真正写到 IDE Document 上。
     *
     * 在写入前会再次校验每段 patch 的锚点文本是否仍与当前文件内容匹配；任一段失效就放弃整批写入。
     * 通过 [WriteCommandAction] 在 IDE 写命令中执行替换并提交、保存 Document。
     */
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
            val warnings = mutableListOf<String>() // 写入过程中收集到的告警
            var simulatedText = document.text // 模拟逐段 patch 应用后的文本，用于校验后续锚点
            val verifiedEdits = prepared.preparedEdits.mapNotNull { edit ->
                val safeStart = edit.startOffset.coerceIn(0, simulatedText.length) // 越界保护后的起始偏移
                val safeEnd = edit.endOffset.coerceIn(safeStart, simulatedText.length) // 越界保护后的结束偏移
                val currentSlice = simulatedText.substring(safeStart, safeEnd) // 当前文件中该区间的真实文本
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

    /** 通过锚点校验、可安全应用的局部编辑快照。 */
    private data class VerifiedPreparedCodeEdit(
        val edit: PreparedCodeEdit,
        val startOffset: Int,
        val endOffset: Int,
    )

    /** 最近一次文件系统操作抛出的异常，用于在告警信息中提示原因。 */
    private val lastFileSystemError: AtomicReference<Throwable?> = AtomicReference(null)

    /**
     * 包裹一段文件系统/草稿操作：成功返回结果；失败则把目标路径加入跳过列表并附上告警。
     */
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

    /**
     * 安全执行一段文件系统读操作：捕获异常并记录到 [lastFileSystemError]，失败时返回 null。
     */
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

    /** 把异常格式化成给用户看的告警文案；优先使用具体异常信息，否则回退到异常类名。 */
    private fun fileSystemFailureWarning(
        targetPath: String,
        error: Throwable?,
    ): String {
        val detail = error?.message?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName ?: "未知文件系统错误"
        return "已跳过 '$targetPath'，文件系统操作失败：$detail。"
    }

    /** 让 IDE 重新发现并刷新某个 NIO 文件，使后续读取看到最新内容；失败时静默忽略。 */
    private fun refreshFile(target: Path) {
        runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        }
    }

    /**
     * 读取目标文件的当前文本内容。
     *
     * 有 IDE 上下文时优先走 EDT 上的 VirtualFile/Document，确保拿到的是 IDE 视角下的最新内容；
     * 否则直接用 NIO 读取磁盘内容。
     */
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

    /**
     * 在 IDE 的事件分发线程上同步执行一段动作。
     *
     * 用于满足 PSI/Document API 的线程约束：若当前已在 EDT 则直接执行，
     * 否则通过 invokeAndWait 阻塞当前线程等待结果，并原样抛出动作中的异常。
     */
    private fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }
        val completed = AtomicBoolean(false) // 标记动作是否成功完成
        val result = AtomicReference<T>() // 暂存动作的返回值
        val error = AtomicReference<Throwable?>() // 暂存动作抛出的异常，待回到原线程重新抛出
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
