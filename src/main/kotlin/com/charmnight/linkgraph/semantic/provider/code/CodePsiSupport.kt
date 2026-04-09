package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticBoundary
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import org.jetbrains.kotlin.asJava.getAccessorLightMethods
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.references.readWriteAccess
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

internal data class CodeExecutionPlan(
    val roots: List<PsiElement>,
    val boundary: SemanticBoundary? = null,
)

internal data class KotlinPropertyAccessorSource(
    val owner: KtNamedDeclaration,
    val accessor: KtPropertyAccessor?,
)

internal fun classifyCodeSubject(method: PsiMethod): CodeSubjectKind {
    val navigationElement = method.navigationElement
    if (resolveKotlinPropertyAccessorSource(method) != null) {
        return CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR
    }
    return when (navigationElement) {
        is KtNamedFunction -> CodeSubjectKind.KOTLIN_FUNCTION
        is KtPrimaryConstructor -> CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR
        is KtSecondaryConstructor -> CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR
        else -> CodeSubjectKind.JAVA_METHOD
    }
}

internal fun resolveExecutionPlan(method: PsiMethod): CodeExecutionPlan {
    return when (classifyCodeSubject(method)) {
        CodeSubjectKind.JAVA_METHOD -> {
            val body = method.body
            if (body == null) {
                CodeExecutionPlan(
                    roots = emptyList(),
                    boundary = unsupportedBoundary(
                        method = method,
                        title = "Java 方法体缺失",
                        description = "当前方法没有可解析的方法体，无法继续抽取流程语义。",
                        kind = "JAVA_BODY_MISSING",
                    ),
                )
            } else {
                CodeExecutionPlan(listOf(body))
            }
        }

        CodeSubjectKind.KOTLIN_FUNCTION -> {
            val function = method.navigationElement as? KtNamedFunction
            val bodyExpression = function?.bodyExpression
            if (bodyExpression == null) {
                CodeExecutionPlan(
                    roots = emptyList(),
                    boundary = unsupportedBoundary(
                        method = method,
                        title = "Kotlin 函数体缺失",
                        description = "当前 Kotlin 函数没有可解析的函数体。",
                        kind = "KOTLIN_FUNCTION_BODY_MISSING",
                    ),
                )
            } else {
                CodeExecutionPlan(listOf(bodyExpression))
            }
        }

        CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR -> {
            val accessorSource = resolveKotlinPropertyAccessorSource(method)
            val root = accessorSource?.accessor?.bodyExpression
            if (root == null) {
                CodeExecutionPlan(
                    roots = emptyList(),
                    boundary = unsupportedBoundary(
                        method = method,
                        title = "Kotlin 属性访问器体缺失",
                        description = "当前 Kotlin 属性访问器没有可解析的访问器体。",
                        kind = "KOTLIN_ACCESSOR_BODY_MISSING",
                    ),
                )
            } else {
                CodeExecutionPlan(listOf(root))
            }
        }

        CodeSubjectKind.KOTLIN_PRIMARY_CONSTRUCTOR -> {
            val constructor = method.navigationElement as? KtPrimaryConstructor
            val owningClass = constructor?.let { PsiTreeUtil.getParentOfType(it, KtClass::class.java, false) }
            if (owningClass == null) {
                CodeExecutionPlan(
                    roots = emptyList(),
                    boundary = unsupportedBoundary(
                        method = method,
                        title = "Kotlin 主构造器宿主缺失",
                        description = "当前 Kotlin 主构造器缺少可解析的宿主类。",
                        kind = "KOTLIN_PRIMARY_CONSTRUCTOR_OWNER_MISSING",
                    ),
                )
            } else {
                CodeExecutionPlan(primaryConstructorExecutionRoots(owningClass))
            }
        }

        CodeSubjectKind.KOTLIN_SECONDARY_CONSTRUCTOR -> {
            val constructor = method.navigationElement as? KtSecondaryConstructor
            if (constructor == null) {
                CodeExecutionPlan(
                    roots = emptyList(),
                    boundary = unsupportedBoundary(
                        method = method,
                        title = "Kotlin 次构造器体缺失",
                        description = "当前 Kotlin 次构造器没有可解析的构造器体。",
                        kind = "KOTLIN_SECONDARY_CONSTRUCTOR_BODY_MISSING",
                    ),
                )
            } else {
                CodeExecutionPlan(
                    roots = listOfNotNull(
                        constructor.getDelegationCall().takeIf { call -> call.text.isNotBlank() },
                        constructor.bodyExpression,
                    ),
                )
            }
        }
    }
}

