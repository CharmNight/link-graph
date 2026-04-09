package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 负责把 Markdown 文件当前位置解码为 Markdown 资源主题。
 */
class MarkdownSubjectDecoder(
    /** 保存资源锚点解析器。 */
    private val anchorParser: ResourceAnchorParser = ResourceAnchorParser(),
) : ResourceDecoder {
    /**
     * 判断当前文件是否为 Markdown 文件。
     */
    override fun supports(file: PsiFile): Boolean {
        return when (file.virtualFile?.extension?.lowercase()) {
            "md", "markdown" -> true
            else -> false
        }
    }

    /**
     * Markdown 当前阶段允许整文件预览。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = true

    /**
     * 将 Markdown 文件当前位置解码为资源主题句柄。
     */
    override fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle? {
        // Markdown 主题默认以文件路径作为稳定标识。
        val path = sourcePathOf(file)
        // 优先使用当前行范围，缺失时回退到整文件范围。
        val range = lineRange(editor.document, caretOffset) ?: TextRange(0, file.textLength.coerceAtLeast(0))
        return ResourceSubjectHandle(
            subjectId = stableSubjectId("resource-markdown", path),
            sourcePath = path,
            sourceRange = sourceRangeOf(file, range),
            displayName = file.name,
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
            resourceAnchor = anchorParser.parse(file.text),
            attributes = mapOf("path" to path),
        )
    }
}
