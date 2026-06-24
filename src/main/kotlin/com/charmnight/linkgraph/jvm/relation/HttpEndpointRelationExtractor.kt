package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression

/** HTTP 端点信息，记录 HTTP 方法与路径。 */
data class HttpEndpoint(
    /** HTTP 谓词，例如 GET、POST。 */
    val method: String,
    /** 端点路径，例如 /api/foo。 */
    val path: String,
)

/** HTTP 端点关系提取器：基于 Spring/Feign 注解识别控制器的对外端点。 */
object HttpEndpointRelationExtractor {
    /** 解析 Spring 控制器方法的 HTTP 端点（结合类级 RequestMapping）。 */
    fun controllerEndpoint(method: PsiMethod): HttpEndpoint? {
        val owner = method.containingClass ?: return null
        // 支持元注解：用户自定义的 @MyRestController（其本身被 @RestController 元标注）也应被识别为控制器。
        if (!owner.annotations.any { annotation ->
                annotation.transitiveAnnotations().any { it.simpleName() in CONTROLLER_ANNOTATIONS }
            }
        ) {
            return null
        }
        val httpMethod = requestMethod(method) ?: return null
        val classMapping = owner.annotations
            .firstOrNull { annotation -> annotation.transitiveAnnotations().any { it.simpleName() == "RequestMapping" } }
        val path = combinePaths(
            classMapping?.annotationStringValue("value") ?: classMapping?.annotationStringValue("path"),
            requestPath(method),
        ) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    /** 解析 Feign 客户端方法的 HTTP 端点，使用类级别路径与方法的映射注解组合。 */
    fun feignEndpoint(classPath: String?, method: PsiMethod): HttpEndpoint? {
        val httpMethod = requestMethod(method) ?: return null
        val path = combinePaths(classPath, requestPath(method)) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    /** 将 HTTP 端点转换为可纳入 JVM 索引的资源符号，便于在关系图中作为外部入口展示。 */
    fun endpointResourceSymbol(endpoint: HttpEndpoint): JvmResourceSymbol =
        JvmResourceSymbol(
            id = stableJvmId("resource", "http:${endpoint.method} ${endpoint.path}"),
            path = "http:${endpoint.method} ${endpoint.path}",
            kind = JvmResourceKind.OTHER,
            source = null,
            origin = SourceOrigin.PROJECT_SOURCE,
        )

    /** 根据 Spring Mapping 注解推断方法对应的 HTTP 谓词，无法识别时返回 null。 */
    fun requestMethod(method: PsiMethod): String? {
        method.annotations.forEach { annotation ->
            // 把当前注解及其元注解链一起检查，支持 @MyGetMapping（其本身被 @GetMapping 元标注）
            for (anno in annotation.transitiveAnnotations()) {
                when (anno.simpleName()) {
                    "GetMapping" -> return "GET"
                    "PostMapping" -> return "POST"
                    "PutMapping" -> return "PUT"
                    "DeleteMapping" -> return "DELETE"
                    "PatchMapping" -> return "PATCH"
                    "RequestMapping" -> {
                        val methodValue = anno.findDeclaredAttributeValue("method") ?: continue
                        methodValue.resolveHttpMethod()?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /**
     * BFS 遍历注解及其元注解链：返回包含自身 + 所有可达元注解的列表。
     *
     * Spring 的组合注解模式（如 @GetMapping 内部元标注 @RequestMapping）依赖此遍历才能被识别。
     * 限制 maxDepth=4 防止循环引用 / 过度遍历。
     */
    private fun PsiAnnotation.transitiveAnnotations(maxDepth: Int = 4): List<PsiAnnotation> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<PsiAnnotation>()
        val queue = ArrayDeque<Pair<PsiAnnotation, Int>>()
        queue.addLast(this to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val key = current.qualifiedName ?: current.nameReferenceElement?.referenceName ?: continue
            if (!visited.add(key)) continue
            result += current
            if (depth >= maxDepth) continue
            val resolved = current.nameReferenceElement?.resolve() as? com.intellij.psi.PsiClass ?: continue
            resolved.annotations.forEach { meta ->
                queue.addLast(meta to depth + 1)
            }
        }
        return result
    }

    /**
     * 把 `@RequestMapping(method = ...)` 的成员值解析为 HTTP 谓词。
     *
     * 旧实现用 `value.text.contains("GET")` 子串匹配，会把 `@RequestMapping(method = Foo.GETsomething)`
     * 这类无关标识符误判为 GET，同时漏掉全限定名 `RequestMethod.GET` 之外的形式。
     * 现在统一通过 PSI 引用解析到枚举常量（`PsiEnumConstant`），从常量名取 HTTP 谓词。
     */
    private fun PsiAnnotationMemberValue.resolveHttpMethod(): String? = when (this) {
        is PsiReferenceExpression -> {
            val resolved = resolve()
            if (resolved is PsiEnumConstant) {
                resolved.name.uppercase()
            } else {
                null
            }
        }
        is PsiArrayInitializerMemberValue -> {
            initializers.firstNotNullOfOrNull { member -> member.resolveHttpMethod() }
        }
        else -> null
    }

    /** 从 Mapping 注解中提取路径，优先 value 属性，缺失时回退到 path 属性。支持元注解链。 */
    fun requestPath(method: PsiMethod): String? =
        method.annotations
            .firstNotNullOfOrNull { annotation ->
                annotation.transitiveAnnotations().firstOrNull { anno ->
                    anno.simpleName() in REQUEST_MAPPING_ANNOTATIONS
                }
            }
            ?.let { annotation -> annotation.annotationStringValue("value") ?: annotation.annotationStringValue("path") }

    /** 合并类级与方法级路径片段，归一化为以 / 开头、不含多余斜杠的形式。 */
    fun combinePaths(classPath: String?, methodPath: String?): String? {
        val methodPart = methodPath ?: return null
        val parts = listOfNotNull(classPath, methodPart)
            .map { part -> part.trim().trim('/') }
            .filter(String::isNotBlank)
        return "/" + parts.joinToString("/")
    }

    /** 取注解的简短名称，优先使用全限定名末尾，回退到注解引用名。 */
    fun PsiAnnotation.simpleName(): String? =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName

    /** 取注解指定属性的字符串字面量值；当查询 value 时也兼容默认属性写法。 */
    fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    /** 把注解成员值（字面量或数组初始化器）解析为字符串，数组取首个元素。 */
    private fun com.intellij.psi.PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    /** 标识 Spring 控制器的注解集合，用于判定类是否承载 HTTP 端点。 */
    private val CONTROLLER_ANNOTATIONS = setOf("Controller", "RestController")
    /** 所有用于映射 HTTP 路径的 Spring 注解集合。 */
    private val REQUEST_MAPPING_ANNOTATIONS = setOf(
        "RequestMapping",
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "DeleteMapping",
        "PatchMapping",
    )
}
