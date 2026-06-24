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

/** 生成稳定的关系 ID：基于关系种类、源端、目标端以及可选限定符。 */
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

/** 基于类源码引用构造证据条目。 */
internal fun JvmClassSymbol.evidence(claim: String): JvmEvidenceRef =
    source.evidence(claim)

/** 基于方法源码引用构造证据条目。 */
internal fun JvmMethodSymbol.evidence(claim: String): JvmEvidenceRef =
    source.evidence(claim)

/** 基于源码引用构造证据条目。 */
internal fun JvmSourceRef?.evidence(claim: String): JvmEvidenceRef =
    JvmEvidenceRef(
        filePath = this?.displayPath,
        virtualFileUrl = this?.virtualFileUrl,
        startLine = this?.startLine,
        endLine = this?.endLine,
        claim = claim,
        decompiled = this?.decompiled ?: false,
    )

/** 基于 PSI 元素构造证据条目（带可选回退源码引用）。 */
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

/** 在上下文中按符号查找对应的 PSI 类。 */
internal fun JvmResolutionContext.findPsiClass(symbol: JvmClassSymbol): PsiClass? =
    cachedPsiClass(symbol.id)

/** 在上下文中按符号查找对应的 PSI 方法。 */
internal fun JvmResolutionContext.findPsiMethod(symbol: JvmMethodSymbol): PsiMethod? =
    cachedPsiMethod(symbol.id)

/** 通过限定名把 PSI 类映射为类符号。 */
internal fun PsiClass.ownerClassSymbol(index: JvmSymbolIndex): JvmClassSymbol? =
    qualifiedName?.let(index.classesByQualifiedName::get)

/** 通过方法签名把 PSI 方法映射为方法符号。 */
internal fun PsiMethod.ownerMethodSymbol(index: JvmSymbolIndex): JvmMethodSymbol? =
    index.methodsBySignature[methodSignature(this)]

/** 按限定名查询类符号。 */
internal fun JvmSymbolIndex.classByQualifiedName(name: String?): JvmClassSymbol? =
    name?.takeIf(String::isNotBlank)?.let(classesByQualifiedName::get)

/** 按 PSI 类型查询类符号。 */
internal fun JvmSymbolIndex.classByType(type: PsiType?): JvmClassSymbol? =
    classByQualifiedName(canonicalTypeText(type))

/** 基于包名就近解析类型对应的类符号（优先精确匹配）。 */
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

/** 向上查找 PSI 元素所属的类符号，用于判定关系归属的源端类层级。 */
internal fun classLevelSourceSymbol(index: JvmSymbolIndex, element: PsiElement): JvmClassSymbol? =
    PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.ownerClassSymbol(index)

/** 向上查找 PSI 元素所属的方法符号，用于将语句或表达式定位到具体方法。 */
internal fun methodLevelSourceSymbol(index: JvmSymbolIndex, element: PsiElement): JvmMethodSymbol? =
    PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.ownerMethodSymbol(index)

/** 取出当前项目中定义的类（排除外部、库和 JDK 类），并按限定名排序以便结果稳定可读。 */
internal fun projectClasses(index: JvmSymbolIndex): List<JvmClassSymbol> =
    index.classesByQualifiedName.values
        .filterNot { symbol -> symbol.external || symbol.library || symbol.jdk }
        .sortedBy(JvmClassSymbol::qualifiedName)

/** 取出当前项目中定义的方法，按签名排序输出，保证跨次解析结果一致。 */
internal fun projectMethods(index: JvmSymbolIndex): List<JvmMethodSymbol> =
    index.methodsBySignature.values.sortedBy(JvmMethodSymbol::signature)

/**
 * 在预算限制下筛选出用于解析方法体内部关系的方法集合。
 * 仅保留允许的来源类中的方法，并截断到预算上限，避免对海量方法做无意义扫描。
 */
internal fun projectMethodsForBodyRelations(
    index: JvmSymbolIndex,
    budget: JvmResolutionBudget,
): List<JvmMethodSymbol> {
    // 配置中允许进行方法体关系解析的类 ID 集合，为空表示不限制。
    val allowedClassIds = budget.methodBodySourceClassIds
    return projectMethods(index)
        .asSequence()
        .filter { method ->
            allowedClassIds.isEmpty() ||
                index.classByQualifiedName(method.ownerClassName)?.id in allowedClassIds
        }
        .take(budget.maxMethodBodiesScanned.coerceAtLeast(0))
        .toList()
}

/** 取出当前项目中定义的字段，按限定名排序输出。 */
internal fun projectFields(index: JvmSymbolIndex): List<JvmFieldSymbol> =
    index.fieldsByQualifiedName.values.sortedBy(JvmFieldSymbol::qualifiedName)

/**
 * 根据任意符号反查其所属的类符号。
 * 类符号返回自身；方法和字段则通过其 owner 类名在索引中查找；其他类型无法定位类时返回 null。
 */
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

/**
 * 工厂方法：组装一条 [JvmRelation]，自动生成稳定 ID 并把证据纳入 samples。
 * qualifier 用于在同一对节点间区分多个不同语义的关系。
 */
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

/** 取得 PSI 类所在 Java 文件的包名；无法判定时退化为限定名前缀或空字符串。 */
internal fun PsiClass.javaFilePackageName(): String =
    (containingFile as? PsiJavaFile)?.packageName ?: qualifiedName?.substringBeforeLast('.', "") ?: ""

/** 当元素是字段或参数时返回其声明类型，其他类型元素返回 null，用于统一类型获取入口。 */
internal fun fieldOrParameterTypeElementType(element: PsiElement): PsiType? =
    when (element) {
        is PsiField -> element.type
        is PsiParameter -> element.type
        else -> null
    }

/** 收集 PSI 类上所有注解的限定名，便于后续按注解做能力或语义判定。 */
internal fun annotationQualifiedNames(psiClass: PsiClass): Set<String> =
    psiClass.annotations.mapNotNull { annotation -> annotation.qualifiedName }.toSet()

/** 判断 PSI 类是否携带任一指定简单名注解，常用于识别 Spring/JSR 等标记型注解。 */
internal fun hasAnyAnnotation(psiClass: PsiClass, simpleNames: Set<String>): Boolean =
    annotationQualifiedNames(psiClass).any { qualifiedName -> qualifiedName.substringAfterLast('.') in simpleNames }

/** 从可能含包名的类名字符串中取出类名部分；若无分隔符则视为不合法，返回空串。 */
internal fun serviceClassNameForPackage(className: String): String =
    className.substringBeforeLast('.', missingDelimiterValue = "")
