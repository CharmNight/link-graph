package com.charmnight.linkgraph.codegen

/**
 * 表示一条结构化代码编辑操作。
 *
 * 代码生成阶段产出的编辑以本结构描述，应用阶段再翻译为具体文件改动。
 * 这种"操作描述 → 应用"的两阶段让生成与应用解耦，便于预览、校验、撤销。
 */
data class CodeEditOperation(
    /** 操作稳定 ID；用于跨阶段引用同一个操作。 */
    val operationId: String,
    /** 目标文件路径。 */
    val filePath: String,
    /** 关联的编辑范围 ID；为空表示无明确范围。 */
    val scopeId: String? = null,
    /** 操作类型。 */
    val kind: CodeEditOperationKind,
    /** 操作负载。具体内容由 [kind] 决定（例如新方法文本、新 import 行等）。 */
    val payload: String,
    /** 操作级警告。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 结构化代码编辑操作类型。
 *
 * 覆盖方法体替换、方法块替换、方法后插入、新增 import、新增字段、创建新文件等场景。
 */
enum class CodeEditOperationKind {
    /** 替换方法体（保留方法签名）。 */
    REPLACE_METHOD_BODY,
    /** 替换整个方法块（含签名）。 */
    REPLACE_METHOD_BLOCK,
    /** 在某方法后插入新方法。 */
    INSERT_METHOD_AFTER,
    /** 新增 import。 */
    ADD_IMPORT,
    /** 新增字段。 */
    ADD_FIELD,
    /** 创建新文件。 */
    CREATE_FILE,
}

/**
 * 已经"准备好"的代码编辑：定位到具体的文件偏移区间与替换文本。
 *
 * 应用阶段直接消费本对象做替换，不需要再做定位计算。
 */
data class PreparedCodeEdit(
    val operationId: String,
    val filePath: String,
    val scopeId: String?,
    val kind: CodeEditOperationKind,
    /** 目标符号签名（用于事后引用）。 */
    val targetSymbolSignature: String?,
    /** 起始偏移。 */
    val startOffset: Int,
    /** 结束偏移。 */
    val endOffset: Int,
    /** 替换前的文本（用于校验）。 */
    val beforeText: String,
    /** 替换后的文本。 */
    val afterText: String,
    val warnings: List<String> = emptyList(),
)

/**
 * 一批已准备好的编辑。
 *
 * @property canApply 整批是否可应用；任一条目不可应用则整批拒绝
 * @property preparedEdits 已准备好的编辑列表
 * @property previewText 整批预览文本（人类可读）
 * @property warnings 整批警告
 */
data class PreparedCodeEditBatch(
    val canApply: Boolean,
    val preparedEdits: List<PreparedCodeEdit> = emptyList(),
    val previewText: String,
    val warnings: List<String> = emptyList(),
) {
    /** 是否有已准备好的编辑。 */
    fun hasPreparedEdits(): Boolean = preparedEdits.isNotEmpty()
}

/**
 * 表示一次本地验证的结果。
 *
 * 在应用编辑前做本地静态验证，判断编辑是否会破坏现有符号、是否触发警告等。
 */
data class CodeEditValidationResult(
    /** 是否通过本地 apply gate。 */
    val isValid: Boolean,
    /** 检测到的变化符号。 */
    val changedSymbols: Set<String> = emptySet(),
    /** 验证警告。 */
    val warnings: List<String> = emptyList(),
)
