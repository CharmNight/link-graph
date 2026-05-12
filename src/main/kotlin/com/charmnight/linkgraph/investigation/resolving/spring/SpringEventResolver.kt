package com.charmnight.linkgraph.investigation.resolving.spring

import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.ReadActionEvidenceResolver
import com.charmnight.linkgraph.investigation.resolving.java.JavaPsiEvidenceSupport
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * 使用 PSI 和 Spring 注解规则解析事件发布与监听关系。
 */
class SpringEventResolver : ReadActionEvidenceResolver() {
    /** 保存解析器稳定标识。 */
    override val id: String = "spring-event"
    private val springEventListenerAnnotations = setOf(
        "org.springframework.context.event.EventListener",
        "org.springframework.transaction.event.TransactionalEventListener",
    )

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
    ): ResolutionOutcome {
        val eventClasses = eventClasses(goal, context)
        if (eventClasses.isEmpty()) {
            return unresolved(goal, "未能确认 Spring Event 类型。")
        }
        val listenerMethods = eventClasses.flatMap { eventClass ->
            listenersForEvent(context, eventClass)
        }.distinctBy(::methodSignature)
        if (listenerMethods.isEmpty()) {
            return unresolved(goal, "未找到匹配 Spring Event 监听器。")
        }
        return ResolutionOutcome.Resolved(
            resolverId = id,
            facts = listenerMethods.map { method ->
                JavaPsiEvidenceSupport.methodFact(
                    goal = goal,
                    resolverId = id,
                    method = method,
                    level = EvidenceLevel.DIRECT_FRAMEWORK_RESOLVED,
                    claim = "已确认 Spring Event 监听器 ${methodSignature(method)}。",
                    whyResolved = "根据 publishEvent 事件类型与 @EventListener/@TransactionalEventListener 参数类型匹配。",
                )
            },
        )
    }

    /**
     * 从目标事件名和发布点方法体中收集事件类型。
     */
    private fun eventClasses(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): List<PsiClass> {
        val classes = linkedMapOf<String, PsiClass>()
        goal.eventClassName
            ?.takeIf(String::isNotBlank)
            ?.let { className -> JavaPsiEvidenceSupport.resolveClassCandidates(context, className) }
            .orEmpty()
            .forEach { psiClass -> classes[psiClass.qualifiedName ?: psiClass.name.orEmpty()] = psiClass }
        val callsite = JavaPsiEvidenceSupport.resolveMethodCandidates(goal, context).methods.singleOrNull()
        callsite?.let { method ->
            publishedEventClasses(method).forEach { psiClass ->
                classes[psiClass.qualifiedName ?: psiClass.name.orEmpty()] = psiClass
            }
        }
        return classes.values.toList()
    }

    /**
     * 从 `publishEvent(...)` 调用中解析事件类型。
     */
    private fun publishedEventClasses(method: PsiMethod): List<PsiClass> {
        val body = method.body ?: return emptyList()
        return PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .filter { call -> call.methodExpression.referenceName == "publishEvent" }
            .mapNotNull { call ->
                val eventExpression = call.argumentList.expressions.firstOrNull() ?: return@mapNotNull null
                when (eventExpression) {
                    is PsiNewExpression -> (eventExpression.classReference?.resolve() as? PsiClass)
                        ?: eventExpression.classReference?.referenceName
                            ?.let { shortName -> classInCallsitePackage(method, shortName) }
                    else -> (eventExpression.type as? PsiClassType)?.resolve()
                }
            }
            .filter { psiClass -> psiClass.qualifiedName != null }
    }

    /**
     * 使用发布点所在 package 补全事件短类名。
     */
    private fun classInCallsitePackage(
        method: PsiMethod,
        shortName: String,
    ): PsiClass? {
        val packageName = (method.containingFile as? PsiJavaFile)?.packageName.orEmpty()
        if (packageName.isBlank()) {
            return null
        }
        return com.intellij.psi.JavaPsiFacade.getInstance(method.project)
            .findClass("$packageName.$shortName", GlobalSearchScope.projectScope(method.project))
    }

    /**
     * 查找能够接收指定事件类型的监听器方法。
     */
    private fun listenersForEvent(
        context: InvestigationContext,
        eventClass: PsiClass,
    ): List<PsiMethod> {
        val scope = GlobalSearchScope.projectScope(context.project)
        val psiManager = PsiManager.getInstance(context.project)
        return FilenameIndex.getAllFilesByExt(context.project, "java", scope)
            .mapNotNull(psiManager::findFile)
            .filterIsInstance<PsiJavaFile>()
            .flatMap { file -> PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java) }
            .filter(JavaPsiEvidenceSupport::isProjectSourceMethod)
            .filter { method -> isSpringEventListener(method) }
            .filter { method -> acceptsEvent(method, eventClass) }
            .sortedBy(::methodSignature)
    }

    /**
     * 判断方法是否为 Spring 事件监听器。
     */
    private fun isSpringEventListener(method: PsiMethod): Boolean {
        return method.annotations.any { annotation ->
            val annotationName = annotation.qualifiedName ?: annotation.nameReferenceElement?.referenceName ?: return@any false
            annotationName in springEventListenerAnnotations
        }
    }

    /**
     * 判断监听器参数是否可以接收指定事件。
     */
    private fun acceptsEvent(
        method: PsiMethod,
        eventClass: PsiClass,
    ): Boolean {
        val parameterType = method.parameterList.parameters.firstOrNull()?.type ?: return false
        val parameterClass = (parameterType as? PsiClassType)?.resolve()
        if (parameterClass != null) {
            return parameterClass == eventClass || eventClass.isInheritor(parameterClass, true)
        }
        val parameterText = JavaPsiEvidenceSupport.normalizeType(parameterType)
        val eventName = eventClass.qualifiedName ?: eventClass.name.orEmpty()
        return parameterText == eventName || parameterText.substringAfterLast('.') == eventName.substringAfterLast('.')
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
