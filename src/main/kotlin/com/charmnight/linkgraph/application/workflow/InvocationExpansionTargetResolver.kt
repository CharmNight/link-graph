package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin

class InvocationExpansionTargetResolver {
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

    private fun requiresImplementationResolution(
        ownerClass: JvmClassSymbol,
        method: JvmMethodSymbol,
    ): Boolean =
        ownerClass.kind == JvmClassKind.INTERFACE ||
            ownerClass.abstract ||
            method.abstract

    private fun implementationMethods(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
        index: ArchitectureGraphIndex,
    ): List<JvmMethodSymbol> {
        val implementingClassIds = implementationClassIds(ownerClass, index)
        if (implementingClassIds.isEmpty()) {
            return emptyList()
        }
        val parameterTypes = method.parameterTypes
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
                            candidate.parameterTypes == parameterTypes
                    }
            }
            .distinctBy(JvmMethodSymbol::id)
            .toList()
    }

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

    private fun ownerClassName(signature: String): String? {
        val beforeParameters = signature.substringBefore('(', missingDelimiterValue = signature)
        return beforeParameters
            .substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
    }
}
