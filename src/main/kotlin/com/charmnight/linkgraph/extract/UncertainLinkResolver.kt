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

class UncertainLinkResolver(
    private val javaResolver: JavaResolver = JavaResolver(),
) {
    fun resolve(method: PsiMethod, context: ResolverContext): ResolverOutput {
        val body = method.body ?: return ResolverOutput()
        val methodNodeId = GraphNode.stableId(NodeType.METHOD, javaResolver.methodKey(method))
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val additionalMethods = linkedSetOf<PsiMethod>()

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
}
