package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin

/**
 * 调用展开目标解析器。
 *
 * 根据传入的方法签名和图索引，判断调用点应当如何展开：
 * 当目标方法在项目源码中时进一步判断抽象方法是否需要解析到具体实现，
 * 当目标在 JDK 或外部库中时给出对应的外部来源标记。
 */
class InvocationExpansionTargetResolver {
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
    ): InvocationExpansionTarget {
        val signature = invocationSignature.trim()
        if (signature.isBlank()) {
            return InvocationExpansionTarget(InvocationExpansionTargetKind.NOT_FOUND)
        }
        val method = index.findMethod(signature)
            ?: index.findMethod(signature.substringBefore('#', missingDelimiterValue = signature))
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
        val implementations = implementationMethods(method, ownerClass, index)
        return when (implementations.size) {
            0 -> InvocationExpansionTarget(InvocationExpansionTargetKind.NO_IMPLEMENTATION)
            1 -> InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.PROJECT_SOURCE,
                signature = implementations.single().signature,
            )
            else -> InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
                candidateSignatures = implementations.map(JvmMethodSymbol::signature).sorted(),
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
     * 再在这些项目源码的实现类中按 [matchesByOverrideShape] 匹配，
     * 结果按方法标识去重。
     *
     * 匹配规则用 simpleName + 参数数量：与 JavaOverrideResolver 保持一致，
     * 覆盖泛型特化、协变返回等 parameterTypes 严格相等会漏匹配的场景。
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
        val parameterCount = method.parameterTypes.size
        return implementingClassIds
            .asSequence()
            .mapNotNull(index::findSymbol)
            .filterIsInstance<JvmClassSymbol>()
            .filter { symbol -> symbol.origin == SourceOrigin.PROJECT_SOURCE }
            .flatMap { classSymbol ->
                index.symbolIndex.methodsBySignature.values.asSequence()
                    .filter { candidate ->
                        candidate.ownerClassName == classSymbol.qualifiedName &&
                            candidate.simpleName == method.simpleName &&
                            candidate.parameterTypes.size == parameterCount
                    }
            }
            .distinctBy(JvmMethodSymbol::id)
            .toList()
    }

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
}
