package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.index.JvmClassKind

/**
 * 使用统一 ArchitectureGraphIndex 精确解析枚举常量。
 */
class JavaEnumConstantResolver(
    jvmEvidenceIndexAdapter: JvmEvidenceIndexAdapter = JvmEvidenceIndexAdapter(),
) : JvmIndexReadActionEvidenceResolver(jvmEvidenceIndexAdapter) {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-enum-constant"

    /**
     * 仅处理枚举常量取证目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.ENUM_CONSTANT
    }

    /**
     * 从共享 JVM symbol index 中解析枚举类和枚举常量，避免文本搜索污染上下文。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
        index: ArchitectureGraphIndex,
    ): ResolutionOutcome {
        val enumName = goal.symbolName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少枚举类名。")
        val constantName = goal.memberName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少枚举常量名。")
        val classCandidates = JvmInvestigationEvidenceSupport.resolveClassCandidates(index, enumName, JvmClassKind.ENUM)
        return when (classCandidates.size) {
            0 -> unresolved(goal, "未找到枚举类 $enumName。")
            1 -> resolveConstantInSingleClass(goal, index, classCandidates.single().qualifiedName, constantName)
            else -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = classCandidates.mapIndexed { candidateIndex, enumClass ->
                    JvmInvestigationEvidenceSupport.enumCandidate(
                        goal = goal,
                        resolverId = id,
                        enumClass = enumClass,
                        constantName = constantName,
                        reason = "短类名 $enumName 命中多个枚举类，未确认应该使用哪一个。",
                    ).copy(candidateId = "${goal.goalId}-class-candidate-$candidateIndex")
                },
                reason = "枚举类短名 $enumName 命中多个候选，不能确认唯一源码符号。",
                requiredEvidence = listOf("补充 $enumName 的全限定类名或调用点所属 import。"),
            )
        }
    }

    /**
     * 在唯一枚举类中解析指定常量。
     */
    private fun resolveConstantInSingleClass(
        goal: EvidenceGoal,
        index: ArchitectureGraphIndex,
        enumName: String,
        constantName: String,
    ): ResolutionOutcome {
        val enumClass = index.findClass(enumName)
            ?: return unresolved(goal, "未找到枚举类 $enumName。")
        if (enumClass.kind != JvmClassKind.ENUM) {
            return unresolved(goal, "$enumName 不是枚举类。")
        }
        val field = index.findField("${enumClass.qualifiedName}.$constantName")
            ?: return unresolved(goal, "枚举类 ${enumClass.qualifiedName} 中未找到常量 $constantName。")
        return ResolutionOutcome.Resolved(
            resolverId = id,
            facts = listOf(
                JvmInvestigationEvidenceSupport.fieldFact(
                    goal = goal,
                    resolverId = id,
                    field = field,
                    claim = "已确认枚举常量 ${field.qualifiedName} 存在于 ArchitectureGraphIndex。",
                    whyResolved = "复用 ArchitectureGraphIndex 的 JvmFieldSymbol，未使用文本搜索。",
                ),
            ),
        )
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
            requiredEvidence = listOf("补充 ${goal.symbolName ?: "目标枚举"} 的全限定类名或源码索引。"),
        )
    }
}
