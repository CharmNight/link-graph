package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 负责把 SQL 文件中的当前位置解码为 SQL 资源主题。
 */
class SqlSubjectDecoder : ResourceDecoder {
    /**
     * 判断当前文件是否为 SQL 文件。
     */
    override fun supports(file: PsiFile): Boolean = file.virtualFile?.extension?.lowercase() == "sql"

    /**
     * SQL 文件默认允许整文件预览。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = true

    /**
     * 把当前光标所在 SQL 文件位置转换为资源主题句柄。
     */
    override fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle? {
        // SQL 主题以文件路径作为稳定标识基础。
        val path = sourcePathOf(file)
        // 优先取当前行范围，高亮范围缺失时回退到整文件范围。
        val range = lineRange(editor.document, caretOffset) ?: TextRange(0, file.textLength.coerceAtLeast(0))
        return ResourceSubjectHandle(
            subjectId = stableSubjectId("resource-sql", path),
            sourcePath = path,
            sourceRange = sourceRangeOf(file, range),
            displayName = file.name,
            kind = ResourceSubjectKind.SQL_FILE,
            attributes = mapOf("path" to path),
        )
    }
}
