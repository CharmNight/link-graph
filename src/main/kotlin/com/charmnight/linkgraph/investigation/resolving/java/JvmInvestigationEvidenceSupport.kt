package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.domain.EvidenceCandidate
import com.charmnight.linkgraph.investigation.domain.EvidenceFact
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.source.SourceOrigin

internal object JvmInvestigationEvidenceSupport {
    fun resolveMethodCandidates(
        goal: EvidenceGoal,
        index: ArchitectureGraphIndex,
    ): MethodSymbolCandidates {
        goal.symbolSignature
            ?.takeIf { signature -> signature.contains('(') && signature.contains("):") }
            ?.let(index::findMethod)
            ?.let { method -> return MethodSymbolCandidates(listOf(method), null) }

        val ownerName = goal.ownerClassName?.takeIf(String::isNotBlank)
            ?: return MethodSymbolCandidates(emptyList(), "缺少所属类名。")
        val methodName = goal.methodName?.takeIf(String::isNotBlank)
            ?: return MethodSymbolCandidates(emptyList(), "缺少方法名。")
        val classCandidates = ownerNameCandidates(ownerName, goal)
            .flatMap { candidateOwnerName -> resolveClassCandidates(index, candidateOwnerName) }
            .distinctBy(JvmClassSymbol::id)
        if (classCandidates.isEmpty()) {
            return MethodSymbolCandidates(emptyList(), "未找到类 $ownerName。")
        }
        val methods = classCandidates
            .flatMap { classSymbol ->
                index.symbolIndex.methodsBySignature.values.filter { method ->
                    method.ownerClassName == classSymbol.qualifiedName &&
                        method.simpleName == methodName &&
                        matchesRequestedSignature(method, goal.parameterTypes, goal.returnType)
                }
            }
            .distinctBy(JvmMethodSymbol::id)
            .sortedBy(JvmMethodSymbol::signature)
        return MethodSymbolCandidates(methods, null)
    }

    fun resolveClassCandidates(
        index: ArchitectureGraphIndex,
        className: String,
        kind: JvmClassKind? = null,
    ): List<JvmClassSymbol> {
        val exact = index.findClass(className)
        val candidates = if (exact != null) {
            listOf(exact)
        } else {
            val shortName = className.substringAfterLast('.')
            index.symbolIndex.classesByQualifiedName.values
                .filter { symbol -> symbol.simpleName == shortName || symbol.qualifiedName == className }
                .sortedBy(JvmClassSymbol::qualifiedName)
        }
        return candidates
            .filter { symbol -> symbol.origin == SourceOrigin.PROJECT_SOURCE }
            .filter { symbol -> kind == null || symbol.kind == kind }
    }

    fun methodFact(
        goal: EvidenceGoal,
        resolverId: String,
        method: JvmMethodSymbol,
        level: EvidenceLevel = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
        claim: String = "已确认方法 ${method.signature} 存在于 ArchitectureGraphIndex。",
        whyResolved: String,
    ): EvidenceFact =
        EvidenceFact(
            factId = "${goal.goalId}-$resolverId-${method.signature.hashCode()}",
            level = level,
            resolverId = resolverId,
            symbolSignature = method.signature,
            filePath = method.source?.displayPath.orEmpty(),
            startLine = method.source?.startLine,
            endLine = method.source?.endLine,
            claim = claim,
            whyResolved = whyResolved,
        )

    fun fieldFact(
        goal: EvidenceGoal,
        resolverId: String,
        field: JvmFieldSymbol,
        level: EvidenceLevel = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
        claim: String = "已确认符号 ${field.qualifiedName} 存在于 ArchitectureGraphIndex。",
        whyResolved: String,
    ): EvidenceFact =
        EvidenceFact(
            factId = "${goal.goalId}-$resolverId-${field.qualifiedName.hashCode()}",
            level = level,
            resolverId = resolverId,
            symbolSignature = field.qualifiedName,
            filePath = field.source?.displayPath.orEmpty(),
            startLine = field.source?.startLine,
            endLine = field.source?.endLine,
            claim = claim,
            whyResolved = whyResolved,
        )

    fun methodCandidate(
        goal: EvidenceGoal,
        resolverId: String,
        method: JvmMethodSymbol,
        reason: String,
    ): EvidenceCandidate =
        EvidenceCandidate(
            candidateId = "${goal.goalId}-$resolverId-candidate-${method.signature.hashCode()}",
            symbolSignature = method.signature,
            resolverId = resolverId,
            reason = reason,
        )

    fun enumCandidate(
        goal: EvidenceGoal,
        resolverId: String,
        enumClass: JvmClassSymbol,
        constantName: String,
        reason: String,
    ): EvidenceCandidate =
        EvidenceCandidate(
            candidateId = "${goal.goalId}-$resolverId-candidate-${enumClass.qualifiedName.hashCode()}",
            symbolSignature = "${enumClass.qualifiedName}.$constantName",
            resolverId = resolverId,
            reason = reason,
        )

    fun matchesRequestedSignature(
        method: JvmMethodSymbol,
        parameterTypes: List<String>,
        returnType: String?,
    ): Boolean {
        if (parameterTypes.isNotEmpty()) {
            val expectedParameters = parameterTypes.map(::normalizeRequestedType)
            if (method.parameterTypes.size != expectedParameters.size ||
                method.parameterTypes.zip(expectedParameters).any { (actual, expected) -> !typesMatch(actual, expected) }
            ) {
                return false
            }
        }
        if (!returnType.isNullOrBlank()) {
            val actualReturnType = method.returnType ?: "void"
            if (!typesMatch(actualReturnType, normalizeRequestedType(returnType))) {
                return false
            }
        }
        return true
    }

    fun normalizeRequestedType(rawType: String): String {
        return when (rawType.trim()) {
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
            else -> rawType.trim()
        }
    }

    private fun ownerNameCandidates(
        ownerName: String,
        goal: EvidenceGoal,
    ): List<String> {
        val names = linkedSetOf(ownerName)
        if ('.' !in ownerName) {
            (goal.parameterTypes + listOfNotNull(goal.returnType))
                .mapNotNull { typeName -> typeName.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank) }
                .forEach { packageName -> names += "$packageName.$ownerName" }
        }
        return names.toList()
    }

    private fun typesMatch(
        actual: String,
        expected: String,
    ): Boolean {
        val normalizedActual = normalizeRequestedType(actual)
        val normalizedExpected = normalizeRequestedType(expected)
        return normalizedActual == normalizedExpected ||
            normalizedActual.substringAfterLast('.') == normalizedExpected.substringAfterLast('.')
    }
}

internal data class MethodSymbolCandidates(
    val methods: List<JvmMethodSymbol>,
    val unresolvedReason: String?,
)
