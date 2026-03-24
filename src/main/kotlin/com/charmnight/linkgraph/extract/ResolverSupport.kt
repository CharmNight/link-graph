package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

object ResolverSupport {
    private val requestMappingAnnotations = setOf(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
    )

    private val controllerAnnotations = setOf(
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
    )

    private val shortRequestMappingAnnotations = requestMappingAnnotations.map { it.substringAfterLast('.') }.toSet()
    private val shortControllerAnnotations = controllerAnnotations.map { it.substringAfterLast('.') }.toSet()

    fun annotationName(annotation: PsiAnnotation): String {
        return annotation.qualifiedName ?: annotation.text.removePrefix("@").substringBefore("(")
    }

    fun hasAnyAnnotation(owner: PsiModifierListOwner, names: Set<String>): Boolean {
        val shortNames = names.map { it.substringAfterLast('.') }.toSet()
        return owner.annotations.any { annotation ->
            val name = annotationName(annotation)
            name in names || name.substringAfterLast('.') in shortNames
        }
    }

    fun findAnnotation(owner: PsiModifierListOwner, names: Set<String>): PsiAnnotation? {
        val shortNames = names.map { it.substringAfterLast('.') }.toSet()
        return owner.annotations.firstOrNull { annotation ->
            val name = annotationName(annotation)
            name in names || name.substringAfterLast('.') in shortNames
        }
    }

    fun annotationString(annotation: PsiAnnotation?, vararg attributeNames: String): String? {
        val names = if (attributeNames.isEmpty()) arrayOf("value") else attributeNames
        for (attributeName in names) {
            val values = memberValueStrings(annotation?.findAttributeValue(attributeName))
            if (values.isNotEmpty()) {
                return values.first()
            }
        }
        return null
    }

    fun annotationStrings(annotation: PsiAnnotation?, vararg attributeNames: String): List<String> {
        val names = if (attributeNames.isEmpty()) arrayOf("value") else attributeNames
        return names.flatMap { attributeName ->
            memberValueStrings(annotation?.findAttributeValue(attributeName))
        }.distinct()
    }

    fun requestMethod(method: PsiMethod): String? {
        return when {
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.GetMapping")) -> "GET"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PostMapping")) -> "POST"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PutMapping")) -> "PUT"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.DeleteMapping")) -> "DELETE"
            else -> null
        }
    }

    fun requestPath(ownerClass: PsiClass): String? {
        return findAnnotation(ownerClass, setOf("org.springframework.web.bind.annotation.RequestMapping"))
            ?.let { annotationString(it, "value", "path") }
    }

    fun requestPath(method: PsiMethod): String? {
        return findAnnotation(method, requestMappingAnnotations)
            ?.let { annotationString(it, "value", "path") }
    }

    fun combinePaths(classPath: String?, methodPath: String?): String? {
        val methodPart = methodPath ?: return null
        val parts = listOfNotNull(classPath, methodPart)
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
        return "/" + parts.joinToString("/")
    }

    fun isController(psiClass: PsiClass): Boolean = hasAnyAnnotation(psiClass, controllerAnnotations)

    fun endpointSignature(method: PsiMethod): Pair<String, String>? {
        val ownerClass = method.containingClass ?: return null
        if (!isController(ownerClass)) {
            return null
        }
        val httpMethod = requestMethod(method) ?: return null
        val path = combinePaths(requestPath(ownerClass), requestPath(method)) ?: return null
        return httpMethod to path
    }

    fun endpointNode(
        javaResolver: JavaResolver,
        method: PsiMethod,
        httpMethod: String,
        path: String,
        sourceKind: String,
    ): GraphNode {
        return endpointNode(
            httpMethod = httpMethod,
            path = path,
            location = javaResolver.methodNode(method).location,
            sourceKind = sourceKind,
        )
    }

    fun endpointNode(
        httpMethod: String,
        path: String,
        location: String?,
        sourceKind: String,
    ): GraphNode {
        return GraphNode(
            id = GraphNode.stableId(NodeType.HTTP_ENDPOINT, "$httpMethod $path"),
            type = NodeType.HTTP_ENDPOINT,
            title = "$httpMethod $path",
            location = location,
            sourceKind = sourceKind,
            metadata = mapOf(
                "path" to path,
                "httpMethod" to httpMethod,
            ),
        )
    }

    fun listenerTopics(method: PsiMethod): List<String> {
        return listOfNotNull(
            findAnnotation(method, setOf("org.springframework.kafka.annotation.KafkaListener")),
            findAnnotation(method, setOf("org.apache.rocketmq.spring.annotation.RocketMQMessageListener")),
        ).flatMap { annotation ->
            annotationStrings(annotation, "topics", "topic", "value")
        }.distinct()
    }

    fun literalString(value: PsiAnnotationMemberValue?): String? {
        return memberValueStrings(value).firstOrNull()
    }

    fun locationOf(owner: PsiDocCommentOwner): String? {
        val file = owner.containingFile ?: return null
        return locationOf(file, owner.textRange?.startOffset ?: return null)
    }

    fun locationOf(file: PsiFile, marker: String): String? {
        val text = file.text
        val offset = text.indexOf(marker)
        if (offset < 0) {
            return null
        }
        return locationOf(file, offset)
    }

    private fun locationOf(file: PsiFile, offset: Int): String {
        val line = file.text.take(offset).count { it == '\n' } + 1
        val path = file.virtualFile?.path ?: file.name
        return "$path:$line"
    }

    private fun memberValueStrings(value: PsiAnnotationMemberValue?): List<String> {
        return when (value) {
            null -> emptyList()
            is PsiLiteralExpression -> listOfNotNull(value.value as? String)
            is PsiArrayInitializerMemberValue -> value.initializers.flatMap(::memberValueStrings)
            else -> {
                val text = value.text.trim().trim('"')
                if (text.isBlank()) {
                    emptyList()
                } else {
                    listOf(text)
                }
            }
        }
    }
}
