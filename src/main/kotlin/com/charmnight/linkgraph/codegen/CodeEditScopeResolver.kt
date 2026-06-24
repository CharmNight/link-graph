package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope

/**
 * 根据结构化编辑操作定位其唯一允许的编辑范围。
 *
 * 代码生成阶段会把多个编辑操作派发给底层执行器；每个操作都必须落在预先声明的 edit scope 内，
 * 否则视为越权编辑会被拒绝。本类负责：
 * - 按 scopeId 与文件路径匹配对应的 EditScope；
 * - 校验操作种类是否在该 scope 允许的范围内。
 */
class CodeEditScopeResolver {
    /**
     * 在候选 scope 列表中找到与操作匹配的那一个。
     *
     * @param operation 待定位的编辑操作
     * @param scopes 候选 scope 列表
     * @param projectBasePath 项目根路径；用于路径解析（相对/绝对路径互转）
     * @return 匹配的 scope；找不到返回 null
     */
    fun resolveScope(
        operation: CodeEditOperation,
        scopes: List<EditScope>,
        projectBasePath: String? = null,
    ): EditScope? {
        return scopes.firstOrNull { scope ->
            // scopeId 必须严格相等；文件路径允许相对/绝对表示，做归一化对比
            scope.scopeId == operation.scopeId &&
                pathsReferToSameFile(scope.filePath, operation.filePath, projectBasePath)
        }
    }

    /**
     * 判断操作是否被该 scope 允许。
     *
     * @param operation 待校验的操作
     * @param scope 目标 scope
     * @return true 表示允许执行
     */
    fun isOperationAllowed(
        operation: CodeEditOperation,
        scope: EditScope,
    ): Boolean {
        // scope 没有声明 allowedChangeKinds 时视为完全放行
        if (scope.allowedChangeKinds.isEmpty()) {
            return true
        }
        val allowedKinds = scope.allowedChangeKinds.toSet()
        // 操作种类名直接命中
        if (operation.kind.name in allowedKinds) {
            return true
        }
        // 兼容：把"替换方法块/方法体"映射到 REPLACE_SYMBOL_BODY
        return operation.kind in setOf(CodeEditOperationKind.REPLACE_METHOD_BLOCK, CodeEditOperationKind.REPLACE_METHOD_BODY) &&
            "REPLACE_SYMBOL_BODY" in allowedKinds
    }

    /** 把路径中的反斜杠统一为正斜杠，便于跨平台比较。 */
    private fun String.normalizeSeparators(): String = replace('\\', '/')

    /**
     * 判断两个路径是否指向同一文件。
     * 优先用项目根路径做归一化对比；无法归一化时退化为纯字符串对比。
     */
    private fun pathsReferToSameFile(
        left: String,
        right: String,
        projectBasePath: String?,
    ): Boolean {
        val leftResolved = ProjectPathNormalizer.resolvePath(left, projectBasePath)
        val rightResolved = ProjectPathNormalizer.resolvePath(right, projectBasePath)
        // 两侧都能归一化时直接比较归一化结果
        if (leftResolved != null && rightResolved != null) {
            return leftResolved == rightResolved
        }
        // 兜底：分隔符归一化后做字符串比较
        return left.normalizeSeparators() == right.normalizeSeparators()
    }
}
