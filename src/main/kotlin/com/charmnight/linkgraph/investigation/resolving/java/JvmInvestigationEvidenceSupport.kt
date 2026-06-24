package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceCandidate
import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.source.SourceOrigin

/**
 * JVM 取证过程中跨 resolver 共享的辅助工具集合。
 *
 * 主要负责：把取证目标转换为索引候选、构造可进入 LLM 上下文的事实或候选对象、
 * 把 LLM 文本中的简写类型还原为全限定名以便和方法签名比对。
 */
internal object JvmInvestigationEvidenceSupport {
    /**
     * 根据取证目标在索引中查找匹配的方法候选。
     *
     * 优先按完整签名精确命中，命中失败再退回到所属类名 + 方法名 + 参数/返回值过滤。
     */
    fun resolveMethodCandidates(
        goal: EvidenceGoal,
        index: ArchitectureGraphIndex,
    ): MethodSymbolCandidates {
        // 当目标携带完整签名时，优先按签名做唯一精确查找。
        goal.symbolSignature
            ?.takeIf { signature -> signature.contains('(') && signature.contains("):") }
            ?.let(index::findMethod)
            ?.let { method -> return MethodSymbolCandidates(listOf(method), null) }

        // 缺少所属类名或方法名时直接返回带说明的空结果，避免后续无意义查询。
        val ownerName = goal.ownerClassName?.takeIf(String::isNotBlank)
            ?: return MethodSymbolCandidates(emptyList(), "缺少所属类名。")
        val methodName = goal.methodName?.takeIf(String::isNotBlank)
            ?: return MethodSymbolCandidates(emptyList(), "缺少方法名。")
        // 把短类名或全限定类名解析为索引中的实际类候选，便于后续按方法过滤。
        val classCandidates = ownerNameCandidates(ownerName, goal)
            .flatMap { candidateOwnerName -> resolveClassCandidates(index, candidateOwnerName) }
            .distinctBy(JvmClassSymbol::id)
        if (classCandidates.isEmpty()) {
            return MethodSymbolCandidates(emptyList(), "未找到类 $ownerName。")
        }
        // 在每个候选类下查找方法名相同且参数/返回类型匹配的方法。
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

    /**
     * 按类名查找候选类。
     *
     * 全限定名精确匹配优先，未命中时按短名兜底匹配；同时可按 [kind] 限制只接受某种类型（例如枚举）。
     */
    fun resolveClassCandidates(
        index: ArchitectureGraphIndex,
        className: String,
        kind: JvmClassKind? = null,
    ): List<JvmClassSymbol> {
        // 精确命中优先，否则按短名在全部类中查找。
        val exact = index.findClass(className)
        val candidates = if (exact != null) {
            listOf(exact)
        } else {
            val shortName = className.substringAfterLast('.')
            index.symbolIndex.classesByQualifiedName.values
                .filter { symbol -> symbol.simpleName == shortName || symbol.qualifiedName == className }
                .sortedBy(JvmClassSymbol::qualifiedName)
        }
        // 仅保留项目源码中的类，避免命中外部依赖造成误导；同时按需进一步按类型过滤。
        return candidates
            .filter { symbol -> symbol.origin == SourceOrigin.PROJECT_SOURCE }
            .filter { symbol -> kind == null || symbol.kind == kind }
    }

    /**
     * 根据方法符号构造一条确定性事实。
     *
     * 默认等级为直接源码命中，调用方可通过参数覆盖等级、claim 与 whyResolved。
     */
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

    /**
     * 根据字段符号构造一条确定性事实。
     *
     * 主要用于枚举常量或字段级目标，默认等级为直接源码命中。
     */
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

    /**
     * 根据方法符号构造一个候选证据，仅用于 UI 展示，不能进入 LLM 上下文。
     */
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

    /**
     * 根据枚举类构造一个候选证据，常用于短类名命中多个枚举类的场景。
     */
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

    /**
     * 判断索引中的实际方法签名是否与用户/规划器给定的参数和返回值匹配。
     *
     * 当规划器没有提供参数或返回值时，对应维度不参与匹配。
     */
    fun matchesRequestedSignature(
        method: JvmMethodSymbol,
        parameterTypes: List<String>,
        returnType: String?,
    ): Boolean {
        // 参数列表非空时，需逐一标准化后比较；数量或类型不一致直接拒绝。
        if (parameterTypes.isNotEmpty()) {
            val expectedParameters = parameterTypes.map(::normalizeRequestedType)
            if (method.parameterTypes.size != expectedParameters.size ||
                method.parameterTypes.zip(expectedParameters).any { (actual, expected) -> !typesMatch(actual, expected) }
            ) {
                return false
            }
        }
        // 返回类型非空时，再校验返回类型是否一致。
        if (!returnType.isNullOrBlank()) {
            val actualReturnType = method.returnType ?: "void"
            if (!typesMatch(actualReturnType, normalizeRequestedType(returnType))) {
                return false
            }
        }
        return true
    }

    /**
     * 把方法签名中的简写类型名还原为全限定名，便于跨表示形式比对。
     */
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

    /**
     * 在缺少包名时根据参数/返回类型所属包补出候选全限定类名。
     *
     * 用户提问往往只写了短类名，这里利用方法签名中的其它类型所属包做兜底推断。
     */
    private fun ownerNameCandidates(
        ownerName: String,
        goal: EvidenceGoal,
    ): List<String> {
        val names = linkedSetOf(ownerName)
        // 仅在传入的是短类名时才尝试补全，避免对全限定名做无意义改造。
        if ('.' !in ownerName) {
            (goal.parameterTypes + listOfNotNull(goal.returnType))
                .mapNotNull { typeName -> typeName.substringBeforeLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank) }
                .forEach { packageName -> names += "$packageName.$ownerName" }
        }
        return names.toList()
    }

    /**
     * 判断两个类型字符串是否可视为相同。
     *
     * 全限定名比对失败时再退回到短名比对，以兼容用户书写与索引表示不一致的情况。
     */
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

/**
 * 保存方法符号候选查询结果。
 */
internal data class MethodSymbolCandidates(
    /** 保存匹配到的方法符号列表。 */
    val methods: List<JvmMethodSymbol>,
    /** 保存未匹配或异常时的说明，便于上游生成未解析结果。 */
    val unresolvedReason: String?,
)
