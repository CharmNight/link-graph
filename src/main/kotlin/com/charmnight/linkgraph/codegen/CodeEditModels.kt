package com.charmnight.linkgraph.codegen

/**
 * 表示一条结构化代码编辑操作。
 */
data class CodeEditOperation(
    /** 操作稳定 ID。 */
    val operationId: String,
    /** 目标文件路径。 */
    val filePath: String,
    /** 关联的编辑范围 ID。 */
    val scopeId: String? = null,
    /** 操作类型。 */
    val kind: CodeEditOperationKind,
    /** 操作负载。 */
    val payload: String,
    /** 操作级警告。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 结构化代码编辑操作类型。
 */
enum class CodeEditOperationKind {
    REPLACE_METHOD_BODY,
    REPLACE_METHOD_BLOCK,
    INSERT_METHOD_AFTER,
    ADD_IMPORT,
    ADD_FIELD,
    CREATE_FILE,
}

data class PreparedCodeEdit(
    val operationId: String,
    val filePath: String,
    val scopeId: String?,
    val kind: CodeEditOperationKind,
    val targetSymbolSignature: String?,
    val startOffset: Int,
    val endOffset: Int,
    val beforeText: String,
    val afterText: String,
    val warnings: List<String> = emptyList(),
)

data class PreparedCodeEditBatch(
    val canApply: Boolean,
    val preparedEdits: List<PreparedCodeEdit> = emptyList(),
    val previewText: String,
    val warnings: List<String> = emptyList(),
) {
    fun hasPreparedEdits(): Boolean = preparedEdits.isNotEmpty()
}

/**
 * 表示一次本地验证的结果。
 */
data class CodeEditValidationResult(
    /** 是否通过本地 apply gate。 */
    val isValid: Boolean,
    /** 检测到的变化符号。 */
    val changedSymbols: Set<String> = emptySet(),
    /** 验证警告。 */
    val warnings: List<String> = emptyList(),
)
