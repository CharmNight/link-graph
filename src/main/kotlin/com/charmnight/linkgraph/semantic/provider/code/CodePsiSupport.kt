package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.semantic.model.SemanticBoundary
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
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

/**
 * 代码执行计划：描述某个方法的"可执行根节点"集合以及（若失败时）对应的不可解析边界。
 * Java 方法直接以方法体作为根；Kotlin 主构造器的根是父类委托调用 + 初始化块；其它主体类似。
 */
internal data class CodeExecutionPlan(
    val roots: List<PsiElement>,
    val boundary: SemanticBoundary? = null,
)

/**
 * Kotlin 属性访问器来源信息：当 PSI 方法实际对应 Kotlin 属性的 getter/setter 时，
 * 通过此结构保留对原属性（owner）与具体访问器（accessor，可为 null）的引用。
 */
internal data class KotlinPropertyAccessorSource(
    val owner: KtNamedDeclaration,
    val accessor: KtPropertyAccessor?,
)

/**
 * 已解析的下游目标方法，附带置信度与分派类型。
 * 分派类型用于区分静态绑定、接口分派、抽象分派、具体接收者等场景，影响下游分析的精度。
 */
internal data class ResolvedDownstreamTargetMethod(
    val method: PsiMethod,
    val confidence: JvmRelationConfidence = JvmRelationConfidence.PROVEN,
    val dispatchKind: String = "STATIC",
)

/**
 * 根据 PSI 方法的 navigationElement 判定其对应的代码主体种类，
 * 用于在 Java 方法、Kotlin 函数、构造器、属性访问器之间分派处理逻辑。
 */
