package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmImplementationSignatureResolver
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.NoopJvmImplementationSignatureResolver
import com.charmnight.linkgraph.jvm.index.JvmOverrideShapeMatcher
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.openapi.diagnostic.Logger

/**
 * 调用展开目标解析器。
 *
 * 根据传入的方法签名和图索引，判断调用点应当如何展开：
 * 当目标方法在项目源码中时进一步判断抽象方法是否需要解析到具体实现，
 * 当目标在 JDK 或外部库中时给出对应的外部来源标记。
 */
class InvocationExpansionTargetResolver {
    private val logger = Logger.getInstance(InvocationExpansionTargetResolver::class.java)

    /**
     * 解析调用签名对应的展开目标。
     *
     * 流程为：先按完整签名查找方法，找不到时退化为按类名查找；
     * 如果方法属于非项目源码（JDK、外部库），返回外部来源结果；
     * 如果属于项目源码且非抽象，直接返回；
     * 抽象方法则尝试解析实现，并按实现数量返回单实现、多实现或无实现结果。
     */
    fun resolve(
        invocationSignature: String,
        index: ArchitectureGraphIndex,
        implementationResolver: JvmImplementationSignatureResolver = NoopJvmImplementationSignatureResolver,
    ): InvocationExpansionTarget {
        val signature = invocationSignature.trim()
        if (signature.isBlank()) {
            return InvocationExpansionTarget(InvocationExpansionTargetKind.NOT_FOUND)
        }
        val method = findMethod(signature, index)
        if (method == null) {
            val ownerClass = ownerClassName(signature)?.let(index::findClass)
                ?: return InvocationExpansionTarget(InvocationExpansionTargetKind.NOT_FOUND)
            return targetForClassOrigin(ownerClass, signature)
        }
        val ownerClass = index.findClass(method.ownerClassName)
            ?: return InvocationExpansionTarget(InvocationExpansionTargetKind.NOT_FOUND)
        if (ownerClass.origin != SourceOrigin.PROJECT_SOURCE) {
            return targetForClassOrigin(ownerClass, method.signature)
        }
        if (!requiresImplementationResolution(ownerClass, method)) {
            return InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.PROJECT_SOURCE,
                signature = method.signature,
            )
        }
        val indexedImplementationSignatures = implementationMethods(method, ownerClass, index)
            .map(JvmMethodSymbol::signature)
        val fallbackImplementationSignatures = if (indexedImplementationSignatures.isEmpty()) {
            implementationResolver.implementationSignatures(method, ownerClass)
        } else {
            emptyList()
        }
        val implementationSignatures = indexedImplementationSignatures
            .ifEmpty { fallbackImplementationSignatures }
            .sorted()
        return when (implementationSignatures.size) {
            0 -> {
                logNoImplementationDiagnostic(
                    signature = signature,
                    method = method,
                    ownerClass = ownerClass,
                    index = index,
                    implementationResolver = implementationResolver,
                )
                InvocationExpansionTarget(InvocationExpansionTargetKind.NO_IMPLEMENTATION)
            }
            1 -> InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.PROJECT_SOURCE,
                signature = implementationSignatures.single(),
            )
            else -> InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
                candidateSignatures = implementationSignatures,
            )
        }
    }

    /**
     * 根据所属类的来源信息返回对应的展开目标。
     *
     * JDK 来源或 JDK 类标记为外部 JDK；其它非项目源码或外部库标记为外部库；
     * 项目源码时附带方法签名，外部来源时签名留空。
     */
    private fun targetForClassOrigin(
        ownerClass: JvmClassSymbol,
        signature: String,
    ): InvocationExpansionTarget {
        val kind = when {
            ownerClass.origin == SourceOrigin.JDK_SOURCE || ownerClass.origin == SourceOrigin.JDK_CLASS || ownerClass.jdk ->
                InvocationExpansionTargetKind.EXTERNAL_JDK
            ownerClass.origin != SourceOrigin.PROJECT_SOURCE || ownerClass.external || ownerClass.library ->
                InvocationExpansionTargetKind.EXTERNAL_LIBRARY
            else -> InvocationExpansionTargetKind.PROJECT_SOURCE
        }
        return InvocationExpansionTarget(kind = kind, signature = signature.takeIf { kind == InvocationExpansionTargetKind.PROJECT_SOURCE })
    }

    /**
     * 判断是否需要进一步解析具体实现。
     *
     * 当所属类是接口、类本身是抽象的，或方法本身是抽象的，
     * 都意味着当前签名指向的是一个声明，需要找到真正的实现方法。
     */
    private fun requiresImplementationResolution(
        ownerClass: JvmClassSymbol,
        method: JvmMethodSymbol,
    ): Boolean =
        ownerClass.kind == JvmClassKind.INTERFACE ||
            ownerClass.abstract ||
            method.abstract

    /**
     * 在所有实现类中查找同名同参的具体实现方法。
     *
     * 先收集目标类（接口或抽象类）的全部实现/继承类标识，
     * 再在这些项目源码的实现类中按 [JvmOverrideShapeMatcher.matchesOverride] 匹配，
     * 结果按方法标识去重。
     *
     * 匹配规则与 [com.charmnight.linkgraph.investigation.resolving.java.JavaOverrideResolver]
     * 完全一致：方法简单名 + 参数数量 + 每个参数位置的擦除相容性，覆盖泛型特化、协变返回，
     * 拒绝同名同参数数量但类型不同的重载。
     */
    private fun implementationMethods(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): List<JvmMethodSymbol> {
        val implementingClassIds = implementationClassIds(ownerClass, index)
        if (implementingClassIds.isEmpty()) {
            return emptyList()
        }
        return implementingClassIds
            .asSequence()
            .mapNotNull(index::findSymbol)
            .filterIsInstance<JvmClassSymbol>()
            .filter(::isConcreteJvmClass)
            .flatMap { classSymbol ->
                index.symbolIndex.methodsBySignature.values.asSequence()
                    .filter { candidate ->
                        candidate.ownerClassName == classSymbol.qualifiedName &&
                            JvmOverrideShapeMatcher.matchesOverride(candidate, method)
                    }
            }
            .distinctBy(JvmMethodSymbol::id)
            .toList()
    }

    /**
     * 先按完整签名命中索引；失败时按 owner/name/参数形状兜底。
     *
     * 前端流程图节点可能保留源码展示签名（如 `List<String>`），而 JVM 符号索引可能记录 PSI
     * 规范签名（如 `java.util.List`）。返回类型不参与 JVM 重载判定，因此这里以 owner/name/参数
     * 作为稳定匹配条件，避免把同一个抽象方法误判为未索引。
     */
    private fun findMethod(
        signature: String,
        index: ArchitectureGraphIndex,
    ): JvmMethodSymbol? {
        val normalizedSignature = signature.substringBefore('#', missingDelimiterValue = signature)
        index.findMethod(signature)?.let { return it }
        if (normalizedSignature != signature) {
            index.findMethod(normalizedSignature)?.let { return it }
        }
        val parsed = ParsedMethodSignature.parse(normalizedSignature) ?: return null
        return index.symbolIndex.methodsBySignature.values.asSequence()
            .filter { method ->
                method.ownerClassName == parsed.ownerClassName &&
                    method.simpleName == parsed.methodName &&
                    method.parameterTypes.size == parsed.parameterTypes.size &&
                    method.parameterTypes.zip(parsed.parameterTypes)
                        .all { (indexedType, requestedType) -> methodTypesCompatible(indexedType, requestedType) }
            }
            .sortedWith(
                compareByDescending<JvmMethodSymbol> { method -> method.returnType == parsed.returnType }
                    .thenBy(JvmMethodSymbol::signature),
            )
            .firstOrNull()
    }

    private fun isConcreteJvmClass(classSymbol: JvmClassSymbol): Boolean =
        classSymbol.origin == SourceOrigin.PROJECT_SOURCE &&
            classSymbol.kind != JvmClassKind.INTERFACE &&
            !classSymbol.abstract

    /**
     * 通过广度优先遍历实现/继承关系，收集目标类的全部子类与实现类标识。
     *
     * 从目标类出发，沿着 incoming 的实现和继承边反向收集，
     * 最后排除目标类自身，避免把抽象声明当成实现计入结果。
     */
    private fun implementationClassIds(
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): Set<String> {
        val result = implementationClassIdsFromRelations(ownerClass, index).toMutableSet()
        index.symbolIndex.classesByQualifiedName.values
            .asSequence()
            .filter { classSymbol -> classSymbol.id != ownerClass.id }
            .filter { classSymbol -> classSymbol.hasSuperType(ownerClass, index) }
            .mapTo(result, JvmClassSymbol::id)
        return result
    }

    private fun implementationClassIdsFromRelations(
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): Set<String> {
        val result = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(ownerClass.id)
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            index.relationIndex.incoming(currentId)
                .filter { relation -> relation.kind == JvmRelationKind.IMPLEMENTS || relation.kind == JvmRelationKind.EXTENDS }
                .forEach { relation ->
                    if (result.add(relation.fromSymbolId)) {
                        queue.add(relation.fromSymbolId)
                    }
                }
        }
        result.remove(ownerClass.id)
        return result
    }

    private fun implementationClassIdsFromClassSymbols(
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): Set<String> =
        index.symbolIndex.classesByQualifiedName.values
            .asSequence()
            .filter { classSymbol -> classSymbol.id != ownerClass.id }
            .filter { classSymbol -> classSymbol.hasSuperType(ownerClass, index) }
            .mapTo(linkedSetOf(), JvmClassSymbol::id)

    private fun JvmClassSymbol.hasSuperType(
        targetClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): Boolean {
        val visited = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<JvmClassSymbol>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.id)) {
                continue
            }
            current.directSuperTypes(index).forEach { superClass ->
                if (superClass.id == targetClass.id) {
                    return true
                }
                queue.add(superClass)
            }
        }
        return false
    }

    private fun JvmClassSymbol.directSuperTypes(index: ArchitectureGraphIndex): List<JvmClassSymbol> =
        sequenceOf(superClassName)
            .plus(interfaceNames.asSequence())
            .mapNotNull { typeName -> index.symbolIndex.findClassByTypeName(typeName, packageName) }
            .distinctBy(JvmClassSymbol::id)
            .toList()

    /**
     * 从方法签名中提取所属类的全限定名。
     *
     * 取参数列表前的部分，再以最后一个点切分得到类名；
     * 当签名结构不完整时返回空，由调用方按未找到处理。
     */
    private fun ownerClassName(signature: String): String? {
        val beforeParameters = signature.substringBefore('(', missingDelimiterValue = signature)
        return beforeParameters
            .substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
    }

    private data class ParsedMethodSignature(
        val ownerClassName: String,
        val methodName: String,
        val parameterTypes: List<String>,
        val returnType: String?,
    ) {
        companion object {
            fun parse(signature: String): ParsedMethodSignature? {
                val openParen = signature.indexOf('(').takeIf { index -> index >= 0 } ?: return null
                val closeParen = signature.indexOf(')', startIndex = openParen + 1)
                    .takeIf { index -> index >= openParen } ?: return null
                val ownerAndMethod = signature.substring(0, openParen)
                val ownerClassName = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = "")
                    .takeIf(String::isNotBlank) ?: return null
                val methodName = ownerAndMethod.substringAfterLast('.').takeIf(String::isNotBlank) ?: return null
                val parameterTypes = splitParameterTypes(signature.substring(openParen + 1, closeParen))
                val returnType = signature.substring(closeParen + 1)
                    .removePrefix(":")
                    .takeIf(String::isNotBlank)
                return ParsedMethodSignature(ownerClassName, methodName, parameterTypes, returnType)
            }

            private fun splitParameterTypes(raw: String): List<String> {
                if (raw.isBlank()) {
                    return emptyList()
                }
                val result = mutableListOf<String>()
                val current = StringBuilder()
                var genericDepth = 0
                raw.forEach { char ->
                    when (char) {
                        '<' -> {
                            genericDepth += 1
                            current.append(char)
                        }
                        '>' -> {
                            genericDepth = (genericDepth - 1).coerceAtLeast(0)
                            current.append(char)
                        }
                        ',' -> {
                            if (genericDepth == 0) {
                                current.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
                                current.clear()
                            } else {
                                current.append(char)
                            }
                        }
                        else -> current.append(char)
                    }
                }
                current.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
                return result
            }
        }
    }

    private fun methodTypesCompatible(indexedType: String, requestedType: String): Boolean {
        val indexed = eraseMethodType(indexedType)
        val requested = eraseMethodType(requestedType)
        return indexed == requested || isLikelyTypeParameterName(indexed) || isLikelyTypeParameterName(requested)
    }

    private fun eraseMethodType(type: String): String {
        val trimmed = type.trim().removeSuffix("?")
        val arraySuffix = buildString {
            var rest = trimmed
            while (rest.endsWith("[]")) {
                append("[]")
                rest = rest.removeSuffix("[]")
            }
        }
        val withoutArrays = trimmed.removeSuffix(arraySuffix)
        val erased = withoutArrays.substringBefore('<').substringAfterLast('.').trim()
        return erased + arraySuffix
    }

    private fun isLikelyTypeParameterName(type: String): Boolean =
        type.length == 1 && type[0].isUpperCase()

    private fun logNoImplementationDiagnostic(
        signature: String,
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
        implementationResolver: JvmImplementationSignatureResolver,
    ) {
        val relationClassIds = implementationClassIdsFromRelations(ownerClass, index)
        val classSymbolClassIds = implementationClassIdsFromClassSymbols(ownerClass, index)
        val implementationClassIds = (relationClassIds + classSymbolClassIds).toCollection(linkedSetOf())
        val implementationClasses = implementationClassIds
            .mapNotNull(index::findSymbol)
            .filterIsInstance<JvmClassSymbol>()
        val concreteImplementationClasses = implementationClasses.filter(::isConcreteJvmClass)
        val sameOwnerNameMethods = index.symbolIndex.methodsBySignature.values
            .filter { candidate -> candidate.ownerClassName == method.ownerClassName && candidate.simpleName == method.simpleName }
            .sortedBy(JvmMethodSymbol::signature)
        val candidateMethodsByName = concreteImplementationClasses
            .asSequence()
            .flatMap { classSymbol ->
                index.symbolIndex.methodsBySignature.values.asSequence()
                    .filter { candidate ->
                        candidate.ownerClassName == classSymbol.qualifiedName &&
                            candidate.simpleName == method.simpleName
                    }
            }
            .distinctBy(JvmMethodSymbol::id)
            .sortedBy(JvmMethodSymbol::signature)
            .toList()
        val matchedMethods = candidateMethodsByName
            .filter { candidate -> JvmOverrideShapeMatcher.matchesOverride(candidate, method) }
            .sortedBy(JvmMethodSymbol::signature)
        val incomingRelations = index.relationIndex.incoming(ownerClass.id)
        val outgoingRelations = index.relationIndex.outgoing(ownerClass.id)

        logger.warn(
            "调用展开解析 trace: stage=invocationExpansion.noImplementation, " +
                "signature=$signature, normalizedSignature=${signature.substringBefore('#', missingDelimiterValue = signature)}, " +
                "matchedMethod=${method.methodSummary()}, ownerClass=${ownerClass.classSummary()}, " +
                "sameOwnerNameMethods=${sameOwnerNameMethods.size}[${sameOwnerNameMethods.sampleMethods()}], " +
                "ownerIncoming=${incomingRelations.size}[${incomingRelations.sampleRelations(index)}], " +
                "ownerOutgoing=${outgoingRelations.size}[${outgoingRelations.sampleRelations(index)}], " +
                "relationImplementationClassIds=${relationClassIds.size}[${relationClassIds.sampleIds(index)}], " +
                "classSymbolImplementationClassIds=${classSymbolClassIds.size}[${classSymbolClassIds.sampleIds(index)}], " +
                "implementationClasses=${implementationClasses.size}[${implementationClasses.sampleClasses()}], " +
                "concreteImplementationClasses=${concreteImplementationClasses.size}[${concreteImplementationClasses.sampleClasses()}], " +
                "candidateMethodsByName=${candidateMethodsByName.size}[${candidateMethodsByName.sampleMethods()}], " +
                "matchedOverrideMethods=${matchedMethods.size}[${matchedMethods.sampleMethods()}], " +
                implementationResolver.diagnostic(method, ownerClass),
        )
    }

    private fun JvmMethodSymbol.methodSummary(): String =
        "$signature(id=$id, owner=$ownerClassName, params=${parameterTypes.joinToString("|")}, return=$returnType, abstract=$abstract, origin=$origin)"

    private fun JvmClassSymbol.classSummary(): String =
        "$qualifiedName(id=$id, kind=$kind, abstract=$abstract, origin=$origin, super=$superClassName, interfaces=${interfaceNames.joinToString("|")})"

    private fun List<JvmMethodSymbol>.sampleMethods(): String =
        take(SAMPLE_LIMIT).joinToString("|") { method -> method.methodSummary() }

    private fun List<JvmClassSymbol>.sampleClasses(): String =
        take(SAMPLE_LIMIT).joinToString("|") { classSymbol -> classSymbol.classSummary() }

    private fun Set<String>.sampleIds(index: ArchitectureGraphIndex): String =
        take(SAMPLE_LIMIT).joinToString("|") { symbolId ->
            val symbol = index.findSymbol(symbolId)
            when (symbol) {
                is JvmClassSymbol -> symbol.classSummary()
                is JvmMethodSymbol -> symbol.methodSummary()
                is JvmSymbol -> "${symbol.qualifiedName}(id=${symbol.id}, origin=${symbol.origin})"
                null -> "$symbolId(unindexed)"
            }
        }

    private fun List<com.charmnight.linkgraph.jvm.relation.JvmRelation>.sampleRelations(index: ArchitectureGraphIndex): String =
        take(SAMPLE_LIMIT).joinToString("|") { relation ->
            "${relation.kind}:${relation.fromSymbolId.symbolLabel(index)}->${relation.toSymbolId.symbolLabel(index)}"
        }

    private fun String.symbolLabel(index: ArchitectureGraphIndex): String =
        when (val symbol = index.findSymbol(this)) {
            is JvmClassSymbol -> "${symbol.qualifiedName}($this)"
            is JvmMethodSymbol -> "${symbol.signature}($this)"
            is JvmSymbol -> "${symbol.qualifiedName}($this)"
            null -> "$this(unindexed)"
        }

    private companion object {
        const val SAMPLE_LIMIT = 8
    }
}
