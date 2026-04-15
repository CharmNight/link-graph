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

/**
 * 表示一次结构化应用的结果。
 */
data class CodeEditApplyResult(
    /** 是否成功得到可落盘的新文本。 */
    val applied: Boolean,
    /** 更新后的文件文本。 */
    val updatedText: String,
    /** 过程警告。 */
    val warnings: List<String> = emptyList(),
)

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
