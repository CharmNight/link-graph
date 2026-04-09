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

/**
 * 提供提取阶段通用的注解、路径和定位辅助逻辑。
 * 这里集中封装 Spring HTTP 映射、消息监听等解析细节，避免各个解析器重复实现。
 */
object ResolverSupport {
    /** 完整限定名形式的请求映射注解集合。 */
    private val requestMappingAnnotations = setOf(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
    )

    /** 用于识别控制器类型的完整限定名注解集合。 */
    private val controllerAnnotations = setOf(
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
    )

    /** 请求映射注解的短名集合，便于兼容导入后书写形式。 */
    private val shortRequestMappingAnnotations = requestMappingAnnotations.map { it.substringAfterLast('.') }.toSet()
    /** 控制器注解的短名集合，便于兼容导入后书写形式。 */
    private val shortControllerAnnotations = controllerAnnotations.map { it.substringAfterLast('.') }.toSet()

    /** 获取注解的标准名称，优先返回限定名，失败时回退到源码文本。 */
    fun annotationName(annotation: PsiAnnotation): String {
        return annotation.qualifiedName ?: annotation.text.removePrefix("@").substringBefore("(")
    }

    /** 判断给定元素是否带有任一目标注解。 */
    fun hasAnyAnnotation(owner: PsiModifierListOwner, names: Set<String>): Boolean {
        /** 目标注解的短名集合。 */
        val shortNames = names.map { it.substringAfterLast('.') }.toSet()
        return owner.annotations.any { annotation ->
            /** 当前注解解析出的名称。 */
            val name = annotationName(annotation)
            name in names || name.substringAfterLast('.') in shortNames
        }
    }

    /** 查找元素上第一个命中的目标注解。 */
    fun findAnnotation(owner: PsiModifierListOwner, names: Set<String>): PsiAnnotation? {
        /** 目标注解的短名集合。 */
        val shortNames = names.map { it.substringAfterLast('.') }.toSet()
        return owner.annotations.firstOrNull { annotation ->
            /** 当前注解解析出的名称。 */
            val name = annotationName(annotation)
            name in names || name.substringAfterLast('.') in shortNames
        }
    }

    /** 从注解属性中提取单个字符串值，按属性名顺序依次尝试。 */
    fun annotationString(annotation: PsiAnnotation?, vararg attributeNames: String): String? {
        /** 实际参与查找的属性名列表，默认读取 `value`。 */
        val names = if (attributeNames.isEmpty()) arrayOf("value") else attributeNames
        for (attributeName in names) {
            /** 当前属性解析出的所有字符串值。 */
            val values = memberValueStrings(annotation?.findAttributeValue(attributeName))
            if (values.isNotEmpty()) {
                return values.first()
            }
        }
        return null
    }

    /** 从注解属性中提取多个字符串值，并做去重。 */
    fun annotationStrings(annotation: PsiAnnotation?, vararg attributeNames: String): List<String> {
        /** 实际参与查找的属性名列表，默认读取 `value`。 */
        val names = if (attributeNames.isEmpty()) arrayOf("value") else attributeNames
        return names.flatMap { attributeName ->
            memberValueStrings(annotation?.findAttributeValue(attributeName))
        }.distinct()
    }

