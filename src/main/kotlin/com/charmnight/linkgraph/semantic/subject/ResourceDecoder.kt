package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * 负责把资源文件当前位置解码为资源主题句柄。
 *
 * 不同种类的资源（MyBatis XML、Markdown、YAML 等）有自己的解码器实现。
 * 解码器需要结合 PSI 与编辑器 caret 位置判断"光标处是否是一个资源主题"
 * 并返回对应句柄，让语义分析模块能进入相应流程。
 */
interface ResourceDecoder {
    /**
     * 判断当前解码器是否支持指定文件。
     * 通常按文件扩展名或 PSI 类型做粗筛。
     */
    fun supports(file: PsiFile): Boolean

    /**
     * 判断当前位置是否可以被当前解码器识别为资源主题。
     * 默认实现基于 [decode] 是否非空判断，便于上游做"预览式高亮"。
     */
    fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = decode(file, editor, caretOffset) != null

    /**
     * 将文件与光标位置解码为资源主题句柄。
     *
     * @return 资源主题句柄；当前位置不构成资源主题时返回 null
     */
    fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle?
}
