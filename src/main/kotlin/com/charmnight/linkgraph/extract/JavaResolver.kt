package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.serviceContainer.AlreadyDisposedException
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
 * Java PSI 到图模型的基础映射器。
 * 这里既负责方法/类节点的字段抽取，也负责普通 Java 调用链与接口实现关系的解析。
 */
class JavaResolver {
    fun methodKey(method: PsiMethod): String = methodSignature(method, qualifiedOwner = true)

    fun methodNode(method: PsiMethod, flow: String? = null): GraphNode {
        val owner = method.containingClass?.name ?: "<anonymous>"
        val metadata = buildMap {
            if (!flow.isNullOrBlank()) {
                put("flow", flow)
            }
        }
        return GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodKey(method)),
            type = NodeType.METHOD,
            title = "$owner.${method.name}",
            location = locationOf(method),
            signature = methodSignature(method, qualifiedOwner = true),
            inputs = method.parameterList.parameters.map(::typeText),
            outputs = listOf(typeText(method.returnType)),
            doc = methodDoc(method),
            sourceKind = "JAVA_METHOD",
            metadata = metadata,
        )
    }

    fun classNode(psiClass: PsiClass): GraphNode {
        val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>"
        return GraphNode(
            id = GraphNode.stableId(NodeType.CLASS, qualifiedName),
            type = NodeType.CLASS,
            title = psiClass.name ?: qualifiedName,
            location = locationOf(psiClass),
            signature = qualifiedName,
            sourceKind = "JAVA_CLASS",
        )
    }

    fun resolveCalls(method: PsiMethod): List<ResolvedMethodCall> = resolveCallGraph(method).calls

    fun resolveCallGraph(method: PsiMethod): ResolvedMethodFlow {
        val semantic = MethodSemanticDecoder.decode(method)
        when (semantic.kind) {
            MethodSemanticKind.KOTLIN_NAMED_FUNCTION,
            MethodSemanticKind.KOTLIN_PROPERTY_ACCESSOR,
            MethodSemanticKind.KOTLIN_PRIMARY_CONSTRUCTOR,
            MethodSemanticKind.KOTLIN_SECONDARY_CONSTRUCTOR -> return resolveKotlinCallGraph(method, semantic)
            MethodSemanticKind.JAVA_METHOD,
            MethodSemanticKind.OTHER -> Unit
        }
        val body = method.body ?: run {
            if (semantic.domain != MethodSemanticDomain.JAVA) {
                logger.info("静态下游提取跳过: reason=noBody, ${semantic.debugInfo(methodKey(method))}")
            }
            return ResolvedMethodFlow(boundary = semantic.explicitCurrentMethodBoundary(methodKey(method)))
        }
        val resolvedFlow = resolveJavaCallGraph(method, body)
        if (resolvedFlow.calls.isEmpty() && semantic.domain != MethodSemanticDomain.JAVA) {
            logger.info("静态下游提取结果为空: ${semantic.debugInfo(methodKey(method))}")
        }
        return resolvedFlow
    }

    private fun resolveKotlinCallGraph(
        method: PsiMethod,
        semantic: DecodedMethodSemantic,
    ): ResolvedMethodFlow {
        val methodSignature = methodKey(method)
        val executionPlan = resolveKotlinExecutionPlan(method, semantic)
        executionPlan.boundary?.let { boundary ->
            logger.info("静态下游提取跳过: reason=${executionPlan.reasonTag}, ${semantic.debugInfo(methodSignature)}")
            return ResolvedMethodFlow(boundary = boundary)
        }
        if (executionPlan.roots.isEmpty()) {
            logger.info("静态下游提取跳过: reason=noKotlinExecutionRoots, ${semantic.debugInfo(methodSignature)}")
            return ResolvedMethodFlow()
        }
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, methodKey(method))
        val resolvedCalls = linkedMapOf<String, ResolvedMethodCall>()
        val callOccurrences = executionPlan.roots
            .flatMap { root -> collectKotlinResolvedTargetOccurrences(root, methodNodeId, resolvedCalls) }
            .sortedBy { occurrence -> occurrence.offset }

        callOccurrences.forEachIndexed { callOrder, occurrence ->
            occurrence.register(callOrder)
        }
        if (resolvedCalls.isEmpty()) {
            logger.info("静态下游提取结果为空: ${semantic.debugInfo(methodKey(method))}")
        }
        return ResolvedMethodFlow(calls = resolvedCalls.values.toList())
    }

    private fun resolveJavaCallGraph(
        method: PsiMethod,
        body: PsiCodeBlock,
    ): ResolvedMethodFlow {
        return JavaMethodFlowCollector(method).collect(body)
    }

    fun resolveCallers(
        method: PsiMethod,
        limit: Int? = null,
    ): List<PsiMethod> {
        val scope = GlobalSearchScope.projectScope(method.project)
        val callers = linkedMapOf<String, PsiMethod>()
        val semantic = MethodSemanticDecoder.decode(method)
        val targetMethods = linkedSetOf<PsiMethod>().apply {
            add(method)
            addAll(method.findSuperMethods())
            addAll(method.findDeepestSuperMethods())
        }
        val effectiveLimit = limit?.coerceAtLeast(0)

        return runSafelyWithFallback(callers.values.sortedBy(::methodKey)) {
            targetMethods.forEach { targetMethod ->
                var shouldContinue = true
                ReferencesSearch.search(targetMethod, scope).forEach(
                    Processor { reference ->
                        if (!shouldContinue) {
                            return@Processor false
                        }
                        val callerMethod = callerMethodOf(reference.element)
                            ?: return@Processor true
                        val callerKey = methodKey(callerMethod)
                        if (callerKey != methodKey(method)) {
                            callers[callerKey] = callerMethod
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
            callers.values.sortedBy(::methodKey).also { resolvedCallers ->
                if (resolvedCallers.isEmpty() && semantic.domain != MethodSemanticDomain.JAVA) {
                    logger.info("静态上游提取结果为空: ${semantic.debugInfo(methodKey(method))}")
                }
            }
        }
    }

    fun callEdge(fromMethod: PsiMethod, toMethod: PsiMethod, callOrder: Int? = null): GraphEdge {
        val fromId = GraphNode.stableId(NodeType.METHOD, methodKey(fromMethod))
        return callEdge(fromId, toMethod, callOrder)
    }

    fun callEdge(fromNodeId: String, toNodeId: String, callOrder: Int? = null): GraphEdge {
        return GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, fromNodeId, toNodeId),
            type = EdgeType.CALL,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            metadata = buildMap {
                if (callOrder != null) {
                    put("callOrder", callOrder.toString())
                }
            },
        )
    }

    fun callEdge(fromNodeId: String, toMethod: PsiMethod, callOrder: Int? = null): GraphEdge {
        val toId = GraphNode.stableId(NodeType.METHOD, methodKey(toMethod))
        return callEdge(fromNodeId, toId, callOrder)
    }

    fun flowScopeNode(
        method: PsiMethod,
        rawKey: String,
        title: String,
        signature: String,
        element: PsiElement,
        kind: FlowScopeKind,
    ): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.FLOW_SCOPE, rawKey, methodKey(method)),
            type = NodeType.FLOW_SCOPE,
            title = title,
            location = locationOf(element),
            signature = signature,
            doc = "流程作用域",
            sourceKind = "JAVA_FLOW_SCOPE",
            metadata = buildMap {
                put("flow.kind", kind.name)
                put("flow.ownerMethod", methodKey(method))
                sourceMetadataOf(element).forEach(::put)
            },
        )
    }

    fun flowActionNode(
        method: PsiMethod,
        rawKey: String,
        title: String,
        element: PsiElement,
        signature: String? = null,
        actionKind: String = "ACTION",
    ): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.FLOW_ACTION, rawKey, methodKey(method)),
            type = NodeType.FLOW_ACTION,
            title = title,
            location = locationOf(element),
            signature = signature,
            doc = "当前方法关键动作",
            sourceKind = "JAVA_FLOW_ACTION",
            metadata = buildMap {
                put("flow.kind", actionKind)
                put("flow.anchorMethod", methodKey(method))
                put("flow.ownerMethod", methodKey(method))
                sourceMetadataOf(element).forEach(::put)
            },
        )
    }

    fun containsFlowEdge(
        fromNodeId: String,
        toNodeId: String,
        callOrder: Int? = null,
    ): GraphEdge {
        return GraphEdge(
            id = GraphEdge.stableId(EdgeType.CONTAINS_FLOW, fromNodeId, toNodeId),
            type = EdgeType.CONTAINS_FLOW,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            metadata = buildMap {
                if (callOrder != null) {
                    put("callOrder", callOrder.toString())
                }
            },
        )
    }

    fun implementsEdge(implementationClass: PsiClass, contractClass: PsiClass): GraphEdge {
        val fromId = GraphNode.stableId(NodeType.CLASS, implementationClass.qualifiedName ?: implementationClass.name ?: "<anonymous>")
        val toId = GraphNode.stableId(NodeType.CLASS, contractClass.qualifiedName ?: contractClass.name ?: "<anonymous>")
        return GraphEdge(
            id = GraphEdge.stableId(EdgeType.IMPLEMENTS, fromId, toId),
            type = EdgeType.IMPLEMENTS,
            fromNodeId = fromId,
            toNodeId = toId,
        )
    }

    fun concreteBeanClass(parameter: PsiParameter): PsiClass? {
        val paramClass = (parameter.type as? PsiClassType)?.resolve() ?: return null
        return concreteBeanClass(paramClass)
    }

    fun concreteBeanClass(psiClass: PsiClass): PsiClass? {
        if (isConcreteClass(psiClass)) {
            return psiClass
        }

        return inheritorClasses(psiClass)
            .filter(::isConcreteClass)
            .sortedBy { it.qualifiedName ?: it.name }
            .firstOrNull()
    }

    private fun concreteTargets(resolvedMethod: PsiMethod): List<PsiMethod> {
        if (!needsImplementationResolution(resolvedMethod)) {
            return listOf(resolvedMethod)
        }
        if (!isProjectSourceMethod(resolvedMethod)) {
            return listOf(resolvedMethod)
        }

        // 对接口/抽象方法优先展开到具体实现，避免链路只停留在契约层。
        val scope = GlobalSearchScope.projectScope(resolvedMethod.project)
        val overrides = runSafelyWithFallback(listOf(resolvedMethod)) {
            OverridingMethodsSearch.search(resolvedMethod, scope, true)
                .findAll()
                .filter { candidate -> candidate.containingClass?.let(::isConcreteClass) == true }
                .sortedBy(::methodKey)
        }
        return overrides.ifEmpty { listOf(resolvedMethod) }
    }

    private fun implementationEdge(contractMethod: PsiMethod, targetMethod: PsiMethod): ImplementationEdge? {
        val contractClass = contractMethod.containingClass ?: return null
        val implementationClass = targetMethod.containingClass ?: return null
        if (contractClass == implementationClass) {
            return null
        }
        if (!needsImplementationResolution(contractMethod)) {
            return null
        }
        return ImplementationEdge(
            implementationClass = implementationClass,
            contractClass = contractClass,
        )
    }

    private fun inheritorClasses(psiClass: PsiClass): Sequence<PsiClass> {
        val scope = GlobalSearchScope.projectScope(psiClass.project)
        val search = com.intellij.psi.search.searches.ClassInheritorsSearch.search(psiClass, scope, true)
        return search.findAll().asSequence()
    }

    private fun needsImplementationResolution(method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        return owner.isInterface || method.hasModifierProperty(PsiModifier.ABSTRACT)
    }

    private fun isConcreteClass(psiClass: PsiClass): Boolean {
        return !psiClass.isInterface && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
    }

    private fun methodSignature(method: PsiMethod, qualifiedOwner: Boolean): String {
        val owner = method.containingClass?.let { psiClass ->
            if (qualifiedOwner) {
                psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>"
            } else {
                psiClass.name ?: psiClass.qualifiedName ?: "<anonymous>"
            }
        } ?: "<anonymous>"
        val parameters = method.parameterList.parameters.joinToString(",") { typeText(it) }
        val returnType = typeText(method.returnType)
        return "$owner.${method.name}($parameters):$returnType"
    }

    private fun typeText(parameter: PsiParameter): String = typeText(parameter.type)

    private fun typeText(type: PsiType?): String {
        val psiType = type ?: return "void"
        val resolvedClass = (psiType as? PsiClassType)?.resolve()
        return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
    }

    private fun normalizeImplicitJavaLangType(typeText: String): String {
        return when (typeText) {
            "String" -> "java.lang.String"
            "Object" -> "java.lang.Object"
            "Integer" -> "java.lang.Integer"
            "Long" -> "java.lang.Long"
            "Boolean" -> "java.lang.Boolean"
            "Double" -> "java.lang.Double"
            "Float" -> "java.lang.Float"
            "Short" -> "java.lang.Short"
            "Byte" -> "java.lang.Byte"
            "Character" -> "java.lang.Character"
            "Void" -> "java.lang.Void"
            else -> typeText
        }
    }

    private fun locationOf(owner: PsiDocCommentOwner): String? = locationOf(owner as PsiElement)

    private fun locationOf(element: PsiElement): String? {
        return runSafelyWithFallback(null) {
            val file = element.containingFile ?: return@runSafelyWithFallback null
            val offset = element.textRange?.startOffset ?: return@runSafelyWithFallback null
            val line = file.text.take(offset).count { it == '\n' } + 1
            val path = file.virtualFile?.path ?: file.name
            "$path:$line"
        }
    }

    private fun methodDoc(method: PsiMethod): String? {
        val raw = method.docComment?.text ?: return null
        return raw
            .removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .map { line -> line.trim().removePrefix("*").trim() }
            .takeWhile { line -> !line.startsWith("@") }
            .filter { line -> line.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
    }

    private fun sourceMetadataOf(element: PsiElement): Map<String, String> {
        val filePath = element.containingFile?.virtualFile?.path ?: return emptyMap()
        val range = element.textRange ?: return mapOf("source.filePath" to filePath)
        return mapOf(
            "source.filePath" to filePath,
            "source.startOffset" to range.startOffset.toString(),
            "source.endOffset" to range.endOffset.toString(),
        )
    }

    private fun resolveKotlinExecutionPlan(
        method: PsiMethod,
        semantic: DecodedMethodSemantic,
    ): KotlinExecutionPlan {
        val methodSignature = methodKey(method)
        return when (semantic.kind) {
            MethodSemanticKind.KOTLIN_NAMED_FUNCTION -> {
                val sourceFunction = method.navigationElement as? KtNamedFunction
                    ?: return unsupportedKotlinExecutionPlan(
                        semantic = semantic,
                        methodSignature = methodSignature,
                        title = "Kotlin 命名函数静态提取边界",
                        description = "当前方法被识别为 Kotlin 命名函数，但无法定位对应的函数声明体。",
                        kind = "KOTLIN_NAMED_FUNCTION_BOUNDARY",
                        reasonTag = "missingKotlinNamedFunctionNavigation",
                    )
                KotlinExecutionPlan(roots = listOfNotNull(sourceFunction.bodyExpression))
            }
            MethodSemanticKind.KOTLIN_PROPERTY_ACCESSOR -> {
                val accessorSource = resolveKotlinPropertyAccessorSource(method)
                    ?: return unsupportedKotlinExecutionPlan(
                        semantic = semantic,
                        methodSignature = methodSignature,
                        title = "Kotlin 属性访问器静态提取边界",
                        description = "当前方法被识别为 Kotlin 属性访问器，但无法定位对应的访问器声明体。",
                        kind = "KOTLIN_PROPERTY_ACCESSOR_BOUNDARY",
                        reasonTag = "missingKotlinPropertyAccessorNavigation",
                    )
                KotlinExecutionPlan(roots = listOfNotNull(accessorSource.accessor?.bodyExpression))
            }
            MethodSemanticKind.KOTLIN_PRIMARY_CONSTRUCTOR -> {
                val constructor = method.navigationElement as? KtPrimaryConstructor
                    ?: return unsupportedKotlinExecutionPlan(
                        semantic = semantic,
                        methodSignature = methodSignature,
                        title = "Kotlin 主构造器静态提取边界",
                        description = "当前方法被识别为 Kotlin 主构造器，但无法定位对应的构造器声明体。",
                        kind = "KOTLIN_PRIMARY_CONSTRUCTOR_BOUNDARY",
                        reasonTag = "missingKotlinPrimaryConstructorNavigation",
                    )
                val owningClass = PsiTreeUtil.getParentOfType(constructor, KtClass::class.java, false)
                    ?: return unsupportedKotlinExecutionPlan(
                        semantic = semantic,
                        methodSignature = methodSignature,
                        title = "Kotlin 主构造器静态提取边界",
                        description = "当前主构造器缺少可解析的宿主类，无法收集初始化语义。",
                        kind = "KOTLIN_PRIMARY_CONSTRUCTOR_BOUNDARY",
                        reasonTag = "missingKotlinPrimaryConstructorOwner",
                    )
                KotlinExecutionPlan(roots = primaryConstructorExecutionRoots(owningClass))
            }
            MethodSemanticKind.KOTLIN_SECONDARY_CONSTRUCTOR -> {
                val constructor = method.navigationElement as? KtSecondaryConstructor
                    ?: return unsupportedKotlinExecutionPlan(
                        semantic = semantic,
                        methodSignature = methodSignature,
                        title = "Kotlin 次构造器静态提取边界",
                        description = "当前方法被识别为 Kotlin 次构造器，但无法定位对应的构造器声明体。",
                        kind = "KOTLIN_SECONDARY_CONSTRUCTOR_BOUNDARY",
                        reasonTag = "missingKotlinSecondaryConstructorNavigation",
                    )
                KotlinExecutionPlan(
                    roots = listOfNotNull(
                        constructor.getDelegationCall().takeIf { call -> call.text.isNotBlank() },
                        constructor.bodyExpression,
                    ),
                )
            }
            MethodSemanticKind.JAVA_METHOD,
            MethodSemanticKind.OTHER -> KotlinExecutionPlan(emptyList(), boundary = semantic.explicitCurrentMethodBoundary(methodSignature))
        }
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

    private fun unsupportedKotlinExecutionPlan(
        semantic: DecodedMethodSemantic,
        methodSignature: String,
        title: String,
        description: String,
        kind: String,
        reasonTag: String,
    ): KotlinExecutionPlan {
        return KotlinExecutionPlan(
            roots = emptyList(),
            boundary = semantic.boundary(
                methodSignature = methodSignature,
                title = title,
                description = description,
                kind = kind,
            ),
            reasonTag = reasonTag,
        )
    }

    private fun collectKotlinResolvedTargetOccurrences(
        root: KtElement,
        sourceNodeId: String,
        resolvedCalls: LinkedHashMap<String, ResolvedMethodCall>,
    ): List<ResolvedTargetOccurrence> {
        val callElements = buildList<KtCallElement> {
            if (root is KtCallElement) {
                add(root)
            }
            addAll(root.collectDescendantsOfType<KtCallExpression>())
        }
        val simpleNameExpressions = buildList<KtSimpleNameExpression> {
            if (root is KtSimpleNameExpression) {
                add(root)
            }
            addAll(root.collectDescendantsOfType<KtSimpleNameExpression>())
        }
        return buildList {
            callElements.forEach { callElement ->
                val resolved = resolveKotlinCallTarget(callElement)
                    ?.takeIf(::isProjectSourceMethod)
                    ?: return@forEach
                add(ResolvedTargetOccurrence(callElement.textRange.startOffset) { callOrder ->
                    registerResolvedTarget(
                        resolvedCalls = resolvedCalls,
                        sourceNodeId = sourceNodeId,
                        resolvedMethod = resolved,
                        callOrder = callOrder,
                    )
                })
            }
            simpleNameExpressions.forEach { simpleNameExpression ->
                val accessorMethods = resolveKotlinPropertyAccessorTargets(simpleNameExpression)
                if (accessorMethods.isEmpty()) {
                    return@forEach
                }
                add(ResolvedTargetOccurrence(simpleNameExpression.textRange.startOffset) { callOrder ->
                    accessorMethods.forEach { accessorMethod ->
                        registerResolvedTarget(
                            resolvedCalls = resolvedCalls,
                            sourceNodeId = sourceNodeId,
                            resolvedMethod = accessorMethod,
                            callOrder = callOrder,
                        )
                    }
                })
            }
        }
    }

    private fun resolveKotlinCallTarget(callExpression: KtCallElement): PsiMethod? {
        val calleeExpression = callExpression.calleeExpression ?: return null
        val resolvedElement = calleeExpression.mainReference?.resolve() ?: return null
        return psiMethodOf(resolvedElement)
    }

    private fun resolveKotlinPropertyAccessorTargets(simpleNameExpression: KtSimpleNameExpression): List<PsiMethod> {
        val declaration = simpleNameExpression.mainReference
            ?.resolve() as? KtNamedDeclaration
            ?: return emptyList()
        if (!supportsPropertyAccessors(declaration)) {
            return emptyList()
        }
        val accessors = declaration.getAccessorLightMethods()
        val access = simpleNameExpression.readWriteAccess(false)
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

    private fun registerResolvedTarget(
        resolvedCalls: LinkedHashMap<String, ResolvedMethodCall>,
        sourceNodeId: String,
        resolvedMethod: PsiMethod,
        callOrder: Int,
    ) {
        val concreteTargets = concreteTargets(resolvedMethod)
        concreteTargets.forEach { target ->
            val key = "$sourceNodeId->${methodKey(target)}"
            val implementationEdge = implementationEdge(resolvedMethod, target)
            val existing = resolvedCalls[key]
            if (existing == null) {
                resolvedCalls[key] = ResolvedMethodCall(
                    sourceNodeId = sourceNodeId,
                    target = target,
                    callOrder = callOrder,
                    implementationEdge = implementationEdge,
                )
            } else if (existing.implementationEdge == null && implementationEdge != null) {
                resolvedCalls[key] = existing.copy(implementationEdge = implementationEdge)
            }
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

    private fun psiMethodOf(element: PsiElement): PsiMethod? {
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

    private fun isProjectSourceMethod(method: PsiMethod): Boolean {
        val virtualFile = method.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(method.project).isInContent(virtualFile)
    }

    private inner class JavaMethodFlowCollector(
        private val method: PsiMethod,
    ) {
        private val methodNodeId = GraphNode.stableId(NodeType.METHOD, methodKey(method))
        private val resolvedCalls = linkedMapOf<String, ResolvedMethodCall>()
        private val scopeNodes = mutableListOf<GraphNode>()
        private val containmentEdges = mutableListOf<GraphEdge>()
        private val actionNodes = mutableListOf<GraphNode>()
        private val actionEdges = mutableListOf<GraphEdge>()
        private val nextOrderBySourceNodeId = mutableMapOf(methodNodeId to 0)
        private val handledMethodCallRanges = mutableSetOf<String>()
        private val handledLambdaOffsets = mutableSetOf<Int>()
        private val contextStack = ArrayDeque<FlowContext>().apply {
            addLast(FlowContext(methodNodeId))
        }
        private var scopeSerial = 0

        fun collect(body: PsiCodeBlock): ResolvedMethodFlow {
            visitCodeBlock(body)
            return ResolvedMethodFlow(
                scopeNodes = scopeNodes.toList(),
                containmentEdges = containmentEdges.toList(),
                actionNodes = actionNodes.toList(),
                actionEdges = actionEdges.toList(),
                calls = resolvedCalls.values.toList(),
            )
        }

        private fun visitCodeBlock(block: PsiCodeBlock?) {
            block?.statements?.forEach(::visitStatement)
        }

        private fun visitStatement(statement: PsiStatement?) {
            when (statement) {
                null -> Unit
                is PsiBlockStatement -> visitCodeBlock(statement.codeBlock)
                is PsiIfStatement -> visitIfStatement(statement)
                is PsiForeachStatement -> visitForeachStatement(statement)
                is PsiForStatement -> visitForStatement(statement)
                is PsiWhileStatement -> visitWhileStatement(statement)
                is PsiDoWhileStatement -> visitDoWhileStatement(statement)
                is PsiTryStatement -> visitTryStatement(statement)
                is PsiReturnStatement -> visitExpression(statement.returnValue)
                is PsiThrowStatement -> visitExpression(statement.exception)
                is PsiExpressionStatement -> visitExpression(statement.expression)
                is PsiDeclarationStatement -> statement.declaredElements.forEach(::visitDeclaredElement)
                else -> visitElementChildren(statement)
            }
        }

        private fun visitDeclaredElement(element: PsiElement) {
            when (element) {
                is PsiVariable -> visitExpression(element.initializer)
                else -> visitElementChildren(element)
            }
        }

        private fun visitExpression(expression: PsiExpression?) {
            when (expression) {
                null -> Unit
                is PsiMethodCallExpression -> visitMethodCallExpression(expression)
                is PsiNewExpression -> visitNewExpression(expression)
                is PsiLambdaExpression -> visitLambdaExpression(expression)
                is PsiAssignmentExpression -> {
                    visitExpression(expression.lExpression)
                    visitExpression(expression.rExpression)
                }
                is PsiConditionalExpression -> {
                    visitExpression(expression.condition)
                    visitExpression(expression.thenExpression)
                    visitExpression(expression.elseExpression)
                }
                is PsiPolyadicExpression -> expression.operands.forEach(::visitExpression)
                is PsiUnaryExpression -> visitExpression(expression.operand)
                is PsiTypeCastExpression -> visitExpression(expression.operand)
                is PsiParenthesizedExpression -> visitExpression(expression.expression)
                is PsiArrayAccessExpression -> {
                    visitExpression(expression.arrayExpression)
                    visitExpression(expression.indexExpression)
                }
                is PsiArrayInitializerExpression -> expression.initializers.forEach(::visitExpression)
                is PsiReferenceExpression -> visitExpression(expression.qualifierExpression)
                is PsiInstanceOfExpression -> visitExpression(expression.operand)
                else -> visitElementChildren(expression)
            }
        }

        private fun visitElementChildren(element: PsiElement?) {
            element?.children?.forEach { child ->
                when (child) {
                    is PsiStatement -> visitStatement(child)
                    is PsiExpression -> visitExpression(child)
                    else -> visitElementChildren(child)
                }
            }
        }

        private fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
            if (!markMethodCallHandled(expression)) {
                return
            }
            val resolvedMethod = expression.resolveMethod()
            val actionNode = createMethodCallActionNode(expression, resolvedMethod)
            val actionNodeId = actionNode?.let(::registerActionNode)
            withTemporaryContext(actionNodeId) {
                visitExpression(expression.methodExpression.qualifierExpression)
                val lambdaArguments = mutableListOf<PsiLambdaExpression>()
                expression.argumentList.expressions.forEach { argument ->
                    if (argument is PsiLambdaExpression) {
                        lambdaArguments += argument
                    } else {
                        visitExpression(argument)
                    }
                }
                val sourceNodeId = currentContext().nodeId
                resolvedMethod?.let { resolvedMethod ->
                    registerResolvedTarget(
                        resolvedCalls = resolvedCalls,
                        sourceNodeId = sourceNodeId,
                        resolvedMethod = resolvedMethod,
                        callOrder = nextCallOrder(sourceNodeId),
                    )
                }
                lambdaArguments.forEach { lambdaExpression ->
                    if (!markLambdaHandled(lambdaExpression)) {
                        return@forEach
                    }
                    enterScope(
                        kind = FlowScopeKind.LAMBDA,
                        element = lambdaExpression,
                        title = buildLambdaTitle(
                            ownerMethod = resolvedMethod,
                            fallbackName = expression.methodExpression.referenceName,
                            lambdaExpression = lambdaExpression,
                        ),
                        signature = buildLambdaSignature(lambdaExpression),
                    ) {
                        visitScopeElement(lambdaExpression.body)
                    }
                }
            }
        }

        private fun visitNewExpression(expression: PsiNewExpression) {
            val actionNodeId = createNewExpressionActionNode(expression)?.let(::registerActionNode)
            withTemporaryContext(actionNodeId) {
                visitExpression(expression.qualifier)
                expression.arrayDimensions.forEach { dimension ->
                    visitExpression(dimension)
                }
                visitExpression(expression.arrayInitializer)
                expression.argumentList?.expressions?.forEach { argument ->
                    visitExpression(argument)
                }
            }
        }

        private fun visitLambdaExpression(expression: PsiLambdaExpression) {
            if (!markLambdaHandled(expression)) {
                return
            }
            val enclosingCall = PsiTreeUtil.getParentOfType(
                expression,
                PsiMethodCallExpression::class.java,
                PsiNewExpression::class.java,
            )
            enterScope(
                kind = FlowScopeKind.LAMBDA,
                element = expression,
                title = buildLambdaTitle(
                    ownerMethod = when (enclosingCall) {
                        is PsiMethodCallExpression -> enclosingCall.resolveMethod()
                        else -> null
                    },
                    fallbackName = when (enclosingCall) {
                        is PsiMethodCallExpression -> enclosingCall.methodExpression.referenceName
                        is PsiNewExpression -> enclosingCall.classReference?.referenceName
                        else -> null
                    },
                    lambdaExpression = expression,
                ),
                signature = buildLambdaSignature(expression),
            ) {
                visitScopeElement(expression.body)
            }
        }

        private fun visitIfStatement(statement: PsiIfStatement) {
            enterScope(
                kind = FlowScopeKind.IF,
                element = statement,
                title = buildIfTitle(statement),
                signature = shortFlowText(statement.condition?.text) ?: "conditional branch",
            ) {
                visitExpression(statement.condition)
                visitScopeElement(statement.thenBranch)
                visitScopeElement(statement.elseBranch)
            }
        }

        private fun visitForeachStatement(statement: PsiForeachStatement) {
            visitExpression(statement.iteratedValue)
            enterScope(
                kind = FlowScopeKind.FOREACH,
                element = statement,
                title = buildForeachTitle(statement),
                signature = statement.iterationParameter?.typeElement?.text ?: "foreach body",
            ) {
                visitScopeElement(statement.body)
            }
        }

        private fun visitForStatement(statement: PsiForStatement) {
            enterScope(
                kind = FlowScopeKind.FOR,
                element = statement,
                title = buildForTitle(statement),
                signature = shortFlowText(statement.condition?.text) ?: "for loop body",
            ) {
                visitScopeElement(statement.initialization)
                visitExpression(statement.condition)
                visitScopeElement(statement.update)
                visitScopeElement(statement.body)
            }
        }

        private fun visitWhileStatement(statement: PsiWhileStatement) {
            enterScope(
                kind = FlowScopeKind.WHILE,
                element = statement,
                title = buildWhileTitle(statement),
                signature = shortFlowText(statement.condition?.text) ?: "while loop body",
            ) {
                visitExpression(statement.condition)
                visitScopeElement(statement.body)
            }
        }

        private fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
            enterScope(
                kind = FlowScopeKind.DO_WHILE,
                element = statement,
                title = buildDoWhileTitle(statement),
                signature = shortFlowText(statement.condition?.text) ?: "do-while loop body",
            ) {
                visitScopeElement(statement.body)
                visitExpression(statement.condition)
            }
        }

        private fun visitTryStatement(statement: PsiTryStatement) {
            visitCodeBlock(statement.tryBlock)
            statement.resourceList?.children?.forEach(::visitElementChildren)
            statement.catchSections.forEach { catchSection ->
                visitCodeBlock(catchSection.catchBlock)
            }
            visitCodeBlock(statement.finallyBlock)
        }

        private fun enterScope(
            kind: FlowScopeKind,
            element: PsiElement,
            title: String,
            signature: String,
            action: () -> Unit,
        ) {
            val parentContext = currentContext()
            val scopeNode = flowScopeNode(
                method = method,
                rawKey = "scope-${scopeSerial++}-${kind.name.lowercase()}",
                title = title,
                signature = signature,
                element = element,
                kind = kind,
            )
            scopeNodes += scopeNode
            containmentEdges += containsFlowEdge(
                fromNodeId = parentContext.nodeId,
                toNodeId = scopeNode.id,
                callOrder = nextCallOrder(parentContext.nodeId),
            )
            contextStack.addLast(FlowContext(scopeNode.id))
            try {
                action()
            } finally {
                contextStack.removeLast()
            }
        }

        private fun markLambdaHandled(expression: PsiLambdaExpression): Boolean {
            val offset = expression.textRange?.startOffset ?: return true
            return handledLambdaOffsets.add(offset)
        }

        private fun markMethodCallHandled(expression: PsiMethodCallExpression): Boolean {
            val range = expression.textRange ?: return true
            return handledMethodCallRanges.add("${range.startOffset}:${range.endOffset}")
        }

        private fun visitScopeElement(element: PsiElement?) {
            when (element) {
                null -> Unit
                is PsiCodeBlock -> visitCodeBlock(element)
                is PsiBlockStatement -> visitCodeBlock(element.codeBlock)
                is PsiStatement -> visitStatement(element)
                is PsiExpression -> visitExpression(element)
                else -> visitElementChildren(element)
            }
        }

        private fun currentContext(): FlowContext = contextStack.last()

        private inline fun withTemporaryContext(nodeId: String?, action: () -> Unit) {
            if (nodeId == null) {
                action()
                return
            }
            contextStack.addLast(FlowContext(nodeId))
            try {
                action()
            } finally {
                contextStack.removeLast()
            }
        }

        private fun nextCallOrder(sourceNodeId: String): Int {
            val next = nextOrderBySourceNodeId[sourceNodeId] ?: 0
            nextOrderBySourceNodeId[sourceNodeId] = next + 1
            return next
        }

        private fun registerActionNode(node: GraphNode): String {
            actionNodes += node
            actionEdges += callEdge(
                fromNodeId = currentContext().nodeId,
                toNodeId = node.id,
                callOrder = nextCallOrder(currentContext().nodeId),
            )
            return node.id
        }

        private fun createMethodCallActionNode(
            expression: PsiMethodCallExpression,
            resolvedMethod: PsiMethod?,
        ): GraphNode? {
            if (!shouldCaptureMethodCallAsAction(expression, resolvedMethod)) {
                return null
            }
            val startOffset = expression.textRange?.startOffset ?: return null
            val title = shortActionText(expression.text) ?: return null
            return flowActionNode(
                method = method,
                rawKey = "action-call-$startOffset",
                title = title,
                element = expression,
                signature = title,
                actionKind = actionKindOf(expression),
            )
        }

        private fun createNewExpressionActionNode(expression: PsiNewExpression): GraphNode? {
            if (!shouldCaptureStandaloneAction(expression)) {
                return null
            }
            val startOffset = expression.textRange?.startOffset ?: return null
            val title = shortActionText(expression.text) ?: return null
            return flowActionNode(
                method = method,
                rawKey = "action-new-$startOffset",
                title = title,
                element = expression,
                signature = title,
                actionKind = actionKindOf(expression),
            )
        }

        private fun shouldCaptureMethodCallAsAction(
            expression: PsiMethodCallExpression,
            resolvedMethod: PsiMethod?,
        ): Boolean {
            return shouldCaptureStandaloneAction(expression)
        }

        private fun shouldCaptureStandaloneAction(expression: PsiExpression): Boolean {
            var parent = PsiUtil.skipParenthesizedExprUp(expression.parent)
            while (parent is PsiExpressionList) {
                parent = PsiUtil.skipParenthesizedExprUp(parent.parent)
            }
            return when (parent) {
                is PsiExpressionStatement,
                is PsiAssignmentExpression,
                is PsiLocalVariable,
                is PsiReturnStatement,
                is PsiThrowStatement,
                -> true

                else -> false
            }
        }

        private fun shortActionText(raw: String?): String? {
            val text = raw
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return if (text.length <= 72) text else text.take(69).trimEnd() + "..."
        }

        private fun actionKindOf(expression: PsiExpression): String {
            var parent = PsiUtil.skipParenthesizedExprUp(expression.parent)
            while (parent is PsiExpressionList) {
                parent = PsiUtil.skipParenthesizedExprUp(parent.parent)
            }
            return when (parent) {
                is PsiReturnStatement -> "RETURN"
                is PsiThrowStatement -> "THROW"
                else -> "ACTION"
            }
        }

        private fun buildLambdaTitle(
            ownerMethod: PsiMethod?,
            fallbackName: String?,
            lambdaExpression: PsiLambdaExpression,
        ): String {
            val ownerName =
                ownerMethod?.name?.takeIf { it.isNotBlank() } ?:
                    fallbackName?.takeIf { it.isNotBlank() } ?:
                    "lambda"
            val parameterNames = lambdaExpression.parameterList.parameters
                .mapNotNull { parameter -> parameter.name }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
            return if (parameterNames == null) {
                "$ownerName λ"
            } else {
                "$ownerName λ($parameterNames)"
            }
        }

        private fun buildLambdaSignature(lambdaExpression: PsiLambdaExpression): String {
            val parameterText = lambdaExpression.parameterList.parameters
                .joinToString(", ") { parameter -> parameter.typeElement?.text ?: (parameter.name ?: "_") }
                .ifBlank { "无参" }
            return "lambda body · $parameterText"
        }

        private fun buildIfTitle(statement: PsiIfStatement): String {
            val condition = shortFlowText(statement.condition?.text) ?: "condition"
            return "if ($condition)"
        }

        private fun buildForeachTitle(statement: PsiForeachStatement): String {
            val parameterName = statement.iterationParameter?.name ?: "_"
            val iteratedText = shortFlowText(statement.iteratedValue?.text) ?: "items"
            return "for ($parameterName : $iteratedText)"
        }

        private fun buildForTitle(statement: PsiForStatement): String {
            val condition = shortFlowText(statement.condition?.text) ?: "..."
            return "for ($condition)"
        }

        private fun buildWhileTitle(statement: PsiWhileStatement): String {
            val condition = shortFlowText(statement.condition?.text) ?: "..."
            return "while ($condition)"
        }

        private fun buildDoWhileTitle(statement: PsiDoWhileStatement): String {
            val condition = shortFlowText(statement.condition?.text) ?: "..."
            return "do-while ($condition)"
        }

        private fun shortFlowText(raw: String?): String? {
            val text = raw
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return if (text.length <= 56) text else text.take(53).trimEnd() + "..."
        }
    }

    private fun isAccessorLike(method: PsiMethod): Boolean {
        val parameterCount = method.parameterList.parametersCount
        val name = method.name
        val getterLike =
            (name.startsWith("get") && name.length > 3 && parameterCount == 0) ||
                (name.startsWith("is") && name.length > 2 && parameterCount == 0) ||
                (name.startsWith("has") && name.length > 3 && parameterCount == 0)
        val setterLike = name.startsWith("set") && name.length > 3 && parameterCount <= 1
        return getterLike || setterLike
    }

    private fun <T> runSafelyWithFallback(fallback: T, action: () -> T): T {
        return try {
            action()
        } catch (throwable: Throwable) {
            if (!isBenignPsiCancellation(throwable)) {
                throw throwable
            }
            logger.info("链路解析在 PSI/索引释放后提前终止: ${throwable.javaClass.simpleName}")
            fallback
        }
    }

    private fun isBenignPsiCancellation(throwable: Throwable): Boolean {
        if (throwable is ProcessCanceledException || throwable is AlreadyDisposedException) {
            return true
        }
        return throwable.cause?.let(::isBenignPsiCancellation) == true
    }

    companion object {
        private val logger = Logger.getInstance(JavaResolver::class.java)
    }
}

data class ResolvedMethodCall(
    val sourceNodeId: String,
    val target: PsiMethod,
    val callOrder: Int,
    val implementationEdge: ImplementationEdge? = null,
)

data class ResolvedMethodFlow(
    val scopeNodes: List<GraphNode> = emptyList(),
    val containmentEdges: List<GraphEdge> = emptyList(),
    val actionNodes: List<GraphNode> = emptyList(),
    val actionEdges: List<GraphEdge> = emptyList(),
    val calls: List<ResolvedMethodCall> = emptyList(),
    val boundary: ExtractionBoundary? = null,
)

data class ImplementationEdge(
    val implementationClass: PsiClass,
    val contractClass: PsiClass,
)

private data class FlowContext(
    val nodeId: String,
)

enum class FlowScopeKind {
    LAMBDA,
    IF,
    FOREACH,
    FOR,
    WHILE,
    DO_WHILE,
}

private data class ResolvedTargetOccurrence(
    val offset: Int,
    val register: (Int) -> Unit,
)

private data class KotlinExecutionPlan(
    val roots: List<KtElement>,
    val boundary: ExtractionBoundary? = null,
    val reasonTag: String = "unsupportedKotlinExecutionPlan",
)
