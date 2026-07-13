package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.foundation.utf8ByteLengthAtMost
import com.charmnight.linkgraph.source.SourceArchiveReadLimits

/** 代码草稿内容的统一大小限制，避免远程模型输出被无界写入或放入 diff 视图。 */
internal object CodeDraftContentLimits {
    const val MAX_DRAFT_CONTENT_BYTES: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES
    const val MAX_DRAFTS_PER_RESPONSE: Int = 64
    const val MAX_EDIT_OPERATIONS_PER_DRAFT: Int = 64

    fun isWithinTextLimit(text: String?): Boolean =
        text == null || utf8ByteLengthAtMost(text, MAX_DRAFT_CONTENT_BYTES)

    fun isWithinCommandLimit(command: CodeDraftCommand): Boolean =
        when (command) {
            is CodeDraftCommand.CreateFile -> isWithinTextLimit(command.content)
            is CodeDraftCommand.PatchExistingFile -> command.operations.all { operation ->
                isWithinTextLimit(operation.payload)
            }
        }

    fun oversizedContentWarning(targetPath: String): String =
        "已跳过 '$targetPath'，因为代码草稿内容过大，最大允许 $MAX_DRAFT_CONTENT_BYTES bytes。"
}
