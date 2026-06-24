package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter

/** Spring 配置绑定关系解析器：把 @ConfigurationProperties 类与 @Value 字段关联到对应配置资源。 */
class SpringConfigBindingRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识，用于在符号索引中标记该关系来源。 */
    override val id: String = "jvm.spring-config-binding"

    /**
     * 解析 Spring 配置绑定关系：先筛出标准 Spring 配置资源，
     * 再遍历项目中的类：
     * - 对标注 @ConfigurationProperties 的类，按 prefix 绑定到声明了对应前缀键的配置资源；
     * - 对字段/方法参数上的 @Value("${key}") 注入点，按 placeholder key 绑定到声明了对应键的配置资源。
     *
     * @param context JVM 解析上下文，提供符号索引、PSI 与源码读取能力
     * @return 解析得到的配置绑定关系列表，没有配置资源时返回空列表
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val configResources = context.symbolIndex.resourcesByPath.values
            .filter(::isSpringApplicationConfigResource)
        if (configResources.isEmpty()) {
            return emptyList()
        }
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            // 1) @ConfigurationProperties(prefix = "xxx") → 资源
            psiClass.annotations
                .mapNotNull { annotation -> configurationPropertiesPrefix(annotation)?.let { prefix -> annotation to prefix } }
                .forEach { (annotation, prefix) ->
                    configResources
                        .filter { resource -> resourceDeclaresPrefix(context, resource, prefix) }
                        .forEach { resource ->
                            relations += JvmRelation(
                                id = jvmRelationId(JvmRelationKind.RESOURCE_BINDS, classSymbol.id, resource.id, prefix),
                                kind = JvmRelationKind.RESOURCE_BINDS,
                                fromSymbolId = classSymbol.id,
                                toSymbolId = resource.id,
                                confidence = JvmRelationConfidence.PROVEN,
                                source = JvmRelationSource.FRAMEWORK_RULE,
                                samples = listOf(annotation.evidence("Spring configuration properties bind prefix $prefix", classSymbol.source)),
                                metadata = mapOf(
                                    "framework" to "spring-boot",
                                    "config.prefix" to prefix,
                                    "config.resourcePath" to resource.path,
                                    "spring.configurationClass" to classSymbol.qualifiedName,
                                    "spring.annotation" to annotation.qualifiedName.orEmpty(),
                                    "spring.annotationEvidence" to "ANNOTATION_LITERAL",
                                ),
                            )
                        }
                }
            // 2) @Value("${key}") → 资源（字段、方法、构造器参数上均可能出现）
            relations += valueBindingsForClass(context, psiClass, classSymbol, configResources)
        }
        return relations
    }

    /**
     * 扫描一个 PSI 类内字段、方法与参数上的 @Value 注解，提取 placeholder key 并尝试匹配资源。
     */
    private fun valueBindingsForClass(
        context: JvmResolutionContext,
        psiClass: PsiClass,
        classSymbol: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        configResources: List<JvmResourceSymbol>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        val seen = mutableSetOf<String>() // 同一 placeholder × resource 去重
        val members: List<PsiMember> = buildList {
            addAll(psiClass.fields.asList())
            addAll(psiClass.methods.asList())
            addAll(psiClass.constructors.asList())
        }
        members.forEach { member ->
            val valueAnnotations = collectValueAnnotations(member)
            val placeholderKeys = valueAnnotations.flatMap { annotation -> placeholderKeysFromValueAnnotation(annotation) }.distinct()
            if (placeholderKeys.isEmpty()) return@forEach

            placeholderKeys.forEach { key ->
                configResources
                    .filter { resource -> resourceDeclaresPlaceholderKey(context, resource, key) }
                    .forEach { resource ->
                        val relationId = jvmRelationId(JvmRelationKind.RESOURCE_BINDS, classSymbol.id, resource.id, "value:$key")
                        if (!seen.add(relationId)) return@forEach
                        relations += JvmRelation(
                            id = relationId,
                            kind = JvmRelationKind.RESOURCE_BINDS,
                            fromSymbolId = classSymbol.id,
                            toSymbolId = resource.id,
                            confidence = JvmRelationConfidence.PROVEN,
                            source = JvmRelationSource.FRAMEWORK_RULE,
                            samples = listOf(classSymbol.evidence("Spring @Value binds placeholder $key")),
                            metadata = mapOf(
                                "framework" to "spring-boot",
                                "config.placeholder" to key,
                                "config.resourcePath" to resource.path,
                                "spring.configurationClass" to classSymbol.qualifiedName,
                                "spring.annotation" to "org.springframework.beans.factory.annotation.Value",
                                "spring.annotationEvidence" to "ANNOTATION_LITERAL",
                            ),
                        )
                    }
            }
        }
        return relations
    }

    /** 收集一个 PsiMember 上的 @Value 注解：成员本身 + （方法/构造器）参数上的注解。 */
    private fun collectValueAnnotations(member: PsiMember): List<PsiAnnotation> {
        val own = member.annotations.filter { isValueAnnotation(it) }
        val parameterAnnotations = when (member) {
            is PsiMethod -> member.parameterList.parameters.flatMap { p: PsiParameter -> p.annotations.asList() }.filter(::isValueAnnotation)
            else -> emptyList()
        }
        return own + parameterAnnotations
    }

    /** 判断注解是否为 Spring @Value。 */
    private fun isValueAnnotation(annotation: PsiAnnotation): Boolean {
        val qfn = annotation.qualifiedName ?: return false
        val simple = qfn.substringAfterLast('.').ifBlank { return false }
        return qfn == "org.springframework.beans.factory.annotation.Value" || simple == "Value"
    }

    /**
     * 从 @Value 注解中提取 placeholder key：支持 `"${foo.bar}"`、`"${foo.bar:default}"`、`"#{...}"` 等形式。
     * SpEL `#{...}` 不属于配置文件绑定，会被忽略；仅处理 `${...}` 形式。
     */
    private fun placeholderKeysFromValueAnnotation(annotation: PsiAnnotation): List<String> {
        val raw = annotation.findDeclaredAttributeValue("value")?.text ?: return emptyList()
        val keys = mutableListOf<String>()
        // 匹配 ${foo.bar} 或 ${foo.bar:default}；忽略 #{...}（SpEL，不绑定配置文件）
        Regex("""\$\{([^}]+)}""").findAll(raw).forEach { match ->
            val expr = match.groupValues[1].trim()
            // 形如 foo.bar:default → 取冒号前的部分
            val key = expr.substringBefore(':').trim()
            if (key.isNotEmpty() && !key.startsWith('#')) {
                keys += key
            }
        }
        return keys
    }

    /**
     * 从注解中提取 Spring @ConfigurationProperties 的配置前缀：
     * 先按简单名/全限定名判断是否为目标注解，再依次尝试 prefix、value 属性，
     * 取首个非空白字符串作为前缀，否则返回 null。
     */
    private fun configurationPropertiesPrefix(annotation: PsiAnnotation): String? {
        val qualifiedName = annotation.qualifiedName.orEmpty()
        val simpleName = qualifiedName.substringAfterLast('.').ifBlank { annotation.nameReferenceElement?.referenceName.orEmpty() }
        if (simpleName != "ConfigurationProperties" &&
            qualifiedName != "org.springframework.boot.context.properties.ConfigurationProperties"
        ) {
            return null
        }
        return listOf("prefix", "value")
            .firstNotNullOfOrNull { name -> annotation.annotationStringValue(name) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /** 读取注解属性对应的字符串值：value 属性在缺失时回退到默认值位置。 */
    private fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    /** 把注解成员值解析为字符串：支持数组初始化取首元素，以及字面量字符串。 */
    private fun PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    /** 判断资源是否为 Spring Boot 标准 application 配置文件（yml/yaml/properties）。 */
    private fun isSpringApplicationConfigResource(resource: JvmResourceSymbol): Boolean =
        resource.kind in setOf(JvmResourceKind.YAML, JvmResourceKind.PROPERTIES) &&
            resource.path.substringAfterLast('/') in setOf(
                "application.yml",
                "application.yaml",
                "application.properties",
            )

    /**
     * 读取配置资源文本，判断是否声明了指定前缀。
     *
     * 旧实现用单行正则 `^\s*prefix\s*:`，只匹配顶层层级；嵌套形式
     * `spring:\n  orders:\n    endpoint:` 永远命中不到。
     * 新实现把 YAML 拍平成 dotted keys 后再比对前缀；properties 仍按 `prefix.` 形式匹配。
     */
    private fun resourceDeclaresPrefix(
        context: JvmResolutionContext,
        resource: JvmResourceSymbol,
        prefix: String,
    ): Boolean {
        val text = context.sourceResolver.readResourceByPath(resource.path)?.text ?: return false
        return when (resource.kind) {
            JvmResourceKind.YAML -> yamlDeclaresPrefix(text, prefix)
            JvmResourceKind.PROPERTIES -> propertiesDeclaresPrefix(text, prefix)
            else -> false
        }
    }

    /** properties 文件：每行形如 `prefix.key = value` 或 `prefix[key]=value` 或 `prefix = value`。 */
    private fun propertiesDeclaresPrefix(text: String, prefix: String): Boolean {
        val escaped = Regex.escape(prefix)
        // 三种匹配形式：
        // 1. `prefix = value`（完全匹配顶层键）
        // 2. `prefix.key = value`（嵌套键）
        // 3. `prefix[key] = value`（Spring 的索引写法）
        val regex = Regex("""(?m)^\s*${escaped}(\s*=|[.\[])""")
        return regex.containsMatchIn(text)
    }

    /**
     * YAML 文件：把缩进式嵌套结构拍平成 dotted keys，再判断是否有以 [prefix] 开头的 key。
     *
     * 简化处理：仅识别 `key: value` 行 + 缩进深度，忽略 list、anchor、 multiline 字符串等高级特性；
     * 对于 Spring `application.yml` 中常见的扁平/嵌套配置键已经足够。
     */
    private fun yamlDeclaresPrefix(text: String, prefix: String): Boolean {
        val flattened = flattenYamlKeys(text)
        return flattened.any { key -> key == prefix || key.startsWith("$prefix.") || key.startsWith("$prefix[") }
    }

    /**
     * 读取配置资源文本，判断是否声明了 placeholder key（@Value 注入点）。
     */
    private fun resourceDeclaresPlaceholderKey(
        context: JvmResolutionContext,
        resource: JvmResourceSymbol,
        key: String,
    ): Boolean {
        val text = context.sourceResolver.readResourceByPath(resource.path)?.text ?: return false
        return when (resource.kind) {
            JvmResourceKind.YAML -> yamlDeclaresPrefix(text, key)
            JvmResourceKind.PROPERTIES -> propertiesDeclaresPrefix(text, key)
            else -> false
        }
    }

    /**
     * 把 YAML 文本拍平为 dotted keys：维护 (缩进, key) 栈，遇到更深缩进追加为子键。
     */
    private fun flattenYamlKeys(text: String): List<String> {
        val keys = mutableListOf<String>()
        val stack = ArrayDeque<Pair<Int, String>>() // (indent, key)
        text.lineSequence().forEach { line ->
            // 跳过空行与注释
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            // 只处理 `key: value` 或 `key:` 形式的行，忽略 list 项 `- xxx`
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val key = trimmed.substring(0, colonIdx).trim()
            if (key.isEmpty() || key.startsWith("-")) return@forEach
            val indent = line.takeWhile(Char::isWhitespace).length
            // 弹出栈顶所有缩进 >= 当前的项，保留父级
            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeLast()
            }
            val fullKey = if (stack.isEmpty()) key else "${stack.last().second}.$key"
            keys += fullKey
            // 仅当 value 为空时才视为父级（即 `key:` 后续可能有缩进子项）
            val valuePart = trimmed.substring(colonIdx + 1).trim()
            if (valuePart.isEmpty()) {
                stack.addLast(indent to fullKey)
            }
        }
        return keys
    }
}
