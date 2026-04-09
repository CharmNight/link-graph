package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * 负责把资源文件当前位置解码为资源主题句柄。
 */
interface ResourceDecoder {
    /**
     * 判断当前解码器是否支持指定文件。
     */
    fun supports(file: PsiFile): Boolean

    /**
     * 判断当前位置是否可以被当前解码器识别为资源主题。
     */
    fun preview(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): Boolean = decode(file, editor, caretOffset) != null

    /**
     * 将文件与光标位置解码为资源主题句柄。
     */
    fun decode(
        file: PsiFile,
        editor: Editor,
        caretOffset: Int,
    ): ResourceSubjectHandle?
}