    /** 解析 HTTP 方法注解并映射成标准请求动词。 */
    fun requestMethod(method: PsiMethod): String? {
        return when {
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.GetMapping")) -> "GET"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PostMapping")) -> "POST"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PutMapping")) -> "PUT"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.DeleteMapping")) -> "DELETE"
            else -> null
        }
    }

    /** 读取控制器类上的基础请求路径。 */
    fun requestPath(ownerClass: PsiClass): String? {
        return findAnnotation(ownerClass, setOf("org.springframework.web.bind.annotation.RequestMapping"))
            ?.let { annotationString(it, "value", "path") }
    }

    /** 读取方法上的请求路径。 */
    fun requestPath(method: PsiMethod): String? {
        return findAnnotation(method, requestMappingAnnotations)
            ?.let { annotationString(it, "value", "path") }
    }

    /** 合并类级路径和方法级路径，输出统一的绝对路径格式。 */
    fun combinePaths(classPath: String?, methodPath: String?): String? {
        /** 方法级路径不能为空，否则无法形成完整 endpoint。 */
        val methodPart = methodPath ?: return null
        /** 去除两端斜杠与空白后的路径片段集合。 */
        val parts = listOfNotNull(classPath, methodPart)
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
        return "/" + parts.joinToString("/")
    }

    /** 判断类是否为 Spring MVC 控制器。 */
    fun isController(psiClass: PsiClass): Boolean = hasAnyAnnotation(psiClass, controllerAnnotations)

    /** 解析控制器方法对应的 `(HTTP_METHOD, PATH)` 签名。 */
    fun endpointSignature(method: PsiMethod): Pair<String, String>? {
        /** 方法所属类，缺失时无法构造完整 endpoint。 */
        val ownerClass = method.containingClass ?: return null
        if (!isController(ownerClass)) {
            return null
        }
        /** 方法上的 HTTP 动词。 */
        val httpMethod = requestMethod(method) ?: return null
        /** 合并控制器与方法路径后的完整路由。 */
        val path = combinePaths(requestPath(ownerClass), requestPath(method)) ?: return null
        return httpMethod to path
    }

    /** 基于方法 PSI 和 Java 解析器创建 endpoint 节点。 */
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

    /** 基于 endpoint 关键字段直接构造图节点。 */
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

    /** 读取 Kafka 或 RocketMQ 监听器上的 topic 列表。 */
    fun listenerTopics(method: PsiMethod): List<String> {
        return listOfNotNull(
            findAnnotation(method, setOf("org.springframework.kafka.annotation.KafkaListener")),
            findAnnotation(method, setOf("org.apache.rocketmq.spring.annotation.RocketMQMessageListener")),
        ).flatMap { annotation ->
            annotationStrings(annotation, "topics", "topic", "value")
        }.distinct()
    }

    /** 从注解属性值中提取第一个字符串字面量。 */
    fun literalString(value: PsiAnnotationMemberValue?): String? {
        return memberValueStrings(value).firstOrNull()
    }

    /** 计算带文档注释 PSI 元素的源码定位。 */
    fun locationOf(owner: PsiDocCommentOwner): String? {
        /** 所属文件，用于拼接文件路径和行号。 */
        val file = owner.containingFile ?: return null
        return locationOf(file, owner.textRange?.startOffset ?: return null)
    }

    /** 在文件中搜索标记字符串并返回对应源码定位。 */
    fun locationOf(file: PsiFile, marker: String): String? {
        /** 文件完整文本，用于查找标记位置。 */
        val text = file.text
        /** 标记字符串在文件中的偏移量。 */
        val offset = text.indexOf(marker)
        if (offset < 0) {
            return null
        }
        return locationOf(file, offset)
    }

    /** 根据文件与偏移量换算出 `path:line` 形式的位置字符串。 */
    private fun locationOf(file: PsiFile, offset: Int): String {
        /** 偏移量之前换行符数量对应的行号。 */
        val line = file.text.take(offset).count { it == '\n' } + 1
        /** 优先使用虚拟文件路径，回退到文件名。 */
        val path = file.virtualFile?.path ?: file.name
        return "$path:$line"
    }

    /** 递归读取注解属性值中的所有字符串内容。 */
    private fun memberValueStrings(value: PsiAnnotationMemberValue?): List<String> {
        return when (value) {
            null -> emptyList()
            is PsiLiteralExpression -> listOfNotNull(value.value as? String)
            is PsiArrayInitializerMemberValue -> value.initializers.flatMap(::memberValueStrings)
            else -> {
                /** 非字面量场景下直接取源码文本做保底。 */
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
