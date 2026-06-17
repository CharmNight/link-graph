package com.charmnight.linkgraph.investigation.resolving.spring

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.java.JvmEvidenceIndexAdapter
import com.charmnight.linkgraph.investigation.resolving.java.JvmIndexReadActionEvidenceResolver
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

/**
 * 使用共享 JVM relation index 解析 Spring 事件发布与监听关系。
 */
class SpringEventResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "spring-event"

    /**
     * 仅处理 Spring Event 目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.SPRING_EVENT
    }

    /**
     * 解析发布点中的 event 类型，并查找匹配的监听器。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        return resolveFromJvmIndex(goal, index)
            ?: unresolved(goal, "共享 JvmRelationIndex 中未找到 SPRING_EVENT_LISTENS 关系。")
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
        val eventName = goal.eventClassName?.takeIf(String::isNotBlank)
        val relations = sourceSymbols.flatMap { symbol -> index.relationIndex.outgoing(symbol.id) }
            .filter { relation -> relation.kind == JvmRelationKind.SPRING_EVENT_LISTENS }
            .filter { relation ->
                eventName == null ||
                    relation.metadata["spring.event"] == eventName ||
                    relation.metadata["spring.event"]?.substringAfterLast('.') == eventName.substringAfterLast('.')
            }
            .distinctBy { relation -> relation.id }
        if (relations.isEmpty()) {
            return null
        }
        val facts = relations.map { relation ->
            val listenerMethodSignature = relation.metadata["spring.listenerMethod"]
            val listenerSymbol = listenerMethodSignature?.let(index::findMethod) ?: index.findSymbol(relation.toSymbolId)
            val sample = relation.samples.firstOrNull()
            EvidenceFact(
                factId = "${goal.goalId}-$id-${relation.id}",
                level = EvidenceLevel.DIRECT_FRAMEWORK_RESOLVED,
                resolverId = id,
                symbolSignature = listenerSymbol?.qualifiedName ?: listenerMethodSignature ?: relation.toSymbolId,
                filePath = listenerSymbol?.source?.displayPath ?: sample?.filePath ?: "",
                startLine = listenerSymbol?.source?.startLine ?: sample?.startLine,
                endLine = listenerSymbol?.source?.endLine ?: sample?.endLine,
                claim = "已确认 Spring Event 监听器 ${listenerSymbol?.qualifiedName ?: listenerMethodSignature ?: relation.toSymbolId}。",
                whyResolved = "复用 JvmRelationIndex 的 SPRING_EVENT_LISTENS 关系，confidence=${relation.confidence.name}。",
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
            requiredEvidence = listOf("补充事件类型、发布点源码、监听器源码或运行时事件 trace。"),
        )
    }
}
