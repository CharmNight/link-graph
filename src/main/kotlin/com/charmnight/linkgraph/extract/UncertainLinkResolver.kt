package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * 统一承载“无法静态精确还原，但不能直接中断”的链路。
 * 当前覆盖 AOP、反射、SPI 与 JDK 动态代理，并通过 uncertainty 明确告诉前端这是候选关系而非事实关系。
 */
class UncertainLinkResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val additionalMethods = linkedSetOf<PsiMethod>()

        resolveAop(method, methodNodeId, context, nodes, edges, additionalMethods)

        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).forEach { callExpression ->
            when (callExpression.methodExpression.referenceName) {
                "getDeclaredMethod", "getMethod" -> resolveReflection(method, methodNodeId, callExpression, nodes, edges, additionalMethods)
                "load" -> resolveSpi(methodNodeId, callExpression, context, nodes, edges)
                "newProxyInstance" -> resolveProxy(method, methodNodeId, callExpression, nodes, edges)
            }
        }

        return ResolverOutput(
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
            additionalMethods = additionalMethods.toList(),
        )
    }

    private fun resolveReflection(
        sourceMethod: PsiMethod,
        sourceMethodNodeId: String,
        callExpression: PsiMethodCallExpression,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
        additionalMethods: MutableSet<PsiMethod>,
    ) {
        val reflectedMethodName = ResolverSupport.literalString(callExpression.argumentList.expressions.firstOrNull()) ?: return
        val parameterCount = callExpression.argumentList.expressions.size - 1
        val targetClass = resolveClassFromReflectionQualifier(callExpression.methodExpression.qualifierExpression) ?: run {
            val uncertainNode = uncertainNode(
                rawKey = "${sourceMethodNodeId}:reflection:$reflectedMethodName",
                title = "Reflective target $reflectedMethodName",
                sourceKind = "REFLECTION_TARGET",
                reason = "reflection target could not be resolved",
            )
            nodes[uncertainNode.id] = uncertainNode
            edges[GraphEdge.stableId(EdgeType.REFLECTS_TO, sourceMethodNodeId, uncertainNode.id)] = uncertainEdge(
                type = EdgeType.REFLECTS_TO,
                fromNodeId = sourceMethodNodeId,
                toNodeId = uncertainNode.id,
                reason = "reflection target could not be resolved",
            )
            return
        }

        val targetMethod = targetClass.findMethodsByName(reflectedMethodName, true)
            .sortedBy(javaResolver::methodKey)
            .firstOrNull { candidate -> candidate.parameterList.parametersCount == parameterCount }
            ?: targetClass.findMethodsByName(reflectedMethodName, true).sortedBy(javaResolver::methodKey).firstOrNull()

        if (targetMethod == null) {
            val uncertainNode = uncertainNode(
                rawKey = "${targetClass.qualifiedName}#$reflectedMethodName",
                title = "${targetClass.name}#$reflectedMethodName",
                sourceKind = "REFLECTION_TARGET",
                reason = "reflected method could not be resolved",
            )
            nodes[uncertainNode.id] = uncertainNode
            edges[GraphEdge.stableId(EdgeType.REFLECTS_TO, sourceMethodNodeId, uncertainNode.id)] = uncertainEdge(
                type = EdgeType.REFLECTS_TO,
                fromNodeId = sourceMethodNodeId,
                toNodeId = uncertainNode.id,
                reason = "reflected method could not be resolved",
            )
            return
        }

        val targetNode = javaResolver.methodNode(targetMethod)
        nodes[targetNode.id] = targetNode
        edges[GraphEdge.stableId(EdgeType.REFLECTS_TO, sourceMethodNodeId, targetNode.id)] = uncertainEdge(
            type = EdgeType.REFLECTS_TO,
            fromNodeId = sourceMethodNodeId,
            toNodeId = targetNode.id,
            reason = "reflection invocation",
        )
        additionalMethods += targetMethod
    }

    private fun resolveSpi(
        sourceMethodNodeId: String,
        callExpression: PsiMethodCallExpression,
        context: ResolverContext,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        if (!matchesStaticOwner(callExpression, "java.util.ServiceLoader", "ServiceLoader")) {
            return
        }

        val serviceInterface = resolveClassLiteral(callExpression.argumentList.expressions.firstOrNull()) ?: return
        val serviceName = serviceInterface.qualifiedName ?: return
        val providers = providerClasses(serviceInterface, serviceName, context)
        providers.forEach { providerClass ->
            val providerNode = javaResolver.classNode(providerClass)
            nodes[providerNode.id] = providerNode
            edges[GraphEdge.stableId(EdgeType.SPI_RESOLVES_TO, sourceMethodNodeId, providerNode.id)] = GraphEdge(
                id = GraphEdge.stableId(EdgeType.SPI_RESOLVES_TO, sourceMethodNodeId, providerNode.id),
                type = EdgeType.SPI_RESOLVES_TO,
                fromNodeId = sourceMethodNodeId,
                toNodeId = providerNode.id,
                certainty = Certainty.RULE_INFERRED,
                uncertainty = GraphUncertainty(
                    reason = "service loader provider candidate",
                    confidence = 0.5,
                ),
                metadata = mapOf("serviceInterface" to serviceName),
            )
        }
    }

    private fun resolveProxy(
        sourceMethod: PsiMethod,
        sourceMethodNodeId: String,
        callExpression: PsiMethodCallExpression,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
    ) {
        if (!matchesStaticOwner(callExpression, "java.lang.reflect.Proxy", "Proxy")) {
            return
        }

        val proxyInterface = resolveFirstClassLiteral(callExpression.argumentList.expressions.getOrNull(1))
        val title = proxyInterface?.name?.let { "Dynamic proxy for $it" } ?: "Dynamic proxy"
        val rawKey = "${javaResolver.methodKey(sourceMethod)}:${proxyInterface?.qualifiedName ?: "proxy"}"
        val proxyNode = uncertainNode(
            rawKey = rawKey,
            title = title,
            sourceKind = "JDK_PROXY",
            reason = "dynamic proxy invocation",
        )
        nodes[proxyNode.id] = proxyNode
        edges[GraphEdge.stableId(EdgeType.USES_PROXY, sourceMethodNodeId, proxyNode.id)] = uncertainEdge(
            type = EdgeType.USES_PROXY,
            fromNodeId = sourceMethodNodeId,
            toNodeId = proxyNode.id,
            reason = "dynamic proxy invocation",
        )
    }

    private fun resolveAop(
        targetMethod: PsiMethod,
        targetMethodNodeId: String,
        context: ResolverContext,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
        additionalMethods: MutableSet<PsiMethod>,
    ) {
        // AOP 的目标方法在编译期通常并不会显式调用 advice，因此这里按“候选代理链”补图。
        context.allProjectClasses()
            .asSequence()
            .filter { psiClass -> ResolverSupport.hasAnyAnnotation(psiClass, ASPECT_ANNOTATIONS) }
            .forEach { aspectClass ->
                val namedPointcuts = aspectClass.methods.mapNotNull { pointcutMethod ->
                    val pointcutAnnotation = ResolverSupport.findAnnotation(pointcutMethod, POINTCUT_ANNOTATIONS) ?: return@mapNotNull null
                    val expression = ResolverSupport.annotationString(pointcutAnnotation, "value") ?: return@mapNotNull null
                    pointcutMethod.name to expression
                }.toMap()

                aspectClass.methods.forEach { adviceMethod ->
                    val adviceDescriptor = adviceDescriptor(adviceMethod) ?: return@forEach
                    val rawExpression = ResolverSupport.annotationString(adviceDescriptor.annotation, "value", "pointcut") ?: return@forEach
                    val expandedExpression = expandPointcutReferences(rawExpression, namedPointcuts)
                    when (evaluatePointcut(expandedExpression, targetMethod)) {
                        PointcutEvaluation.MATCHED -> addAopAdviceLink(
                            targetMethod = targetMethod,
                            targetMethodNodeId = targetMethodNodeId,
                            adviceMethod = adviceMethod,
                            adviceType = adviceDescriptor.type,
                            pointcutExpression = expandedExpression,
                            reason = "AOP advice matched by static rule",
                            nodes = nodes,
                            edges = edges,
                            additionalMethods = additionalMethods,
                        )

                        PointcutEvaluation.UNSUPPORTED -> {
                            // 无法完整解释 pointcut 时，只要它看起来可能命中当前方法，就保留一条“候选代理边”。
                            if (isPotentialAopCandidate(expandedExpression, targetMethod)) {
                                addAopAdviceLink(
                                    targetMethod = targetMethod,
                                    targetMethodNodeId = targetMethodNodeId,
                                    adviceMethod = adviceMethod,
                                    adviceType = adviceDescriptor.type,
                                    pointcutExpression = expandedExpression,
                                    reason = "AOP advice candidate; pointcut is only partially supported",
                                    nodes = nodes,
                                    edges = edges,
                                    additionalMethods = additionalMethods,
                                )
                            }
                        }

                        PointcutEvaluation.NOT_MATCHED -> Unit
                    }
                }
            }
    }

    private fun adviceDescriptor(method: PsiMethod): AdviceDescriptor? {
        return AOP_ADVICE_ANNOTATIONS.firstNotNullOfOrNull { (type, names) ->
            ResolverSupport.findAnnotation(method, names)?.let { annotation ->
                AdviceDescriptor(annotation = annotation, type = type)
            }
        }
    }

    private fun addAopAdviceLink(
        targetMethod: PsiMethod,
        targetMethodNodeId: String,
        adviceMethod: PsiMethod,
        adviceType: String,
        pointcutExpression: String,
        reason: String,
        nodes: MutableMap<String, GraphNode>,
        edges: MutableMap<String, GraphEdge>,
        additionalMethods: MutableSet<PsiMethod>,
    ) {
        if (adviceMethod == targetMethod) {
            return
        }

        val adviceNode = javaResolver.methodNode(adviceMethod)
        nodes[adviceNode.id] = adviceNode
        val edgeId = GraphEdge.stableId(
            type = EdgeType.USES_PROXY,
            fromNodeId = adviceNode.id,
            toNodeId = targetMethodNodeId,
            ownerContext = javaResolver.methodKey(adviceMethod),
        )
        edges[edgeId] = GraphEdge(
            id = edgeId,
            type = EdgeType.USES_PROXY,
            fromNodeId = adviceNode.id,
            toNodeId = targetMethodNodeId,
            certainty = Certainty.RULE_INFERRED,
            uncertainty = GraphUncertainty(
                reason = "$reason for AOP advice ${adviceMethod.name}",
                confidence = 0.45,
            ),
            metadata = mapOf(
                "adviceType" to adviceType,
                "pointcut" to pointcutExpression,
                "aspectClass" to (adviceMethod.containingClass?.qualifiedName ?: adviceMethod.containingClass?.name ?: "<anonymous>"),
            ),
        )
        additionalMethods += adviceMethod
    }

    private fun expandPointcutReferences(
        expression: String,
        pointcuts: Map<String, String>,
    ): String {
        var current = expression
        repeat(6) {
            var changed = false
            pointcuts.forEach { (name, pointcutExpression) ->
                val token = "$name()"
                if (current.contains(token)) {
                    current = current.replace(token, "($pointcutExpression)")
                    changed = true
                }
            }
            if (!changed) {
                return current
            }
        }
        return current
    }

    private fun evaluatePointcut(
        expression: String,
        targetMethod: PsiMethod,
    ): PointcutEvaluation {
        val normalized = unwrapOuterParens(expression.trim())
        if (normalized.isBlank()) {
            return PointcutEvaluation.UNSUPPORTED
        }

        splitTopLevel(normalized, "||")
            .takeIf { it.size > 1 }
            ?.let { parts ->
                return combineOr(parts.map { part -> evaluatePointcut(part, targetMethod) })
            }

        splitTopLevel(normalized, "&&")
            .takeIf { it.size > 1 }
            ?.let { parts ->
                return combineAnd(parts.map { part -> evaluatePointcut(part, targetMethod) })
            }

        if (normalized.startsWith("!")) {
            return when (evaluatePointcut(normalized.removePrefix("!"), targetMethod)) {
                PointcutEvaluation.MATCHED -> PointcutEvaluation.NOT_MATCHED
                PointcutEvaluation.NOT_MATCHED -> PointcutEvaluation.MATCHED
                PointcutEvaluation.UNSUPPORTED -> PointcutEvaluation.UNSUPPORTED
            }
        }

        return when {
            normalized.startsWith("execution(") && normalized.endsWith(")") -> matchExecutionPointcut(normalized, targetMethod)
            normalized.startsWith("@annotation(") && normalized.endsWith(")") -> matchMethodAnnotationPointcut(normalized, targetMethod)
            normalized.startsWith("@within(") && normalized.endsWith(")") -> matchClassAnnotationPointcut(normalized, targetMethod)
            normalized.startsWith("@target(") && normalized.endsWith(")") -> matchClassAnnotationPointcut(normalized, targetMethod)
            normalized.startsWith("within(") && normalized.endsWith(")") -> matchWithinPointcut(normalized, targetMethod)
            else -> PointcutEvaluation.UNSUPPORTED
        }
    }

    private fun matchExecutionPointcut(
        expression: String,
        targetMethod: PsiMethod,
    ): PointcutEvaluation {
        val body = expression.removePrefix("execution(").removeSuffix(")").trim()
        val declaration = body.substringAfterLast(' ', body).trim()
        val argsStart = declaration.indexOf('(')
        val argsEnd = declaration.lastIndexOf(')')
        if (argsStart <= 0 || argsEnd <= argsStart) {
            return PointcutEvaluation.UNSUPPORTED
        }

        val selector = declaration.substring(0, argsStart)
        val methodPattern = selector.substringAfterLast('.', "")
        val ownerPattern = selector.substringBeforeLast('.', "")
        val ownerName = targetMethod.containingClass?.qualifiedName ?: return PointcutEvaluation.NOT_MATCHED
        if (methodPattern.isBlank() || ownerPattern.isBlank()) {
            return PointcutEvaluation.UNSUPPORTED
        }
        if (!wildcardMatches(ownerPattern, ownerName)) {
            return PointcutEvaluation.NOT_MATCHED
        }
        if (!wildcardMatches(methodPattern, targetMethod.name)) {
            return PointcutEvaluation.NOT_MATCHED
        }

        return matchParameterPattern(
            pattern = declaration.substring(argsStart + 1, argsEnd),
            parameterCount = targetMethod.parameterList.parametersCount,
        )
    }

    private fun matchMethodAnnotationPointcut(
        expression: String,
        targetMethod: PsiMethod,
    ): PointcutEvaluation {
        val annotationName = expression.removePrefix("@annotation(").removeSuffix(")").trim().trim('"')
        if (annotationName.isBlank()) {
            return PointcutEvaluation.UNSUPPORTED
        }
        return if (ResolverSupport.hasAnyAnnotation(targetMethod, setOf(annotationName))) {
            PointcutEvaluation.MATCHED
        } else {
            PointcutEvaluation.NOT_MATCHED
        }
    }

    private fun matchClassAnnotationPointcut(
        expression: String,
        targetMethod: PsiMethod,
    ): PointcutEvaluation {
        val annotationName = expression.substringAfter('(').removeSuffix(")").trim().trim('"')
        val ownerClass = targetMethod.containingClass ?: return PointcutEvaluation.NOT_MATCHED
        if (annotationName.isBlank()) {
            return PointcutEvaluation.UNSUPPORTED
        }
        return if (ResolverSupport.hasAnyAnnotation(ownerClass, setOf(annotationName))) {
            PointcutEvaluation.MATCHED
        } else {
            PointcutEvaluation.NOT_MATCHED
        }
    }

    private fun matchWithinPointcut(
        expression: String,
        targetMethod: PsiMethod,
    ): PointcutEvaluation {
        val ownerName = targetMethod.containingClass?.qualifiedName ?: return PointcutEvaluation.NOT_MATCHED
        val ownerPattern = expression.removePrefix("within(").removeSuffix(")").trim()
        if (ownerPattern.isBlank()) {
            return PointcutEvaluation.UNSUPPORTED
        }
        return if (wildcardMatches(ownerPattern, ownerName)) {
            PointcutEvaluation.MATCHED
        } else {
            PointcutEvaluation.NOT_MATCHED
        }
    }

    private fun matchParameterPattern(
        pattern: String,
        parameterCount: Int,
    ): PointcutEvaluation {
        val normalized = pattern.trim()
        return when {
            normalized.isBlank() -> if (parameterCount == 0) PointcutEvaluation.MATCHED else PointcutEvaluation.NOT_MATCHED
            normalized == ".." || normalized.contains("..") -> PointcutEvaluation.MATCHED
            else -> {
                val expectedCount = normalized.split(',').count { it.trim().isNotBlank() }
                if (expectedCount == parameterCount) {
                    PointcutEvaluation.MATCHED
                } else {
                    PointcutEvaluation.NOT_MATCHED
                }
            }
        }
    }

    private fun isPotentialAopCandidate(
        expression: String,
        targetMethod: PsiMethod,
    ): Boolean {
        val ownerClass = targetMethod.containingClass
        val ownerQualifiedName = ownerClass?.qualifiedName.orEmpty()
        val ownerSimpleName = ownerClass?.name.orEmpty()
        val targetAnnotations = targetMethod.annotations.map(ResolverSupport::annotationName)
        val ownerAnnotations = ownerClass?.annotations?.map(ResolverSupport::annotationName).orEmpty()
        return expression.contains("execution(* *(..))") ||
            expression.contains(targetMethod.name) ||
            expression.contains(ownerQualifiedName) ||
            expression.contains(ownerSimpleName) ||
            (targetAnnotations + ownerAnnotations).any { annotationName ->
                expression.contains(annotationName) || expression.contains(annotationName.substringAfterLast('.'))
            }
    }

    private fun combineOr(results: List<PointcutEvaluation>): PointcutEvaluation {
        return when {
            results.any { it == PointcutEvaluation.MATCHED } -> PointcutEvaluation.MATCHED
            results.any { it == PointcutEvaluation.UNSUPPORTED } -> PointcutEvaluation.UNSUPPORTED
            else -> PointcutEvaluation.NOT_MATCHED
        }
    }

    private fun combineAnd(results: List<PointcutEvaluation>): PointcutEvaluation {
        return when {
            results.any { it == PointcutEvaluation.NOT_MATCHED } -> PointcutEvaluation.NOT_MATCHED
            results.all { it == PointcutEvaluation.MATCHED } -> PointcutEvaluation.MATCHED
            else -> PointcutEvaluation.UNSUPPORTED
        }
    }

    private fun splitTopLevel(
        expression: String,
        delimiter: String,
    ): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0
        while (index < expression.length) {
            when {
                expression[index] == '(' -> {
                    depth += 1
                    current.append(expression[index])
                    index += 1
                }

                expression[index] == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(expression[index])
                    index += 1
                }

                depth == 0 && expression.startsWith(delimiter, index) -> {
                    parts += current.toString().trim()
                    current.clear()
                    index += delimiter.length
                }

                else -> {
                    current.append(expression[index])
                    index += 1
                }
            }
        }

        if (current.isNotBlank()) {
            parts += current.toString().trim()
        }
        return parts.filter { it.isNotBlank() }
    }

    private fun unwrapOuterParens(expression: String): String {
        var current = expression
        while (current.startsWith('(') && current.endsWith(')')) {
            val inner = current.substring(1, current.length - 1).trim()
            if (!isWrappedByOuterParens(inner)) {
                return current
            }
            current = inner
        }
        return current
    }

    private fun isWrappedByOuterParens(expression: String): Boolean {
        var depth = 0
        expression.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth < 0) {
                        return false
                    }
                    if (depth == 0 && index != expression.lastIndex) {
                        return false
                    }
                }
            }
        }
        return depth == 0
    }

    private fun wildcardMatches(
        pattern: String,
        candidate: String,
    ): Boolean {
        val regex = buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                when {
                    pattern.startsWith("..", index) -> {
                        append("(?:\\.[^.]+)*")
                        index += 2
                    }

                    pattern[index] == '*' -> {
                        append("[^.]*")
                        index += 1
                    }

                    pattern[index] == '.' -> {
                        append("\\.")
                        index += 1
                    }

                    else -> {
                        append(Regex.escape(pattern[index].toString()))
                        index += 1
                    }
                }
            }
            append('$')
        }
        return Regex(regex).matches(candidate)
    }

    private fun providerClasses(serviceInterface: PsiClass, serviceName: String, context: ResolverContext): List<PsiClass> {
        val descriptorProviders = context.filesNamed(serviceName)
            .flatMap(::parseProviderNames)
            .mapNotNull { providerName ->
                JavaPsiFacade.getInstance(serviceInterface.project)
                    .findClass(providerName, GlobalSearchScope.projectScope(serviceInterface.project))
            }

        if (descriptorProviders.isNotEmpty()) {
            return descriptorProviders.distinctBy { it.qualifiedName ?: it.name ?: "" }
                .sortedBy { it.qualifiedName ?: it.name ?: "" }
        }

        return context.allProjectClasses()
            .filter { candidate ->
                !candidate.isInterface &&
                    candidate.qualifiedName != null &&
                    candidate.isInheritor(serviceInterface, true)
            }
            .sortedBy { it.qualifiedName ?: it.name ?: "" }
    }

    private fun parseProviderNames(file: PsiFile): List<String> {
        return file.text.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { line -> line.isNotBlank() }
            .toList()
    }

    private fun resolveClassFromReflectionQualifier(expression: PsiExpression?): PsiClass? {
        return when (expression) {
            is PsiMethodCallExpression -> {
                if (expression.methodExpression.referenceName != "forName") {
                    return null
                }
                val className = ResolverSupport.literalString(expression.argumentList.expressions.firstOrNull()) ?: return null
                JavaPsiFacade.getInstance(expression.project)
                    .findClass(className, GlobalSearchScope.projectScope(expression.project))
            }
            else -> resolveClassLiteral(expression)
        }
    }

    private fun resolveClassLiteral(expression: PsiExpression?): PsiClass? {
        val classObject = expression as? PsiClassObjectAccessExpression ?: return null
        return (classObject.operand.type as? PsiClassType)?.resolve()
    }

    private fun resolveFirstClassLiteral(expression: PsiExpression?): PsiClass? {
        val classObject = PsiTreeUtil.findChildOfType(expression, PsiClassObjectAccessExpression::class.java) ?: return null
        return (classObject.operand.type as? PsiClassType)?.resolve()
    }

    private fun matchesStaticOwner(
        callExpression: PsiMethodCallExpression,
        qualifiedOwnerName: String,
        shortOwnerName: String,
    ): Boolean {
        val resolvedOwner = callExpression.resolveMethod()?.containingClass?.qualifiedName
        if (resolvedOwner == qualifiedOwnerName) {
            return true
        }

        val qualifierText = callExpression.methodExpression.qualifierExpression?.text
        return qualifierText == shortOwnerName || qualifierText == qualifiedOwnerName
    }

    private fun uncertainNode(
        rawKey: String,
        title: String,
        sourceKind: String,
        reason: String,
    ): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.UNCERTAIN_LINK, rawKey),
            type = NodeType.UNCERTAIN_LINK,
            title = title,
            sourceKind = sourceKind,
            certainty = Certainty.RULE_INFERRED,
            uncertainty = GraphUncertainty(
                reason = reason,
                confidence = 0.4,
            ),
        )
    }

    private fun uncertainEdge(
        type: EdgeType,
        fromNodeId: String,
        toNodeId: String,
        reason: String,
    ): GraphEdge {
        return GraphEdge(
            id = GraphEdge.stableId(type, fromNodeId, toNodeId),
            type = type,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            certainty = Certainty.RULE_INFERRED,
            uncertainty = GraphUncertainty(
                reason = reason,
                confidence = 0.4,
            ),
        )
    }

    private data class AdviceDescriptor(
        val annotation: com.intellij.psi.PsiAnnotation,
        val type: String,
    )

    private enum class PointcutEvaluation {
        MATCHED,
        NOT_MATCHED,
        UNSUPPORTED,
    }

    private companion object {
        val ASPECT_ANNOTATIONS = setOf("org.aspectj.lang.annotation.Aspect")
        val POINTCUT_ANNOTATIONS = setOf("org.aspectj.lang.annotation.Pointcut")
        val AOP_ADVICE_ANNOTATIONS = listOf(
            "AROUND" to setOf("org.aspectj.lang.annotation.Around"),
            "BEFORE" to setOf("org.aspectj.lang.annotation.Before"),
            "AFTER" to setOf("org.aspectj.lang.annotation.After"),
            "AFTER_RETURNING" to setOf("org.aspectj.lang.annotation.AfterReturning"),
            "AFTER_THROWING" to setOf("org.aspectj.lang.annotation.AfterThrowing"),
        )
    }
}