internal fun resolveKotlinPropertyAccessorSource(method: PsiMethod): KotlinPropertyAccessorSource? {
    val navigationElement = method.navigationElement
    if (navigationElement is KtPropertyAccessor) {
        return KotlinPropertyAccessorSource(
            owner = navigationElement.property,
            accessor = navigationElement,
        )
    }
    return when (navigationElement) {
        is KtProperty -> {
            val accessors = navigationElement.getAccessorLightMethods()
            when {
                accessors.getter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = navigationElement.getter,
                )
                accessors.setter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = navigationElement.setter,
                )
                else -> null
            }
        }

        is KtParameter -> {
            if (!navigationElement.hasValOrVar()) {
                return null
            }
            val accessors = navigationElement.getAccessorLightMethods()
            when {
                accessors.getter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = null,
                )
                accessors.setter?.name == method.name -> KotlinPropertyAccessorSource(
                    owner = navigationElement,
                    accessor = null,
                )
                else -> null
            }
        }

        else -> null
    }
}

internal fun resolveDownstreamTargetMethods(root: PsiElement): List<PsiMethod> {
    return when (root) {
        is KtElement -> resolveKotlinTargetMethods(root)
        else -> resolveJavaTargetMethods(root)
    }
}

internal fun resolveCallers(
    method: PsiMethod,
    limit: Int? = null,
): List<PsiMethod> {
    val scope = GlobalSearchScope.projectScope(method.project)
    val callers = linkedMapOf<String, PsiMethod>()
    val targetMethods = linkedSetOf<PsiMethod>().apply {
        add(method)
        addAll(method.findSuperMethods())
        addAll(method.findDeepestSuperMethods())
    }
    val effectiveLimit = limit?.coerceAtLeast(0)

    targetMethods.forEach { targetMethod ->
        var shouldContinue = true
        ReferencesSearch.search(targetMethod, scope).forEach(
            Processor { reference ->
                if (!shouldContinue) {
                    return@Processor false
                }
                val callerMethod = callerMethodOf(reference.element) ?: return@Processor true
                val callerSignature = methodSignature(callerMethod)
                if (callerSignature != methodSignature(method)) {
                    callers[callerSignature] = callerMethod
                    if (effectiveLimit != null && callers.size >= effectiveLimit) {
                        shouldContinue = false
                    }
                }
                shouldContinue
            },
        )
        if (!shouldContinue) {
            return@forEach
        }
    }

    return callers.values.sortedBy(::methodSignature)
}

internal fun concreteTargetMethods(method: PsiMethod): List<PsiMethod> {
    if (!needsImplementationResolution(method)) {
        return listOf(method)
    }
    if (!isProjectSourceMethod(method)) {
        return listOf(method)
    }
    val scope = GlobalSearchScope.projectScope(method.project)
    val overrides = OverridingMethodsSearch.search(method, scope, true)
        .findAll()
        .filter { candidate -> candidate.containingClass?.let(::isConcreteClass) == true }
        .sortedBy(::methodSignature)
    return overrides.ifEmpty { listOf(method) }
}

internal fun psiMethodOf(element: PsiElement): PsiMethod? {
    return when (element) {
        is PsiMethod -> element
        is KtPropertyAccessor -> {
            val accessors = element.property.getAccessorLightMethods()
            if (element.isGetter) accessors.getter else accessors.setter
        }

        else -> {
            element.getRepresentativeLightMethod()
                ?: element.toLightMethods().firstOrNull()
        }
    }
}

internal fun isProjectSourceMethod(method: PsiMethod): Boolean {
    val virtualFile = method.containingFile?.virtualFile ?: return false
    return ProjectFileIndex.getInstance(method.project).isInContent(virtualFile)
}

private fun resolveJavaTargetMethods(root: PsiElement): List<PsiMethod> {
    val callExpressions = buildList<PsiElement> {
        if (root is PsiMethodCallExpression || root is PsiNewExpression) {
            add(root)
        }
        addAll(PsiTreeUtil.collectElementsOfType(root, PsiMethodCallExpression::class.java))
        addAll(PsiTreeUtil.collectElementsOfType(root, PsiNewExpression::class.java))
    }
    return callExpressions
        .mapNotNull { expression ->
            when (expression) {
                is PsiMethodCallExpression -> expression.resolveMethod()
                is PsiNewExpression -> expression.resolveMethod()
                else -> null
            }
        }
        .flatMap(::concreteTargetMethods)
        .filter(::isProjectSourceMethod)
        .distinctBy(::methodSignature)
}