internal fun classifyCodeSubject(method: PsiMethod): CodeSubjectKind {
    val navigationElement = method.navigationElement
    // 优先识别 Kotlin 属性访问器场景，因为 getter/setter 在 JVM 层呈现为普通方法
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

/**
 * 解析方法对应的执行计划：找出代码主体作为后续下游分析的根节点。
 * 各类主体在缺失可解析体时返回一个不可解析边界，告知调用方为何无法继续分析。
 */
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
            // 主构造器本身没有显式方法体，需要将父类委托调用 + 类初始化块作为执行根
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
                // 次构造器可同时包含父类/主构造器委托调用与自身方法体，二者都作为根
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

/**
 * 识别给定方法是否对应 Kotlin 属性的访问器（getter/setter）。
 * 同时支持独立的 KtPropertyAccessor、KtProperty 自动合成的访问器，以及 KtParameter 的访问器。
 */
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
            // 通过方法名与合成访问器名比对，判断当前方法是 getter 还是 setter
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
            // 仅 val/var 形参才会合成属性访问器（例如构造器参数生成的属性）
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

/** 简化调用入口：返回所有下游目标方法列表（不含分派与置信度信息）。 */
internal fun resolveDownstreamTargetMethods(
    root: PsiElement,
    includeNestedLambdas: Boolean = true,
): List<PsiMethod> =
    resolveDownstreamTargets(root, includeNestedLambdas).map(ResolvedDownstreamTargetMethod::method)

/**
 * 从给定执行根节点抽取所有下游目标方法（被调用的方法）。
 * 按 Kotlin / Java 分派不同的解析逻辑，返回带置信度的目标方法列表。
 */
internal fun resolveDownstreamTargets(
    root: PsiElement,
    includeNestedLambdas: Boolean = true,
): List<ResolvedDownstreamTargetMethod> {
    return when (root) {
        is KtElement -> resolveKotlinTargetMethods(root)
            .map { method -> ResolvedDownstreamTargetMethod(method) }
        else -> resolveJavaTargetMethods(root, includeNestedLambdas)
    }
}

/**
 * 查找调用指定方法的所有调用者，覆盖其所有父接口/父类上的同名声明。
 * 返回结果按方法签名排序、去重，并支持限制结果数量。
 */
internal fun resolveCallers(
    method: PsiMethod,
    limit: Int? = null,
): List<PsiMethod> {
    val scope = GlobalSearchScope.projectScope(method.project)
    // 用方法签名作为去重键，避免重载或继承链上重复计入
    val callers = linkedMapOf<String, PsiMethod>()
    // 待检索的目标方法集合：包括方法本身与其所有父接口/父类上的同名声明
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
                // 排除被检索方法自身作为调用者（如递归自调用）
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

/**
 * 解析接口/抽象方法在项目源码中的所有具体实现。
 * 非抽象方法直接返回自身；项目源码外的方法无法可靠枚举实现，也直接返回自身。
 */
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
        // 仅保留位于具体类中的重写，避免再次引入抽象层
        .filter { candidate -> candidate.containingClass?.let(::isConcreteClass) == true }
        .sortedBy(::methodSignature)
    return overrides.ifEmpty { listOf(method) }
}

/** 把任意 PSI 元素统一转换为对应的 PsiMethod（兼容 Java/Kotlin 的多种声明形式）。 */
internal fun psiMethodOf(element: PsiElement): PsiMethod? {
    return when (element) {
        is PsiMethod -> element
        // Kotlin 属性访问器：根据是 getter 还是 setter 取对应合成方法
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

/** 判断方法是否属于项目源码（位于项目内容根内），用于过滤外部库调用。 */
internal fun isProjectSourceMethod(method: PsiMethod): Boolean {
    val virtualFile = method.containingFile?.virtualFile ?: return false
    return ProjectFileIndex.getInstance(method.project).isInContent(virtualFile)
}

/**
 * 扫描 Java 方法体中的所有方法调用与 new 表达式，解析出目标方法。
 * 对接口/抽象方法的调用会尝试通过具体接收者类型定位到真实实现。
 */
private fun resolveJavaTargetMethods(
    root: PsiElement,
    includeNestedLambdas: Boolean,
): List<ResolvedDownstreamTargetMethod> {
    // 收集所有调用表达式：根节点本身若是调用也纳入，再递归收集子树
    val callExpressions = buildList<PsiElement> {
        if (root is PsiMethodCallExpression || root is PsiNewExpression) {
            add(root)
        }
        addAll(PsiTreeUtil.collectElementsOfType(root, PsiMethodCallExpression::class.java))
        addAll(PsiTreeUtil.collectElementsOfType(root, PsiNewExpression::class.java))
    }
    return callExpressions
        // 可选地剔除嵌套在 lambda 内部的调用，避免把跨闭包的调用算作直接下游
        .filter { expression -> includeNestedLambdas || !isNestedInsideJavaLambda(expression, root) }
        .flatMap { expression ->
            when (expression) {
                is PsiMethodCallExpression -> methodCallTargets(expression)
                is PsiNewExpression -> listOfNotNull(expression.resolveMethod())
                else -> emptyList()
            }
        }
        .map { target ->
            when (target) {
                is ResolvedDownstreamTargetMethod -> target
                is PsiMethod -> ResolvedDownstreamTargetMethod(target)
                else -> null
            }
        }
        .filterNotNull()
        // 仅保留项目源码内的方法，过滤掉库与 JDK 的调用
        .filter { target -> isProjectSourceMethod(target.method) }
        .preferStrongestTargets()
}

/**
 * 解析单条 Java 方法调用表达式的目标方法。
 * 当目标声明在抽象类型上时，尝试根据接收者表达式的具体类型精确定位唯一实现，
 * 否则将该调用标注为运行时分派并保留抽象层方法。
 */
private fun methodCallTargets(expression: PsiMethodCallExpression): List<ResolvedDownstreamTargetMethod> {
    val resolved = expression.resolveMethod()
    if (resolved != null) {
        val owner = resolved.containingClass
        if (owner != null && needsImplementationResolution(resolved)) {
            // 调用方为抽象/接口，尝试通过具体接收者类型反查真实实现
            val concreteReceiverClass = concreteReceiverClass(expression)
            val concreteTargets = concreteReceiverClass
                ?.let { receiverClass ->
                    implementationMethods(
                        qualifierClass = receiverClass,
                        methodName = resolved.name,
                        argumentTypes = expression.argumentList.expressions.map(PsiExpression::getType),
                        project = expression.project,
                        referenceMethod = resolved,
                    )
                }
                .orEmpty()
            if (concreteTargets.size == 1) {
                return concreteTargets.map { method ->
                    ResolvedDownstreamTargetMethod(
                        method = method,
                        confidence = JvmRelationConfidence.PROVEN,
                        dispatchKind = "CONCRETE_RECEIVER",
                    )
                }
            }
            // 无法唯一确定实现时，保留原抽象方法并标注为运行时分派
            return listOf(
                ResolvedDownstreamTargetMethod(
                    method = resolved,
                    confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                    dispatchKind = if (owner.isInterface) "INTERFACE_DISPATCH" else "ABSTRACT_DISPATCH",
                ),
            )
        }
        return listOf(ResolvedDownstreamTargetMethod(resolved))
    }
    // 未解析成功时退化为根据方法名 + 接收者类型 + 参数列表搜索
    val methodName = expression.methodExpression.referenceName ?: return emptyList()
    val qualifierClass = concreteReceiverClass(expression)
        ?: (expression.methodExpression.qualifierExpression?.type as? com.intellij.psi.PsiClassType)
            ?.resolve()
            ?.takeUnless { owner -> needsImplementationResolution(owner) }
        ?: return emptyList()
    return implementationMethods(
        qualifierClass = qualifierClass,
        methodName = methodName,
        argumentTypes = expression.argumentList.expressions.map(PsiExpression::getType),
        project = expression.project,
    ).map { method ->
        ResolvedDownstreamTargetMethod(
            method = method,
            confidence = JvmRelationConfidence.PROVEN,
            dispatchKind = "CONCRETE_RECEIVER",
        )
    }
}

/**
 * 在给定类（或其具体子类）中按方法名、参数个数、参数兼容性筛选候选方法。
 * 若 [qualifierClass] 是接口或抽象类，则枚举其在项目内的所有具体实现类。
 */
private fun implementationMethods(
    qualifierClass: PsiClass,
    methodName: String,
    argumentTypes: List<PsiType?>,
    project: com.intellij.openapi.project.Project,
    referenceMethod: PsiMethod? = null,
): List<PsiMethod> {
    val scope = GlobalSearchScope.projectScope(project)
    val classes = if (qualifierClass.isInterface || qualifierClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
        ClassInheritorsSearch.search(qualifierClass, scope, true)
            .findAll()
            .filter(::isConcreteClass)
    } else {
        listOf(qualifierClass)
    }
    return classes
        .flatMap { candidate ->
            candidate.methods.filter { method ->
                method.name == methodName &&
                    method.parameterList.parametersCount == argumentTypes.size &&
                    (referenceMethod == null || overridesReferenceMethod(method, referenceMethod)) &&
                    parametersAcceptArguments(method, argumentTypes)
            }
        }
        .sortedBy(::methodSignature)
}

/**
 * 推断方法调用接收者表达式的具体类型，用于精确解析接口/抽象类型上的方法调用。
 * 静态类型已是具体类时直接返回；否则尝试解析变量初始值中的 new 表达式。
 */
private fun concreteReceiverClass(expression: PsiMethodCallExpression): PsiClass? {
    val qualifier = expression.methodExpression.qualifierExpression ?: return null
    // 优先用静态类型，前提是该类型本身是具体类
    val qualifierClass = (qualifier.type as? com.intellij.psi.PsiClassType)
        ?.resolve()
        ?.takeUnless { candidate -> needsImplementationResolution(candidate) }
    if (qualifierClass != null) {
        return qualifierClass
    }
    // 退化方案：如果接收者是一个变量，看其初始化表达式能否确定具体类型
    val resolvedVariable = (qualifier as? PsiReferenceExpression)?.resolve() as? PsiVariable
        ?: return null
    return concreteClassFromInitializer(resolvedVariable.initializer)
}

/** 从初始化表达式中推断变量引用的具体类型（如 new SomeImpl() 形式）。 */
private fun concreteClassFromInitializer(expression: PsiExpression?): PsiClass? {
    val unwrapped = expression?.unwrapExpression() ?: return null
    val newExpression = unwrapped as? PsiNewExpression ?: return null
    val candidate = newExpression.classReference?.resolve() as? PsiClass ?: return null
    return candidate.takeUnless { owner -> needsImplementationResolution(owner) }
}

/** 反复剥离外层括号与强制类型转换，得到表达式真正的核心部分。 */
private fun PsiExpression.unwrapExpression(): PsiExpression {
    var current = this
    while (true) {
        current = when (current) {
            is PsiParenthesizedExpression -> current.expression ?: return current
            is PsiTypeCastExpression -> current.operand ?: return current
            else -> return current
        }
    }
}

/** 判断类是否为接口或抽象类，需要进一步解析具体实现。 */
private fun needsImplementationResolution(owner: PsiClass): Boolean =
    owner.isInterface || owner.hasModifierProperty(PsiModifier.ABSTRACT)

/** 判断方法是否覆写了引用方法（自身相同或位于其父方法链上）。 */
private fun overridesReferenceMethod(
    method: PsiMethod,
    referenceMethod: PsiMethod,
): Boolean {
    if (method == referenceMethod) {
        return true
    }
    val superMethods = method.findSuperMethods().asSequence() + method.findDeepestSuperMethods().asSequence()
    return superMethods.any { superMethod -> superMethod == referenceMethod }
}

/** 检查方法参数列表是否能兼容给定的实参类型集合（实参为 null 视为通配）。 */
private fun parametersAcceptArguments(
    method: PsiMethod,
    argumentTypes: List<PsiType?>,
): Boolean {
    val parameters = method.parameterList.parameters
    if (parameters.size != argumentTypes.size) {
        return false
    }
    return parameters.zip(argumentTypes).all { (parameter, argumentType) ->
        argumentType == null || TypeConversionUtil.isAssignable(parameter.type, argumentType)
    }
}

/**
 * 同签名的多个候选目标择优：优先选择置信度最高的（ordinal 最小），
 * 然后按方法签名稳定排序，避免下游结果在不同次解析间出现顺序漂移。
 */
private fun List<ResolvedDownstreamTargetMethod>.preferStrongestTargets(): List<ResolvedDownstreamTargetMethod> =
    groupBy { target -> methodSignature(target.method) }
        .values
        .map { targets ->
            targets.minWith(
                compareBy<ResolvedDownstreamTargetMethod> { target -> target.confidence.ordinal }
                    .thenBy { target -> methodSignature(target.method) },
            )
        }
        .sortedBy { target -> methodSignature(target.method) }

/** 判断某个 PSI 元素是否嵌套在 Java lambda 表达式内（且不是根节点本身）。 */
private fun isNestedInsideJavaLambda(
    element: PsiElement,
    root: PsiElement,
): Boolean {
    val lambdaAncestor = PsiTreeUtil.getParentOfType(element, PsiLambdaExpression::class.java, false) ?: return false
    return lambdaAncestor != root && PsiTreeUtil.isAncestor(root, lambdaAncestor, true)
}

/**
 * 从 Kotlin 代码根节点抽取下游目标方法：覆盖普通函数调用与属性访问器引用。
 * 结果按方法签名去重，使用 LinkedHashMap 保留首次出现顺序。
 */
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

    // 通过 callee 的 mainReference 解析被调函数，再展开到具体实现
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

    // 简单名引用可能指向 Kotlin 属性，需要把读写访问转换为对应的 getter/setter 调用
    simpleNames.forEach { expression ->
        resolveKotlinPropertyAccessorTargets(expression).forEach { method ->
            methods.putIfAbsent(methodSignature(method), method)
        }
    }

    return methods.values.toList()
}

/** 解析 Kotlin 属性简单名引用对应的访问器方法（按读写访问情况分别包含 getter/setter）。 */
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

/** 判断声明是否能合成属性访问器：Kotlin 属性或带 val/var 的构造器参数支持。 */
private fun supportsPropertyAccessors(declaration: KtNamedDeclaration): Boolean {
    return when (declaration) {
        is KtProperty -> true
        is KtParameter -> declaration.hasValOrVar()
        else -> false
    }
}

/**
 * 从任意 PSI 引用元素定位其所属的方法上下文。
 * 按优先级尝试 Java/Kotlin 函数、属性访问器、次/主构造器，找到任一即返回。
 */
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

/**
 * 收集 Kotlin 主构造器对应的执行根：父类委托调用 + 类体内属性的初始化表达式 + 匿名初始化块。
 * 按文本起始位置排序，保证下游分析时遵循源码出现顺序。
 */
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

/** 构造一个不可解析边界对象，统一带上方法签名信息以便排查问题。 */
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

/** 判断方法是否需要进一步解析具体实现：接口方法或 abstract 方法。 */
private fun needsImplementationResolution(method: PsiMethod): Boolean {
    val owner = method.containingClass ?: return false
    return owner.isInterface || method.hasModifierProperty(PsiModifier.ABSTRACT)
}

/** 判断类是否为具体类（非接口且非抽象）。 */
private fun isConcreteClass(psiClass: PsiClass): Boolean {
    return !psiClass.isInterface && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
}
