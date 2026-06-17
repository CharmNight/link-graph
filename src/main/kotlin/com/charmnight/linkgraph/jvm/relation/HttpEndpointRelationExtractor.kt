package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod

data class HttpEndpoint(
    val method: String,
    val path: String,
)

object HttpEndpointRelationExtractor {
    fun controllerEndpoint(method: PsiMethod): HttpEndpoint? {
        val owner = method.containingClass ?: return null
        if (!owner.annotations.any { annotation -> annotation.simpleName() in CONTROLLER_ANNOTATIONS }) {
            return null
        }
        val httpMethod = requestMethod(method) ?: return null
        val classMapping = owner.annotations.firstOrNull { annotation -> annotation.simpleName() == "RequestMapping" }
        val path = combinePaths(
            classMapping?.annotationStringValue("value") ?: classMapping?.annotationStringValue("path"),
            requestPath(method),
        ) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    fun feignEndpoint(classPath: String?, method: PsiMethod): HttpEndpoint? {
        val httpMethod = requestMethod(method) ?: return null
        val path = combinePaths(classPath, requestPath(method)) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    fun endpointResourceSymbol(endpoint: HttpEndpoint): JvmResourceSymbol =
        JvmResourceSymbol(
            id = stableJvmId("resource", "http:${endpoint.method} ${endpoint.path}"),
            path = "http:${endpoint.method} ${endpoint.path}",
            kind = JvmResourceKind.OTHER,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )

    fun requestMethod(method: PsiMethod): String? {
        method.annotations.forEach { annotation ->
            when (annotation.simpleName()) {
                "GetMapping" -> return "GET"
                "PostMapping" -> return "POST"
                "PutMapping" -> return "PUT"
                "DeleteMapping" -> return "DELETE"
                "PatchMapping" -> return "PATCH"
                "RequestMapping" -> {
                    val methodValue = annotation.findDeclaredAttributeValue("method")?.text.orEmpty().uppercase()
                    HTTP_METHODS.firstOrNull { value -> methodValue.contains(value) }?.let { return it }
                }
            }
        }
        return null
    }

    fun requestPath(method: PsiMethod): String? =
        method.annotations
            .firstOrNull { annotation -> annotation.simpleName() in REQUEST_MAPPING_ANNOTATIONS }
            ?.let { annotation -> annotation.annotationStringValue("value") ?: annotation.annotationStringValue("path") }

    fun combinePaths(classPath: String?, methodPath: String?): String? {
        val methodPart = methodPath ?: return null
        val parts = listOfNotNull(classPath, methodPart)
            .map { part -> part.trim().trim('/') }
            .filter(String::isNotBlank)
        return "/" + parts.joinToString("/")
    }

    fun PsiAnnotation.simpleName(): String? =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName

    fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    private fun com.intellij.psi.PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    private val CONTROLLER_ANNOTATIONS = setOf("Controller", "RestController")
    private val REQUEST_MAPPING_ANNOTATIONS = setOf(
        "RequestMapping",
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "DeleteMapping",
        "PatchMapping",
    )
    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
}