private fun resolveKotlinTargetMethods(root: KtElement): List<PsiMethod> {
    val methods = linkedMapOf<String, PsiMethod>()
    val callElements = buildList<KtCallElement> {
        if (root is KtCallElement) {
            add(root)
        }
        addAll(root.collectDescendantsOfType<KtCallExpression>())
    }
    val simpleNames = buildList<KtSimpleNameExpression> {
        if (root is KtSimpleNameExpression) {
            add(root)
        }
        addAll(root.collectDescendantsOfType<KtSimpleNameExpression>())
    }

    callElements.forEach { callElement ->
        val targetMethod = callElement.calleeExpression
            ?.mainReference
            ?.resolve()
            ?.let(::psiMethodOf)
            ?: return@forEach
        concreteTargetMethods(targetMethod)
            .filter(::isProjectSourceMethod)
            .forEach { method -> methods.putIfAbsent(methodSignature(method), method) }
    }

    simpleNames.forEach { expression ->
        resolveKotlinPropertyAccessorTargets(expression).forEach { method ->
            methods.putIfAbsent(methodSignature(method), method)
        }
    }

    return methods.values.toList()
}

private fun resolveKotlinPropertyAccessorTargets(expression: KtSimpleNameExpression): List<PsiMethod> {
    val declaration = expression.mainReference?.resolve() as? KtNamedDeclaration ?: return emptyList()
    if (!supportsPropertyAccessors(declaration)) {
        return emptyList()
    }
    val accessors = declaration.getAccessorLightMethods()
    val access = expression.readWriteAccess(false)
    return buildList {
        if (access.isRead) {
            accessors.getter?.let(::add)
        }
        if (access.isWrite) {
            accessors.setter?.let(::add)
        }
    }.filter(::isProjectSourceMethod)
}

private fun supportsPropertyAccessors(declaration: KtNamedDeclaration): Boolean {
    return when (declaration) {
        is KtProperty -> true
        is KtParameter -> declaration.hasValOrVar()
        else -> false
    }
}

private fun callerMethodOf(referenceElement: PsiElement): PsiMethod? {
    PsiTreeUtil.getParentOfType(referenceElement, PsiMethod::class.java, false)
        ?.let { return it }
    PsiTreeUtil.getParentOfType(referenceElement, KtNamedFunction::class.java, false)
        ?.let(::psiMethodOf)
        ?.let { return it }
    PsiTreeUtil.getParentOfType(referenceElement, KtPropertyAccessor::class.java, false)
        ?.let(::psiMethodOf)
        ?.let { return it }
    PsiTreeUtil.getParentOfType(referenceElement, KtSecondaryConstructor::class.java, false)
        ?.let(::psiMethodOf)
        ?.let { return it }
    PsiTreeUtil.getParentOfType(referenceElement, KtPrimaryConstructor::class.java, false)
        ?.let(::psiMethodOf)
        ?.let { return it }
    return null
}

private fun primaryConstructorExecutionRoots(owningClass: KtClass): List<KtElement> {
    val bodyRoots = buildList<KtElement> {
        owningClass.superTypeListEntries
            .filterIsInstance<KtSuperTypeCallEntry>()
            .forEach(::add)
        owningClass.body?.declarations.orEmpty().forEach { declaration ->
            when (declaration) {
                is KtProperty -> declaration.initializer?.let(::add)
                is KtAnonymousInitializer -> declaration.body?.let(::add)
            }
        }
    }
    return bodyRoots.sortedBy { root -> root.textRange.startOffset }
}

private fun unsupportedBoundary(
    method: PsiMethod,
    title: String,
    description: String,
    kind: String,
): SemanticBoundary {
    return SemanticBoundary(
        title = title,
        reason = "$description signature=${methodSignature(method)}",
        kind = kind,
    )
}

private fun needsImplementationResolution(method: PsiMethod): Boolean {
    val owner = method.containingClass ?: return false
    return owner.isInterface || method.hasModifierProperty(PsiModifier.ABSTRACT)
}

private fun isConcreteClass(psiClass: PsiClass): Boolean {
    return !psiClass.isInterface && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
}
