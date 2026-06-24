package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 负责把 Markdown 文件当前位置解码为 Markdown 资源主题。
 *
 * Markdown 通常作为设计文档或说明文档存在。本解码器把整个文件作为一个资源主题，
 * 让语义分析模块能把文档内容关联到代码节点（例如"这个类在哪份文档中被提到"）。
 */
class MarkdownSubjectDecoder(
    /** 保存资源锚点解析器。用于从 Markdown 文本中提取方法/类锚点。 */
    private val anchorParser: ResourceAnchorParser = ResourceAnchorParser(),
) : ResourceDecoder {
    /**
     * 判断当前文件是否为 Markdown 文件。
     * 支持 .md 和 .markdown 两种扩展名。
     */
    override fun supports(file: PsiFile): Boolean {
        return when (file.virtualFile?.extension?.lowercase()) {
            "md", "markdown" -> true
            else -> false
        }
    }

    /**
     * Markdown 当前阶段允许整文件预览。
     * 不依赖光标位置——任何光标位置都可触发 Markdown 主题。
     */
    override fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = true

    /**
     * 将 Markdown 文件当前位置解码为资源主题句柄。
     *
     * @param file Markdown 文件 PSI
     * @param editor 当前编辑器
     * @param caretOffset 光标偏移
     * @return Markdown 资源主题句柄
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
            // 锚点解析器从 Markdown 文本中提取代码引用，便于建立文档与代码的关联
            resourceAnchor = anchorParser.parse(file.text),
            attributes = mapOf("path" to path),
        )
    }
}
