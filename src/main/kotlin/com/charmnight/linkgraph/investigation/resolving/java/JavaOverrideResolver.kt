package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.ReadActionEvidenceResolver
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * 使用 PSI 重写搜索解析接口或抽象方法的真实实现。
 */
class JavaOverrideResolver : ReadActionEvidenceResolver() {
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
    ): ResolutionOutcome {
        val baseCandidates = JavaPsiEvidenceSupport.resolveMethodCandidates(goal, context)
        val baseMethod = baseCandidates.methods.singleOrNull()
            ?: return unresolvedBase(goal, baseCandidates)
        val implementations = concreteImplementations(baseMethod)
        return when (implementations.size) {
            0 -> ResolutionOutcome.Unresolved(
                resolverId = id,
                reason = "未找到 ${methodSignature(baseMethod)} 的项目内具体实现。",
                requiredEvidence = listOf("补充实现类源码、Spring 注入绑定或运行时 receiver 类型。"),
            )
            1 -> ResolutionOutcome.Resolved(
                resolverId = id,
                facts = listOf(
                    JavaPsiEvidenceSupport.methodFact(
                        goal = goal,
                        resolverId = id,
                        method = implementations.single(),
                        claim = "已确认 ${methodSignature(baseMethod)} 的唯一项目内实现是 ${methodSignature(implementations.single())}。",
                        whyResolved = "PSI OverridingMethodsSearch 找到唯一具体实现，未使用文本搜索。",
                    ),
                ),
            )
            else -> ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = implementations.map { method ->
                    JavaPsiEvidenceSupport.methodCandidate(
                        goal = goal,
                        resolverId = id,
                        method = method,
                        reason = "接口或抽象方法存在多个具体实现，无法确认运行时 receiver。",
                    )
                },
                reason = "${methodSignature(baseMethod)} 存在多个具体实现。",
                requiredEvidence = listOf("补充运行时 receiver 类型、Spring Bean 注入绑定或调用 trace。"),
            )
        }
    }

    /**
     * 构造基础方法无法唯一解析时的结果。
     */
    private fun unresolvedBase(
        goal: EvidenceGoal,
        baseCandidates: MethodCandidates,
    ): ResolutionOutcome {
        if (baseCandidates.methods.size > 1) {
            return ResolutionOutcome.MultipleCandidates(
                resolverId = id,
                candidates = baseCandidates.methods.map { method ->
                    JavaPsiEvidenceSupport.methodCandidate(
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
     */
    private fun concreteImplementations(baseMethod: PsiMethod): List<PsiMethod> {
        val scope = GlobalSearchScope.projectScope(baseMethod.project)
        val indexed = OverridingMethodsSearch.search(baseMethod, scope, true)
            .findAll()
            .filter { method -> method.containingClass?.let(::isConcreteClass) == true }
            .filter(JavaPsiEvidenceSupport::isProjectSourceMethod)
            .sortedBy(::methodSignature)
        if (indexed.isNotEmpty()) {
            return indexed
        }
        val ownerClass = baseMethod.containingClass ?: return emptyList()
        val psiManager = PsiManager.getInstance(baseMethod.project)
        return FilenameIndex.getAllFilesByExt(baseMethod.project, "java", scope)
            .mapNotNull(psiManager::findFile)
            .flatMap { file -> PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java) }
            .filter(::isConcreteClass)
            .filter { psiClass -> psiClass.isInheritor(ownerClass, true) }
            .flatMap { psiClass -> psiClass.findMethodsByName(baseMethod.name, false).toList() }
            .filter { method ->
                JavaPsiEvidenceSupport.matchesRequestedSignature(
                    method = method,
                    parameterTypes = baseMethod.parameterList.parameters.map { parameter ->
                        JavaPsiEvidenceSupport.normalizeType(parameter.type)
                    },
                    returnType = JavaPsiEvidenceSupport.normalizeType(baseMethod.returnType),
                )
            }
            .filter(JavaPsiEvidenceSupport::isProjectSourceMethod)
            .sortedBy(::methodSignature)
    }

    /**
     * 判断类是否为可实例化的具体类。
     */
    private fun isConcreteClass(psiClass: PsiClass): Boolean {
        return !psiClass.isInterface && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
    }
}
