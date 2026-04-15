package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope

/**
 * 根据结构化编辑操作定位其唯一允许的编辑范围。
 */
class CodeEditScopeResolver {
    fun resolveScope(
        operation: CodeEditOperation,
        scopes: List<EditScope>,
    ): EditScope? {
        val normalizedPath = operation.filePath.normalizeSeparators()
        return scopes.firstOrNull { scope ->
            scope.scopeId == operation.scopeId && scope.filePath.normalizeSeparators() == normalizedPath
        }
    }

    fun isOperationAllowed(
        operation: CodeEditOperation,
        scope: EditScope,
    ): Boolean {
        if (scope.allowedChangeKinds.isEmpty()) {
            return true
        }
        val allowedKinds = scope.allowedChangeKinds.toSet()
        if (operation.kind.name in allowedKinds) {
            return true
        }
        return operation.kind in setOf(CodeEditOperationKind.REPLACE_METHOD_BLOCK, CodeEditOperationKind.REPLACE_METHOD_BODY) &&
            "REPLACE_SYMBOL_BODY" in allowedKinds
    }

    private fun String.normalizeSeparators(): String = replace('\\', '/')
}
