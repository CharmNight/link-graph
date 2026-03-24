package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil

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
            signature = methodSignature(method, qualifiedOwner = false),
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

    fun resolveCalls(method: PsiMethod): List<ResolvedMethodCall> {
        val body = method.body ?: return emptyList()
        val resolvedCalls = linkedMapOf<String, ResolvedMethodCall>()
        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .forEach { callExpression ->
                val resolved = callExpression.resolveMethod() ?: return@forEach
                val concreteTargets = concreteTargets(resolved)
                concreteTargets.forEach { target ->
                    val key = methodKey(target)
                    val implementationEdge = implementationEdge(resolved, target)
                    val existing = resolvedCalls[key]
                    if (existing == null) {
                        resolvedCalls[key] = ResolvedMethodCall(
                            target = target,
                            implementationEdge = implementationEdge,
                        )
                    } else if (existing.implementationEdge == null && implementationEdge != null) {
                        resolvedCalls[key] = existing.copy(implementationEdge = implementationEdge)
                    }
                }
            }
        return resolvedCalls.values.toList()
    }

    fun callEdge(fromMethod: PsiMethod, toMethod: PsiMethod): GraphEdge {
        val fromId = GraphNode.stableId(NodeType.METHOD, methodKey(fromMethod))
        val toId = GraphNode.stableId(NodeType.METHOD, methodKey(toMethod))
        return GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, fromId, toId),
            type = EdgeType.CALL,
            fromNodeId = fromId,
            toNodeId = toId,
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

        val scope = GlobalSearchScope.projectScope(resolvedMethod.project)
        val overrides = OverridingMethodsSearch.search(resolvedMethod, scope, true)
            .findAll()
            .filter { candidate -> candidate.containingClass?.let(::isConcreteClass) == true }
            .sortedBy(::methodKey)
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

    private fun locationOf(owner: PsiDocCommentOwner): String? {
        val file = owner.containingFile ?: return null
        val offset = owner.textRange?.startOffset ?: return null
        val line = file.text.take(offset).count { it == '\n' } + 1
        val path = file.virtualFile?.path ?: file.name
        return "$path:$line"
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
}

data class ResolvedMethodCall(
    val target: PsiMethod,
    val implementationEdge: ImplementationEdge? = null,
)

data class ImplementationEdge(
    val implementationClass: PsiClass,
    val contractClass: PsiClass,
)
