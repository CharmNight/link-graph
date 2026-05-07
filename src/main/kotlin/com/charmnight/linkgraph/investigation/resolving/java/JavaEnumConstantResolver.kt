package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.investigation.domain.EvidenceCandidate
import com.charmnight.linkgraph.investigation.domain.EvidenceFact
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.EvidenceResolver
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.search.GlobalSearchScope

/**
 * 使用 Java PSI 精确解析枚举常量。
 */
class JavaEnumConstantResolver : EvidenceResolver {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-enum-constant"

    /**
     * 仅处理枚举常量取证目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.ENUM_CONSTANT
    }

    /**
     * 在 IDEA read action 中解析枚举类和枚举常量，避免文本搜索污染上下文。
     */
    override fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        return ReadAction.compute<ResolutionOutcome, RuntimeException> {
            resolveInReadAction(goal, context)
        }
    }

    /**
     * 执行真正的 PSI 解析逻辑。
     */
    private fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        val enumName = goal.symbolName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少枚举类名。")
        val constantName = goal.memberName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少枚举常量名。")
        val scope = GlobalSearchScope.projectScope(context.project)
        val psiFacade = JavaPsiFacade.getInstance(context.project)
        val psiClass = psiFacade.findClass(enumName, scope)
            ?: return resolveByShortName(goal, context, enumName, constantName, scope)
        if (!psiClass.isEnum) {
            return unresolved(goal, "$enumName 不是枚举类。")
        }
        return resolveConstantInSingleClass(goal, psiClass, enumName, constantName)
    }

    /**
     * 使用短类名解析枚举类；多命中时只返回候选，不能升级为直接证据。
     */
    private fun resolveByShortName(
        goal: EvidenceGoal,
        context: InvestigationContext,
        enumName: String,
        constantName: String,
        scope: GlobalSearchScope,
    ): ResolutionOutcome {
        val candidates = findByShortName(context, enumName, scope)
        return when (candidates.size) {
            0 -> unresolved(goal, "未找到枚举类 $enumName。")
            1 -> resolveConstantInSingleClass(goal, candidates.single(), enumName, constantName)
            else -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = candidates.mapIndexed { index, psiClass ->
                    EvidenceCandidate(
                        candidateId = "${goal.goalId}-class-candidate-$index",
                        symbolSignature = "${psiClass.qualifiedName ?: psiClass.name}.$constantName",
                        resolverId = id,
                        reason = "短类名 $enumName 命中多个枚举类，未确认应该使用哪一个。",
                    )
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
        psiClass: com.intellij.psi.PsiClass,
        enumName: String,
        constantName: String,
    ): ResolutionOutcome {
        val enumConstants = psiClass.fields
            .filterIsInstance<PsiEnumConstant>()
            .filter { constant -> constant.name == constantName }
        return when (enumConstants.size) {
            0 -> unresolved(goal, "枚举类 ${psiClass.qualifiedName ?: enumName} 中未找到常量 $constantName。")
            1 -> resolved(goal, enumConstants.single())
            else -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = enumConstants.mapIndexed { index, constant ->
                    EvidenceCandidate(
                        candidateId = "${goal.goalId}-candidate-$index",
                        symbolSignature = "${psiClass.qualifiedName ?: enumName}.${constant.name}",
                        resolverId = id,
                        reason = "同名枚举常量出现多次，无法确认唯一源码位置。",
                    )
                },
                reason = "枚举常量 $constantName 命中多个候选。",
                requiredEvidence = listOf("补充全限定枚举类名或重新分析源码索引。"),
            )
        }
    }

    /**
     * 使用短类名回退查找项目内枚举类。
     */
    private fun findByShortName(
        context: InvestigationContext,
        enumName: String,
        scope: GlobalSearchScope,
    ): List<com.intellij.psi.PsiClass> {
        return com.intellij.psi.search.PsiShortNamesCache.getInstance(context.project)
            .getClassesByName(enumName.substringAfterLast('.'), scope)
            .filter { psiClass -> psiClass.isEnum }
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

    /**
     * 把唯一枚举常量转换为直接源码事实。
     */
    private fun resolved(
        goal: EvidenceGoal,
        constant: PsiEnumConstant,
    ): ResolutionOutcome.Resolved {
        val psiClass = constant.containingClass
        val file = constant.containingFile ?: constant.navigationElement.containingFile
        val document = file?.viewProvider?.document
        val range = constant.textRange
        val startLine = document?.getLineNumber(range.startOffset)?.plus(1)
        val endLine = document?.getLineNumber(range.endOffset.coerceAtLeast(range.startOffset))?.plus(1)
        val signature = "${psiClass?.qualifiedName ?: psiClass?.name ?: goal.symbolName}.${constant.name}"
        return ResolutionOutcome.Resolved(
            resolverId = id,
            facts = listOf(
                EvidenceFact(
                    factId = "${goal.goalId}-fact-enum-constant",
                    level = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
                    resolverId = id,
                    symbolSignature = signature,
                    filePath = file?.virtualFile?.path ?: file?.name.orEmpty(),
                    startLine = startLine,
                    endLine = endLine,
                    claim = "已确认枚举常量 $signature 存在于真实源码。",
                    whyResolved = "Java PSI 精确解析到 PsiEnumConstant，非文本搜索结果。",
                ),
            ),
        )
    }
}
