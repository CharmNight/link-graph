package com.charmnight.linkgraph.semantic.provider.code.relation

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticIdFactory
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.charmnight.linkgraph.semantic.subject.sourcePathOf
import com.charmnight.linkgraph.semantic.subject.sourceRangeOf
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiClass

/**
 * 解析代码方法与资源单元之间的绑定关系。
 * 当前覆盖 MyBatis 语句映射和 Spring 控制器 endpoint 绑定。
 * Feign 代理和路由事实由 JVM framework relation index 统一产出。
 */
class ResourceBindingSemanticResolver : CodeRelationSemanticResolver {
    /** 从 MyBatis XML 中提取 namespace 的正则表达式。 */
    private val myBatisNamespaceRegex = Regex("""<mapper\b[^>]*namespace\s*=\s*"([^"]+)"""")
    /** 支持识别的请求映射注解集合。 */
    private val requestMappingAnnotations = setOf(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
    )
    /** 用于判断控制器类的注解集合。 */
    private val controllerAnnotations = setOf(
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
    )

    /** 汇总解析给定方法涉及的资源绑定关系。 */
    override fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction {
        return mergeExtractions(
            resolveMyBatisBinding(method, context),
            resolveControllerEndpointBinding(method),
        )
    }

    /** 解析 Mapper 接口方法与 MyBatis 语句之间的绑定关系。 */
    private fun resolveMyBatisBinding(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction {
        /** 方法所属接口。 */
        val ownerClass = method.containingClass ?: return RelationExtraction()
        /** Mapper 的 namespace。 */
        val namespace = ownerClass.qualifiedName ?: return RelationExtraction()
        if (!ownerClass.isInterface) {
            return RelationExtraction()
        }

        /** 在全部 XML 中找到的目标语句。 */
        val statementMatch = context.allXmlFiles()
            .asSequence()
            .mapNotNull { file ->
                findMyBatisStatement(
                    file = file,
                    namespace = namespace,
                    statementId = method.name,
                )
            }
            .firstOrNull()
            ?: return RelationExtraction()

        /** 与方法绑定的 SQL 资源单元。 */
        val resourceUnit = ResourceUnit(
            id = SemanticIdFactory.resourceUnitId(
                resourceKind = "sql",
                subjectId = SemanticIdFactory.compose("resource-mybatis", "$namespace.${statementMatch.statementId}"),
            ),
            title = "SQL ${ownerClass.name}.${statementMatch.statementId}",
            resourceKind = "MYBATIS_STATEMENT",
            metadata = mapOf(
                "namespace" to namespace,
                "statementId" to statementMatch.statementId,
                "statementType" to statementMatch.statementType,
                "path" to sourcePathOf(statementMatch.file),
            ),
        )
        /** 方法到 SQL 资源的绑定关系。 */
        val relation = SemanticRelation(
            kind = SemanticRelationKind.BINDS_TO,
            fromUnitId = SemanticIdFactory.methodUnitId(methodSignature(method)),
            toUnitId = resourceUnit.id,
            label = "MYBATIS_STATEMENT",
        )
        /** SQL 资源到源码文件的映射。 */
        val sourceMapping = SourceMapping(
            sourcePath = sourcePathOf(statementMatch.file),
            sourceRange = sourceRangeOf(statementMatch.file, statementMatch.range),
            targetUnitId = resourceUnit.id,
        )
        return RelationExtraction(
            semanticUnits = listOf(resourceUnit),
            relations = listOf(relation),
            sourceMappings = listOf(sourceMapping),
        )
    }

    /** 解析控制器方法本身与 HTTP endpoint 资源单元之间的绑定关系。 */
    private fun resolveControllerEndpointBinding(method: PsiMethod): RelationExtraction {
        /** 当前方法映射出的 endpoint 签名。 */
        val endpointSignature = endpointSignature(method) ?: return RelationExtraction()
        /** endpoint 对应的资源单元。 */
        val endpointUnit = httpEndpointUnit(
            httpMethod = endpointSignature.first,
            path = endpointSignature.second,
        )
        /** 方法到 endpoint 的引用关系。 */
        val relation = SemanticRelation(
            kind = SemanticRelationKind.REFERENCES,
            fromUnitId = SemanticIdFactory.methodUnitId(methodSignature(method)),
            toUnitId = endpointUnit.id,
            label = EdgeType.ROUTES_TO.name,
        )
        return RelationExtraction(
            semanticUnits = listOf(endpointUnit),
            relations = listOf(relation),
            sourceMappings = listOfNotNull(sourceMappingOf(endpointUnit.id, method.navigationElement ?: method)),
        )
    }

    /** 在 XML 文件中查找指定 namespace 和 statementId 对应的 MyBatis 语句。 */
    private fun findMyBatisStatement(
        file: PsiFile,
        namespace: String,
        statementId: String,
    ): MyBatisStatementMatch? {
        /** XML 文件的完整文本。 */
        val text = file.text
        /** XML 中声明的 namespace。 */
        val discoveredNamespace = myBatisNamespaceRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        if (discoveredNamespace != namespace) {
            return null
        }
        /** 精确定位 statement 标签的正则表达式。 */
        val statementRegex = Regex(
            """<(select|insert|update|delete)\b[^>]*id\s*=\s*"${Regex.escape(statementId)}"[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        /** 语句标签在 XML 中的匹配结果。 */
        val match = statementRegex.find(text) ?: return null
        return MyBatisStatementMatch(
            file = file,
            statementId = statementId,
            statementType = match.groupValues[1].lowercase(),
            range = TextRange(match.range.first, match.range.last + 1),
        )
    }

    /** 描述一次 MyBatis statement 的定位结果。 */
    private data class MyBatisStatementMatch(
        /** 命中的 XML 文件。 */
        val file: PsiFile,
        /** statement 的 id 属性。 */
        val statementId: String,
        /** statement 标签类型，如 select 或 update。 */
        val statementType: String,
        /** statement 标签在源码中的文本范围。 */
        val range: TextRange,
    )

    /** 构造 HTTP endpoint 对应的资源单元。 */
    private fun httpEndpointUnit(
        httpMethod: String,
        path: String,
    ): ResourceUnit {
        return ResourceUnit(
            id = SemanticIdFactory.resourceUnitId(
                resourceKind = "http-endpoint",
                subjectId = "$httpMethod $path",
            ),
            title = "$httpMethod $path",
            resourceKind = "HTTP_ENDPOINT",
            metadata = mapOf(
                "httpMethod" to httpMethod,
                "path" to path,
            ),
        )
    }

    /** 根据 PSI 元素生成资源单元的源码映射。 */
    private fun sourceMappingOf(
        targetUnitId: String,
        element: PsiElement,
    ): SourceMapping? {
        /** 目标元素所在文件。 */
        val file = element.containingFile ?: return null
        /** 目标元素在文件中的文本范围。 */
        val range = element.textRange ?: return null
        return SourceMapping(
            sourcePath = sourcePathOf(file),
            sourceRange = sourceRangeOf(file, range),
            targetUnitId = targetUnitId,
        )
    }

    /** 解析控制器方法的 endpoint 签名。 */
    private fun endpointSignature(method: PsiMethod): Pair<String, String>? {
        /** 方法所属类。 */
        val ownerClass = method.containingClass ?: return null
        if (!hasAnyAnnotation(ownerClass, controllerAnnotations)) {
            return null
        }
        /** 方法上的 HTTP 动词。 */
        val httpMethod = requestMethod(method) ?: return null
        /** 控制器类路径与方法路径组合后的完整路由。 */
        val path = combinePaths(requestPath(ownerClass), requestPath(method)) ?: return null
        return httpMethod to path
    }

    /** 解析方法上的请求映射注解并返回标准 HTTP 动词。 */
    private fun requestMethod(method: PsiMethod): String? {
        return when {
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.GetMapping")) -> "GET"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PostMapping")) -> "POST"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.PutMapping")) -> "PUT"
            hasAnyAnnotation(method, setOf("org.springframework.web.bind.annotation.DeleteMapping")) -> "DELETE"
            else -> null
        }
    }

    /** 读取类级别的请求路径。 */
    private fun requestPath(ownerClass: PsiClass): String? {
        return findAnnotation(ownerClass, setOf("org.springframework.web.bind.annotation.RequestMapping"))
            ?.let { annotation -> annotationString(annotation, "value", "path") }
    }

    /** 读取方法级别的请求路径。 */
    private fun requestPath(method: PsiMethod): String? {
        return findAnnotation(method, requestMappingAnnotations)
            ?.let { annotation -> annotationString(annotation, "value", "path") }
    }

    /** 合并类级路径和方法级路径。 */
    private fun combinePaths(
        classPath: String?,
        methodPath: String?,
    ): String? {
        /** 方法路径不能为空。 */
        val methodPart = methodPath ?: return null
        /** 清理空白和首尾斜杠后的路径片段。 */
        val parts = listOfNotNull(classPath, methodPart)
            .map { part -> part.trim().trim('/') }
            .filter { part -> part.isNotBlank() }
        return "/" + parts.joinToString("/")
    }

    /** 按属性名顺序读取注解中的单个字符串值。 */
    private fun annotationString(
        annotation: PsiAnnotation?,
        vararg attributeNames: String,
    ): String? {
        /** 实际参与查找的属性名列表。 */
        val names = if (attributeNames.isEmpty()) arrayOf("value") else attributeNames
        names.forEach { attributeName ->
            /** 当前属性解析出的字符串列表。 */
            val values = memberValueStrings(annotation?.findAttributeValue(attributeName))
            if (values.isNotEmpty()) {
                return values.first()
            }
        }
        return null
    }

    /** 递归解析注解属性值中的所有字符串。 */
    private fun memberValueStrings(value: PsiAnnotationMemberValue?): List<String> {
        return when (value) {
            null -> emptyList()
            is PsiLiteralExpression -> listOfNotNull(value.value as? String)
            is PsiArrayInitializerMemberValue -> value.initializers.flatMap(::memberValueStrings)
            else -> {
                /** 非字面量场景下回退到源码文本。 */
                val text = value.text.trim().trim('"')
                if (text.isBlank()) {
                    emptyList()
                } else {
                    listOf(text)
                }
            }
        }
    }

    /** 从修饰符拥有者上查找首个命中的注解。 */
    private fun findAnnotation(
        owner: PsiModifierListOwner,
        names: Set<String>,
    ): PsiAnnotation? {
        /** 目标注解的短名集合。 */
        val shortNames = names.map { name -> name.substringAfterLast('.') }.toSet()
        return owner.annotations.firstOrNull { annotation ->
            /** 当前注解的解析名称。 */
            val name = annotation.qualifiedName ?: annotation.text.removePrefix("@").substringBefore("(")
            name in names || name.substringAfterLast('.') in shortNames
        }
    }

    /** 判断元素上是否存在任一目标注解。 */
    private fun hasAnyAnnotation(
        owner: PsiModifierListOwner,
        names: Set<String>,
    ): Boolean = findAnnotation(owner, names) != null

    /** 合并多个提取结果，并按 ID 去重。 */
    private fun mergeExtractions(vararg extractions: RelationExtraction): RelationExtraction {
        /** 合并后的语义单元索引。 */
        val semanticUnits = linkedMapOf<String, SemanticUnit>()
        /** 合并后的关系索引。 */
        val relations = linkedMapOf<String, SemanticRelation>()
        /** 合并后的附加方法索引。 */
        val additionalMethods = linkedMapOf<String, PsiMethod>()
        /** 合并后的源码映射索引。 */
        val sourceMappings = linkedMapOf<String, SourceMapping>()

        extractions.forEach { extraction ->
            extraction.semanticUnits.forEach { unit ->
                semanticUnits.putIfAbsent(unit.id, unit)
            }
            extraction.relations.forEach { relation ->
                relations.putIfAbsent(
                    relationKey(relation.kind, relation.fromUnitId, relation.toUnitId, relation.label),
                    relation,
                )
            }
            extraction.additionalMethods.forEach { method ->
                additionalMethods.putIfAbsent(methodSignature(method), method)
            }
            extraction.sourceMappings.forEach { mapping ->
                sourceMappings.putIfAbsent(mapping.targetUnitId, mapping)
            }
        }

        return RelationExtraction(
            semanticUnits = semanticUnits.values.toList(),
            relations = relations.values.toList(),
            additionalMethods = additionalMethods.values.toList(),
            sourceMappings = sourceMappings.values.toList(),
        )
    }

    /** 为关系构造稳定去重键。 */
    private fun relationKey(
        kind: SemanticRelationKind,
        fromUnitId: String,
        toUnitId: String,
        label: String?,
    ): String {
        return listOf(kind.name, fromUnitId, toUnitId, label.orEmpty()).joinToString("|")
    }
}
