package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

/** 匹配主题标识中的非字母数字字符。 */
private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * 根据命名空间和原始键生成稳定主题标识。
 */
internal fun stableSubjectId(
    namespace: String,
    rawKey: String,
): String {
    // 命名空间和原始键分别规范化后再拼接，避免出现非法字符。
    val normalizedNamespace = namespace.trim().lowercase().replace(NON_ALNUM, "-").trim('-').ifBlank { "subject" }
    val normalizedKey = rawKey.trim().lowercase().replace(NON_ALNUM, "-").trim('-').ifBlank { "unknown" }
    return "$normalizedNamespace:$normalizedKey"
}

/**
 * 获取文件可展示的路径。
 */
internal fun sourcePathOf(file: PsiFile): String = file.virtualFile?.path ?: file.name

/**
 * 获取安全的光标偏移量。
 */
internal fun safeCaretOffset(
    editor: Editor,
    psiFile: PsiFile,
): Int = editor.caretModel.offset.coerceAtMost(psiFile.textLength.coerceAtLeast(1) - 1)

/**
 * 获取偏移量所在行的文本范围。
 */
internal fun lineRange(
    document: Document,
    offset: Int,
): TextRange? {
    if (document.lineCount <= 0) {
        return null
    }
    val safeOffset = offset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1)
    val lineIndex = document.getLineNumber(safeOffset)
    return TextRange(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
}

/**
 * 获取偏移量对应的 1 基行号。
 */
internal fun lineNumberOf(
    document: Document,
    offset: Int,
): Int? {
    if (document.lineCount <= 0) {
        return null
    }
    val safeOffset = offset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1)
    return document.getLineNumber(safeOffset) + 1
}

/**
 * 根据 PSI 文件和文本范围构造源码范围对象。
 */
internal fun sourceRangeOf(
    psiFile: PsiFile,
    range: TextRange,
): SourceRange {
    // 拿不到文档时只能回退到纯偏移量范围。
    val document = psiFile.viewProvider.document
    if (document == null) {
        return SourceRange(
            startOffset = range.startOffset,
            endOffset = range.endOffset,
        )
    }
    return SourceRange(
        startOffset = range.startOffset,
        endOffset = range.endOffset,
        startLine = lineNumberOf(document, range.startOffset),
        endLine = lineNumberOf(document, range.endOffset.coerceAtLeast(range.startOffset)),
    )
}

/**
 * 生成人类可读的方法展示名。
 */
internal fun methodDisplayName(method: PsiMethod): String {
    val ownerName = method.containingClass?.name
        ?: method.containingClass?.qualifiedName
        ?: method.name
    return "$ownerName.${method.name}"
}

/**
 * 生成稳定且可比较的方法签名。
 */
internal fun methodSignature(method: PsiMethod): String {
    val ownerName = method.containingClass?.qualifiedName
        ?: method.containingClass?.name
        ?: method.name
    // 参数类型统一标准化，保证跨 Java/Kotlin 场景也能稳定比较。
    val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
        normalizedTypeText(parameter.type) ?: parameter.type.canonicalText
    }
    val returnType = normalizedTypeText(method.returnType)
        ?: if (method.isConstructor) ownerName else "void"
    return "$ownerName.${method.name}($parameters):$returnType"
}

/**
 * 获取类型的规范化文本。
 */
internal fun canonicalTypeText(type: PsiType?): String {
    return normalizedTypeText(type) ?: "void"
}

/**
 * 把资源锚点格式化为可展示字符串。
 */
internal fun formatResourceAnchor(anchor: ResourceAnchor): String {
    val parameters = anchor.parameterTypeNames?.joinToString(",") ?: ""
    val signature = "${anchor.ownerName}.${anchor.methodName}($parameters)"
    return anchor.returnTypeName?.let { returnType -> "$signature:$returnType" } ?: signature
}

/**
 * 尽量解析出类型的全限定名。
 */
private fun normalizedTypeText(type: PsiType?): String? {
    val psiType = type ?: return null
    val resolvedClass = (psiType as? PsiClassType)?.resolve()
    return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
}

/**
 * 把隐式 `java.lang` 类型补齐为全限定名。
 */
private fun normalizeImplicitJavaLangType(typeText: String): String {
    return when (typeText) {
        "String" -> "java.lang.String"
        "Object" -> "java.lang.Object"
        "Integer" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Boolean" -> "java.lang.Boolean"
        "Double" -> "java.lang.Double"
        "Float" -> "java.lang.Float"
        "Short" -> "java.lang.Short"
        "Byte" -> "java.lang.Byte"
        "Character" -> "java.lang.Character"
        "Void" -> "java.lang.Void"
        else -> typeText
    }
}
