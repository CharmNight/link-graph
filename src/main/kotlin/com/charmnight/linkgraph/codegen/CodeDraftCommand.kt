package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.agent.model.EditScope

/**
 * 已消除字段歧义的代码草稿命令。
 *
 * 新文件只能携带完整内容，现有文件只能携带局部操作与本地授权范围；应用层不再同时保存
 * `content` 和 `editOperations` 并猜测优先级。
 */
sealed interface CodeDraftCommand {
    val targetPath: String

    data class CreateFile(
        override val targetPath: String,
        val content: String,
    ) : CodeDraftCommand

    data class PatchExistingFile(
        override val targetPath: String,
        val operations: List<CodeEditOperation>,
        val scopes: List<EditScope>,
    ) : CodeDraftCommand {
        init {
            require(operations.none { operation -> operation.kind == CodeEditOperationKind.CREATE_FILE }) {
                "PatchExistingFile cannot contain CREATE_FILE operations."
            }
            require(operations.all { operation -> sameCommandPath(operation.filePath, targetPath) }) {
                "Every patch operation filePath must match command targetPath."
            }
        }
    }
}

private fun sameCommandPath(left: String, right: String): Boolean =
    left.trim().replace('\\', '/') == right.trim().replace('\\', '/')

/** 返回新文件草稿内容；局部 patch 命令返回 null。 */
internal fun CodeDraftCommand.createFileContentOrNull(): String? =
    (this as? CodeDraftCommand.CreateFile)?.content

/** 返回现有文件 patch 命令；新文件命令返回 null。 */
internal fun CodeDraftCommand.patchOrNull(): CodeDraftCommand.PatchExistingFile? =
    this as? CodeDraftCommand.PatchExistingFile
