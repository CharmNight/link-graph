package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.canonicalTypeText
import com.charmnight.linkgraph.jvm.index.methodSignature
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

internal fun jvmRelationId(
    kind: JvmRelationKind,
    fromSymbolId: String,
    toSymbolId: String,
    qualifier: String? = null,
): String {
    val raw = buildString {
        append(kind.name.lowercase())
        append(':')
        append(fromSymbolId)
        append("->")
        append(toSymbolId)
        qualifier?.takeIf(String::isNotBlank)?.let { value ->
            append(':')
            append(value)
        }
    }
    return raw.lowercase().replace(Regex("[^a-z0-9:_>\\-]+"), "-").trim('-')
}

internal fun JvmClassSymbol.evidence(claim: String): JvmEvidenceRef =
    source.evidence(claim)

internal fun JvmMethodSymbol.evidence(claim: String): JvmEvidenceRef =
    source.evidence(claim)

internal fun JvmSourceRef?.evidence(claim: String): JvmEvidenceRef =
    JvmEvidenceRef(
        filePath = this?.displayPath,
        virtualFileUrl = this?.virtualFileUrl,
        startLine = this?.startLine,
        endLine = this?.endLine,
        claim = claim,
        decompiled = this?.decompiled ?: false,
    )

internal fun PsiElement.evidence(claim: String, fallback: JvmSourceRef? = null): JvmEvidenceRef {
    val file = containingFile?.virtualFile
    val document = file?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it) }
    val range = textRange
    val startLine = range
        ?.let { textRange -> document?.getLineNumber(textRange.startOffset)?.plus(1) }
        ?: fallback?.startLine
    val endLine = range
        ?.let { textRange ->
            document?.getLineNumber(textRange.endOffset.coerceAtLeast(textRange.startOffset))?.plus(1)
        }
        ?: fallback?.endLine
    return JvmEvidenceRef(
        filePath = fallback?.displayPath ?: file?.path,
        virtualFileUrl = fallback?.virtualFileUrl ?: file?.url,
        startLine = startLine,
        endLine = endLine,
        claim = claim,
        decompiled = fallback?.decompiled ?: false,
    )
}

internal fun JvmResolutionContext.findPsiClass(symbol: JvmClassSymbol): PsiClass? =
    cachedPsiClass(symbol.id)

internal fun JvmResolutionContext.findPsiMethod(symbol: JvmMethodSymbol): PsiMethod? =
    cachedPsiMethod(symbol.id)

internal fun PsiClass.ownerClassSymbol(index: JvmSymbolIndex): JvmClassSymbol? =
    qualifiedName?.let(index.classesByQualifiedName::get)

internal fun PsiMethod.ownerMethodSymbol(index: JvmSymbolIndex): JvmMethodSymbol? =
    index.methodsBySignature[methodSignature(this)]

internal fun JvmSymbolIndex.classByQualifiedName(name: String?): JvmClassSymbol? =
    name?.takeIf(String::isNotBlank)?.let(classesByQualifiedName::get)

internal fun JvmSymbolIndex.classByType(type: PsiType?): JvmClassSymbol? =
    classByQualifiedName(canonicalTypeText(type))

internal fun JvmSymbolIndex.classByTypeNear(
    type: PsiType?,
    ownerPackageName: String?,
): JvmClassSymbol? {
    classByType(type)?.let { return it }
    val raw = type?.canonicalText
        ?.substringBefore('<')
        ?.removeSuffix("[]")
        ?.takeIf(String::isNotBlank)
        ?: return null
    classByQualifiedName(raw)?.let { return it }
    val simpleName = raw.substringAfterLast('.')
    return ownerPackageName
        ?.takeIf(String::isNotBlank)
        ?.let { packageName -> classByQualifiedName("$packageName.$simpleName") }
}

internal fun classLevelSourceSymbol(index: JvmSymbolIndex, element: PsiElement): JvmClassSymbol? =
    PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.ownerClassSymbol(index)

internal fun methodLevelSourceSymbol(index: JvmSymbolIndex, element: PsiElement): JvmMethodSymbol? =
    PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.ownerMethodSymbol(index)

internal fun projectClasses(index: JvmSymbolIndex): List<JvmClassSymbol> =
    index.classesByQualifiedName.values
        .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
        .sortedBy(JvmClassSymbol::qualifiedName)

internal fun projectMethods(index: JvmSymbolIndex): List<JvmMethodSymbol> =
    index.methodsBySignature.values.sortedBy(JvmMethodSymbol::signature)

internal fun projectFields(index: JvmSymbolIndex): List<JvmFieldSymbol> =
    index.fieldsByQualifiedName.values.sortedBy(JvmFieldSymbol::qualifiedName)

internal fun ownerClassSymbol(
    index: JvmSymbolIndex,
    symbol: JvmSymbol,
): JvmClassSymbol? =
    when (symbol) {
        is JvmClassSymbol -> symbol
        is JvmMethodSymbol -> index.classByQualifiedName(symbol.ownerClassName)
        is JvmFieldSymbol -> index.classByQualifiedName(symbol.ownerClassName)
        else -> null
    }

internal fun relation(
    kind: JvmRelationKind,
    from: JvmSymbol,
    to: JvmSymbol,
    confidence: JvmRelationConfidence,
    source: JvmRelationSource,
    evidence: JvmEvidenceRef,
    qualifier: String? = null,
    count: Int = 1,
    metadata: Map<String, String> = emptyMap(),
): JvmRelation =
    JvmRelation(
        id = jvmRelationId(kind, from.id, to.id, qualifier),
        kind = kind,
        fromSymbolId = from.id,
        toSymbolId = to.id,
        confidence = confidence,
        source = source,
        count = count,
        samples = listOf(evidence),
        metadata = metadata,
    )

internal fun PsiClass.javaFilePackageName(): String =
    (containingFile as? PsiJavaFile)?.packageName ?: qualifiedName?.substringBeforeLast('.', "") ?: ""

internal fun fieldOrParameterTypeElementType(element: PsiElement): PsiType? =
    when (element) {
        is PsiField -> element.type
        is PsiParameter -> element.type
        else -> null
    }

internal fun annotationQualifiedNames(psiClass: PsiClass): Set<String> =
    psiClass.annotations.mapNotNull { annotation -> annotation.qualifiedName }.toSet()

internal fun hasAnyAnnotation(psiClass: PsiClass, simpleNames: Set<String>): Boolean =
    annotationQualifiedNames(psiClass).any { qualifiedName -> qualifiedName.substringAfterLast('.') in simpleNames }

internal fun serviceClassNameForPackage(className: String): String =
    className.substringBeforeLast('.', missingDelimiterValue = "")
