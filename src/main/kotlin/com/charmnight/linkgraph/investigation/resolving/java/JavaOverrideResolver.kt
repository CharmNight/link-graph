package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmOverrideShapeMatcher
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin

/**
 * 使用统一 ArchitectureGraphIndex 解析接口或抽象方法的真实实现。
 */
class JavaOverrideResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-method-override"

    /**
     * 仅处理方法重写目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.METHOD_OVERRIDE
    }

    /**
     * 解析接口/抽象方法对应的项目内具体实现。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        val baseCandidates = JvmInvestigationEvidenceSupport.resolveMethodCandidates(goal, index)
        val baseMethod = baseCandidates.methods.singleOrNull()
            ?: return unresolvedBase(goal, baseCandidates)
        val implementations = concreteImplementations(baseMethod, index)
        return when (implementations.size) {
            0 -> ResolutionOutcome.Unresolved(
                resolverId = id,
                reason = "未找到 ${baseMethod.signature} 的项目内具体实现。",
                requiredEvidence = listOf("补充实现类源码、Spring 注入绑定或运行时 receiver 类型。"),
            )
            1 -> ResolutionOutcome.Resolved(
                resolverId = id,
                facts = listOf(
                    JvmInvestigationEvidenceSupport.methodFact(
                        goal = goal,
                        resolverId = id,
                        method = implementations.single(),
                        claim = "已确认 ${baseMethod.signature} 的唯一项目内实现是 ${implementations.single().signature}。",
                        whyResolved = "复用 ArchitectureGraphIndex 的 IMPLEMENTS/EXTENDS 关系找到唯一具体实现。",
                    ),
                ),
            )
            else -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = implementations.map { method ->
                    JvmInvestigationEvidenceSupport.methodCandidate(
                        goal = goal,
                        resolverId = id,
                        method = method,
                        reason = "接口或抽象方法存在多个具体实现，无法确认运行时 receiver。",
                    )
                },
                reason = "${baseMethod.signature} 存在多个具体实现。",
                requiredEvidence = listOf("补充运行时 receiver 类型、Spring Bean 注入绑定或调用 trace。"),
            )
        }
    }

    /**
     * 构造基础方法无法唯一解析时的结果。
     */
    private fun unresolvedBase(
        goal: EvidenceGoal,
        baseCandidates: MethodSymbolCandidates,
    ): ResolutionOutcome {
        if (baseCandidates.methods.size > 1) {
            return ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = baseCandidates.methods.map { method ->
                    JvmInvestigationEvidenceSupport.methodCandidate(
                        goal = goal,
                        resolverId = id,
                        method = method,
                        reason = "基础方法本身存在重载，无法进入重写分析。",
                    )
                },
                reason = "基础方法 ${goal.ownerClassName}.${goal.methodName} 不唯一。",
                requiredEvidence = listOf("补充完整方法签名。"),
            )
        }
        return ResolutionOutcome.Unresolved(
            resolverId = id,
            reason = baseCandidates.unresolvedReason ?: "未找到基础方法 ${goal.ownerClassName}.${goal.methodName}。",
            requiredEvidence = listOf("补充接口/抽象方法完整签名。"),
        )
    }

    /**
     * 查询项目内具体实现方法。
     *
     * 匹配规则统一走 [JvmOverrideShapeMatcher.matchesOverride]：
     * - 方法简单名 + 参数数量 + 每个参数位置的擦除相容性
     * - 覆盖协变返回、泛型特化、Kotlin suspend 等场景
     * - 拒绝同名同参数数量但类型不同的重载（过匹配）
     */
    private fun concreteImplementations(
        baseMethod: JvmMethodSymbol,
        index: ArchitectureGraphIndex,
    ): List<JvmMethodSymbol> {
        val ownerClass = index.findClass(baseMethod.ownerClassName) ?: return emptyList()
        val implementingClassIds = implementationClassIds(ownerClass, index)
        return implementingClassIds
            .asSequence()
            .mapNotNull(index::findSymbol)
            .filterIsInstance<JvmClassSymbol>()
            .filter(::isConcreteClass)
            .flatMap { classSymbol ->
                index.symbolIndex.methodsBySignature.values.asSequence()
                    .filter { method ->
                        method.ownerClassName == classSymbol.qualifiedName &&
                            JvmOverrideShapeMatcher.matchesOverride(method, baseMethod)
                    }
            }
            .distinctBy(JvmMethodSymbol::id)
            .sortedBy(JvmMethodSymbol::signature)
            .toList()
    }

    /**
     * 判断类是否为可实例化的具体类。
     */
    private fun isConcreteClass(classSymbol: JvmClassSymbol): Boolean {
        return classSymbol.origin == SourceOrigin.PROJECT_SOURCE &&
            classSymbol.kind != JvmClassKind.INTERFACE &&
            !classSymbol.abstract
    }

    /**
     * 广度优先收集基础方法所属类（含接口）的所有实现/子类节点 ID。
     * 沿 IMPLEMENTS 与 EXTENDS 反向关系向上扩展，最终排除基础类自身。
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
}
