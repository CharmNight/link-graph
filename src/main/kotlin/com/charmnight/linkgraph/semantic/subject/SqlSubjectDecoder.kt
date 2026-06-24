package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 负责把 SQL 文件中的当前位置解码为 SQL 资源主题。
 *
 * SQL 文件作为独立资源存在（与 MyBatis XML 中的 SQL 不同）。
 * 本解码器把光标所在位置映射为 SQL_FILE 类型的资源主题句柄，
 * 让 SQL 也进入语义分析流程。
 */
class SqlSubjectDecoder : ResourceDecoder {
    /**
     * 判断当前文件是否为 SQL 文件。
     * 仅按扩展名做粗筛，不读文件内容。
     */
    override fun supports(file: PsiFile): Boolean = file.virtualFile?.extension?.lowercase() == "sql"

    /**
     * SQL 文件默认允许整文件预览。
     * 不依赖光标位置——SQL 文件级资源对任何光标位置都可触发。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = true

    /**
     * 把当前光标所在 SQL 文件位置转换为资源主题句柄。
     *
     * @param file SQL 文件 PSI
     * @param editor 当前编辑器
     * @param caretOffset 光标偏移
     * @return SQL 资源主题句柄
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
            // 把路径放到 attributes 里，方便 UI 展示
            attributes = mapOf("path" to path),
        )
    }
}
