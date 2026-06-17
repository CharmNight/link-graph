package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

/**
 * 解析可静态证明的 Java 反射调用。
 */
class JavaReflectionResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-reflection-call"

    /**
     * 仅处理反射调用目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.REFLECTION_CALL
    }

    /**
     * 从共享 JVM 关系索引读取可静态证明的反射目标。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        return resolveFromJvmIndex(goal, index)
            ?: unresolved(goal, "共享 JVM 关系索引中未找到静态可证明的 REFLECTS_TO 关系。")
    }

    private fun resolveFromJvmIndex(
        goal: EvidenceGoal,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome.Resolved? {
        val ownerName = goal.ownerClassName?.takeIf(String::isNotBlank)
        val sourceSymbols = when {
            ownerName != null -> listOfNotNull(index.findClass(ownerName))
            else -> index.symbolIndex.classesByQualifiedName.values.toList()
        }
        val callsiteSignature = goal.callsiteSignature
            ?: goal.symbolSignature
                ?.takeIf { signature -> signature.contains("(") && signature.contains("):") }
        val relations = sourceSymbols.flatMap { symbol -> index.relationIndex.outgoing(symbol.id) }
            .filter { relation -> relation.kind == JvmRelationKind.REFLECTS_TO }
            .filter { relation ->
                relation.confidence == JvmRelationConfidence.PROVEN ||
                    relation.confidence == JvmRelationConfidence.RULE_INFERRED
            }
            .filter { relation ->
                callsiteSignature == null || relation.metadata["reflect.sourceMethod"] == callsiteSignature
            }
            .distinctBy { relation -> relation.id }
        if (relations.isEmpty()) {
            return null
        }
        val facts = relations.map { relation ->
            val target = index.findSymbol(relation.toSymbolId)
            val sample = relation.samples.firstOrNull()
            EvidenceFact(
                factId = "${goal.goalId}-$id-${relation.id}",
                level = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
                resolverId = id,
                symbolSignature = target?.qualifiedName ?: relation.toSymbolId,
                filePath = target?.source?.displayPath ?: sample?.filePath ?: "",
                startLine = target?.source?.startLine ?: sample?.startLine,
                endLine = target?.source?.endLine ?: sample?.endLine,
                claim = "已确认反射目标 ${target?.qualifiedName ?: relation.toSymbolId}。",
                whyResolved = "复用 JvmRelationIndex 的 REFLECTS_TO 关系，confidence=${relation.confidence.name}。",
            )
        }
        return ResolutionOutcome.Resolved(resolverId = id, facts = facts)
    }

    /**
     * 构造未解析结果。
     */
    private fun unresolved(
        goal: EvidenceGoal,
        reason: String,
    ): ResolutionOutcome.Unresolved {
        return ResolutionOutcome.Unresolved(
            resolverId = id,
            reason = reason,
            requiredEvidence = listOf("补充反射 class/method 常量、运行时 trace 或配置实际值。"),
        )
    }
}
