package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceFact
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.EvidenceLevel
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

/**
 * 解析 Java SPI 的 `META-INF/services/<接口全限定名>` 配置绑定。
 */
class JavaSpiResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-spi-binding"

    /**
     * 仅处理 SPI 绑定目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.SPI_BINDING
    }

    /**
     * 从共享 JVM 关系索引读取 SPI provider 证据。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        val interfaceName = goal.interfaceName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少 SPI 接口全限定名。")
        return resolveFromJvmIndex(goal, index, interfaceName)
            ?: unresolved(goal, "未从共享 JVM 关系索引找到 META-INF/services/$interfaceName 的 provider。")
    }

    private fun resolveFromJvmIndex(
        goal: EvidenceGoal,
        index: ArchitectureGraphIndex,
        interfaceName: String,
    ): ResolutionOutcome.Resolved? {
        val interfaceSymbol = index.findClass(interfaceName) ?: return null
        val relations = index.relationIndex.incoming(interfaceSymbol.id)
            .filter { relation -> relation.kind == JvmRelationKind.SPI_PROVIDES }
        if (relations.isEmpty()) {
            return null
        }
        val facts = relations.flatMap { relation ->
            val provider = index.findSymbol(relation.fromSymbolId)
            val providerFact = EvidenceFact(
                factId = "${goal.goalId}-$id-${relation.id}",
                level = EvidenceLevel.DIRECT_FRAMEWORK_RESOLVED,
                resolverId = id,
                symbolSignature = provider?.qualifiedName ?: relation.fromSymbolId,
                filePath = provider?.source?.displayPath ?: relation.samples.firstOrNull()?.filePath ?: "",
                startLine = provider?.source?.startLine ?: relation.samples.firstOrNull()?.startLine,
                endLine = provider?.source?.endLine ?: relation.samples.firstOrNull()?.endLine,
                claim = "已确认 SPI provider ${provider?.qualifiedName ?: relation.fromSymbolId} 绑定 $interfaceName。",
                whyResolved = "复用 JvmRelationIndex 的 SPI_PROVIDES 关系，confidence=${relation.confidence.name}。",
            )
            val configFacts = relation.samples.mapIndexed { indexInRelation, sample ->
                EvidenceFact(
                    factId = "${goal.goalId}-$id-${relation.id}-config-$indexInRelation",
                    level = EvidenceLevel.CONFIG_RESOLVED,
                    resolverId = id,
                    symbolSignature = "META-INF/services/$interfaceName",
                    filePath = sample.filePath ?: "",
                    startLine = sample.startLine,
                    endLine = sample.endLine,
                    claim = sample.claim,
                    whyResolved = "SPI 配置来源于共享 JVM 关系索引。",
                )
            }
            listOf(providerFact) + configFacts
        }.distinctBy(EvidenceFact::factId)
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
            requiredEvidence = listOf("补充 META-INF/services/${goal.interfaceName ?: "目标接口"}、provider 类或运行时 ServiceLoader trace。"),
        )
    }
}
