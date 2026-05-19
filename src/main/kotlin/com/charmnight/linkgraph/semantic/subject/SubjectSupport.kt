package com.charmnight.linkgraph.semantic.subject

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

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
    kotlinMethodDisplayName(method)?.let { return it }
    val ownerName = method.containingClass?.name
        ?: method.containingClass?.qualifiedName
        ?: method.name
    return "$ownerName.${method.name}"
}

/**
 * 生成稳定且可比较的方法签名。
 */
internal fun methodSignature(method: PsiMethod): String {
    kotlinMethodSignature(method)?.let { return it }
    val ownerName = method.containingClass?.qualifiedName
        ?: method.containingClass?.name
        ?: method.name
    // 参数类型统一标准化，保证跨 Java/Kotlin 场景也能稳定比较。
    val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
        runCatching { normalizedTypeText(parameter.type) ?: parameter.type.canonicalText }
            .getOrElse { "unknown" }
    }
    val returnType = runCatching { normalizedTypeText(method.returnType) }.getOrNull()
        ?: if (method.isConstructor) ownerName else "void"
    return "$ownerName.${method.name}($parameters):$returnType"
}

/**
 * 获取类型的规范化文本。
 */
internal fun canonicalTypeText(type: PsiType?): String {
    return runCatching { normalizedTypeText(type) }
        .getOrNull()
        ?: runCatching { type?.canonicalText?.let(::normalizeImplicitJavaLangType) }
            .getOrNull()
        ?: "void"
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
    val resolvedClass = runCatching { (psiType as? PsiClassType)?.resolve() }.getOrNull()
    return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
}

private fun kotlinMethodDisplayName(method: PsiMethod): String? {
    val navigationElement = method.navigationElement
    val ownerSimpleName = when (navigationElement) {
        is KtNamedFunction -> kotlinOwnerSimpleName(navigationElement)
        is KtPropertyAccessor -> kotlinOwnerSimpleName(navigationElement.property)
        is KtProperty -> kotlinOwnerSimpleName(navigationElement)
        is KtPrimaryConstructor -> kotlinOwnerSimpleName(navigationElement)
        is KtSecondaryConstructor -> kotlinOwnerSimpleName(navigationElement)
        else -> null
    } ?: return null
    val methodName = when (navigationElement) {
        is KtPrimaryConstructor -> ownerSimpleName
        is KtSecondaryConstructor -> ownerSimpleName
        else -> method.name
    }
    return "$ownerSimpleName.$methodName"
}

private fun kotlinMethodSignature(method: PsiMethod): String? {
    val navigationElement = method.navigationElement
    val signatureParts = when (navigationElement) {
        is KtNamedFunction -> {
            val ownerName = kotlinOwnerQualifiedName(navigationElement, method) ?: return null
            KotlinSignatureParts(
                ownerName = ownerName,
                methodName = navigationElement.name ?: method.name,
                parameters = navigationElement.valueParameters.map { parameter -> kotlinParameterTypeText(parameter) },
                returnType = kotlinReturnTypeText(navigationElement.typeReference?.text, default = "void"),
            )
        }

        is KtPropertyAccessor -> {
            val ownerName = kotlinOwnerQualifiedName(navigationElement.property, method) ?: return null
            KotlinSignatureParts(
                ownerName = ownerName,
                methodName = method.name,
                parameters = emptyList(),
                returnType = "unknown",
            )
        }

        is KtProperty -> {
            val ownerName = kotlinOwnerQualifiedName(navigationElement, method) ?: return null
            KotlinSignatureParts(
                ownerName = ownerName,
                methodName = method.name,
                parameters = emptyList(),
                returnType = kotlinReturnTypeText(navigationElement.typeReference?.text, default = "unknown"),
            )
        }

        is KtPrimaryConstructor -> kotlinConstructorSignatureParts(method, navigationElement)
        is KtSecondaryConstructor -> kotlinConstructorSignatureParts(method, navigationElement)
        else -> null
    } ?: return null
    return "${signatureParts.ownerName}.${signatureParts.methodName}(${signatureParts.parameters.joinToString(",")}):${signatureParts.returnType}"
}

private fun kotlinConstructorSignatureParts(
    method: PsiMethod,
    constructor: org.jetbrains.kotlin.psi.KtConstructor<*>,
): KotlinSignatureParts? {
    val ownerClass = PsiTreeUtil.getParentOfType(constructor, KtClass::class.java, false)
        ?: return null
    val ownerName = kotlinQualifiedClassName(ownerClass)
        ?: method.containingClass?.qualifiedName
        ?: return null
    val methodName = ownerClass.name ?: method.name
    return KotlinSignatureParts(
        ownerName = ownerName,
        methodName = methodName,
        parameters = constructor.valueParameters.map { parameter -> kotlinParameterTypeText(parameter) },
        returnType = ownerName,
    )
}

private data class KotlinSignatureParts(
    val ownerName: String,
    val methodName: String,
    val parameters: List<String>,
    val returnType: String,
)

private fun kotlinOwnerSimpleName(element: com.intellij.psi.PsiElement): String? =
    PsiTreeUtil.getParentOfType(element, KtClass::class.java, false)?.name

private fun kotlinOwnerQualifiedName(
    element: com.intellij.psi.PsiElement,
    method: PsiMethod,
): String? =
    PsiTreeUtil.getParentOfType(element, KtClass::class.java, false)
        ?.let(::kotlinQualifiedClassName)
        ?: method.containingClass?.qualifiedName
        ?: method.containingClass?.name

private fun kotlinQualifiedClassName(klass: KtClass): String? {
    val classNames = generateSequence(klass) { current: KtClass ->
        PsiTreeUtil.getParentOfType(current.parent, KtClass::class.java, false)
    }
        .mapNotNull { current -> current.name }
        .toList()
        .asReversed()
    val className = classNames.joinToString(".").takeIf { it.isNotBlank() } ?: return null
    val packageName = (klass.containingFile as? KtFile)
        ?.packageFqName
        ?.asString()
        ?.takeIf { it.isNotBlank() }
    return listOfNotNull(packageName, className).joinToString(".")
}

private fun kotlinParameterTypeText(parameter: KtParameter): String =
    kotlinTypeText(parameter.typeReference?.text, default = "unknown")

private fun kotlinReturnTypeText(typeText: String?, default: String): String =
    kotlinTypeText(typeText, default = default).let { normalized ->
        if (normalized == "kotlin.Unit") "void" else normalized
    }

private fun kotlinTypeText(typeText: String?, default: String): String {
    val normalized = typeText
        ?.trim()
        ?.removeSuffix("?")
        ?.replace(Regex("\\s+"), "")
        ?.takeIf { it.isNotBlank() }
        ?: return default
    return when (normalized) {
        "String" -> "kotlin.String"
        "Boolean" -> "kotlin.Boolean"
        "Int" -> "kotlin.Int"
        "Long" -> "kotlin.Long"
        "Double" -> "kotlin.Double"
        "Float" -> "kotlin.Float"
        "Short" -> "kotlin.Short"
        "Byte" -> "kotlin.Byte"
        "Char" -> "kotlin.Char"
        "Unit" -> "kotlin.Unit"
        "Any" -> "kotlin.Any"
        else -> normalized
    }
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
