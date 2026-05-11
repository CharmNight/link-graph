package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.ReadActionEvidenceResolver
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * 解析可静态证明的 Java 反射调用。
 */
class JavaReflectionResolver : ReadActionEvidenceResolver() {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-reflection-call"

    /**
     * 仅处理反射调用目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.REFLECTION_CALL
    }

    /**
     * 只接受编译期常量 class name 和 method name 的反射调用。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        val callsite = JavaPsiEvidenceSupport.resolveMethodCandidates(goal, context).methods.singleOrNull()
            ?: return unresolved(goal, "未找到唯一反射调用点方法。")
        val reflectedMethods = resolveReflectedMethods(goal, context, callsite)
        return when {
            reflectedMethods.isNotEmpty() -> ResolutionOutcome.Resolved(
                resolverId = id,
                facts = reflectedMethods.map { method ->
                    JavaPsiEvidenceSupport.methodFact(
                        goal = goal,
                        resolverId = id,
                        method = method,
                        claim = "已确认反射调用目标 ${methodSignature(method)}。",
                        whyResolved = "Class.forName 与 getMethod 参数均为编译期常量，PSI 精确解析到目标方法。",
                    )
                },
            )
            hasReflectionCalls(callsite) -> ResolutionOutcome.Unresolved(
                resolverId = id,
                reason = "反射调用存在，但 className 或 methodName 不是编译期常量。",
                requiredEvidence = listOf("补充反射 class/method 实际值、配置绑定或运行时 trace。"),
            )
            else -> unresolved(goal, "调用点中没有发现 Class.forName/getMethod 反射调用。")
        }
    }

    /**
     * 从调用点方法体中解析可静态确认的反射目标方法。
     */
    private fun resolveReflectedMethods(
        goal: EvidenceGoal,
        context: InvestigationContext,
        callsite: PsiMethod,
    ): List<PsiMethod> {
        val body = callsite.body ?: return emptyList()
        val classVariables = classForNameVariables(body)
        return PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .filter { call -> call.methodExpression.referenceName == "getMethod" }
            .mapNotNull { call ->
                val className = reflectedClassName(call.methodExpression.qualifierExpression, classVariables)
                    ?: return@mapNotNull null
                val methodName = stringLiteral(call.argumentList.expressions.firstOrNull())
                    ?: return@mapNotNull null
                val parameterTypes = call.argumentList.expressions
                    .drop(1)
                    .mapNotNull(::classObjectTypeName)
                val targetGoal = goal.copy(
                    ownerClassName = className,
                    methodName = methodName,
                    parameterTypes = parameterTypes,
                    returnType = null,
                )
                JavaPsiEvidenceSupport.resolveMethodCandidates(targetGoal, context).methods.singleOrNull()
            }
            .distinctBy(::methodSignature)
    }

    /**
     * 收集 `Class<?> type = Class.forName("...")` 形式的本地变量。
     */
    private fun classForNameVariables(body: com.intellij.psi.PsiCodeBlock): Map<String, String> {
        val variables = linkedMapOf<String, String>()
        PsiTreeUtil.collectElementsOfType(body, PsiDeclarationStatement::class.java).forEach { statement ->
            statement.declaredElements.filterIsInstance<PsiLocalVariable>().forEach { variable ->
                val initializer = variable.initializer as? PsiMethodCallExpression ?: return@forEach
                val className = classForNameLiteral(initializer) ?: return@forEach
                variables[variable.name] = className
            }
        }
        return variables
    }

    /**
     * 解析 getMethod 的 receiver 对应的 class name。
     */
    private fun reflectedClassName(
        qualifier: PsiExpression?,
        variables: Map<String, String>,
    ): String? {
        val call = qualifier as? PsiMethodCallExpression
        if (call != null) {
            return classForNameLiteral(call)
        }
        val qualifierText = qualifier?.text ?: return null
        return variables[qualifierText]
    }

    /**
     * 解析 `Class.forName("...")` 中的常量类名。
     */
    private fun classForNameLiteral(call: PsiMethodCallExpression): String? {
        if (call.methodExpression.referenceName != "forName") {
            return null
        }
        val qualifierText = call.methodExpression.qualifierExpression?.text
        if (qualifierText != "Class" && qualifierText != "java.lang.Class") {
            return null
        }
        return stringLiteral(call.argumentList.expressions.firstOrNull())
    }

    /**
     * 解析字符串字面量。
     */
    private fun stringLiteral(expression: PsiExpression?): String? {
        return (expression as? PsiLiteralExpression)?.value as? String
    }

    /**
     * 解析 `String.class` 这类 class object 参数。
     */
    private fun classObjectTypeName(expression: PsiExpression): String? {
        val classObject = expression as? PsiClassObjectAccessExpression ?: return null
        return JavaPsiEvidenceSupport.normalizeRequestedType(classObject.operand.type.canonicalText)
    }

    /**
     * 判断调用点是否包含反射调用形态。
     */
    private fun hasReflectionCalls(callsite: PsiMethod): Boolean {
        val body = callsite.body ?: return false
        return PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .any { call ->
                call.methodExpression.referenceName == "forName" ||
                    call.methodExpression.referenceName == "getMethod"
            }
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
