package com.charmnight.linkgraph.jvm.index

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import org.jetbrains.kotlin.psi.KtFile

/** 根据种类与原始键生成稳定的 JVM 符号 ID。 */
fun stableJvmId(kind: String, rawKey: String): String {
    val normalizedKind = kind.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "jvm" }
    val normalizedKey = rawKey.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
    return "jvm:$normalizedKind:$normalizedKey"
}

/** 生成 PSI 方法的规范签名（含所有者、参数与返回类型）。 */
fun methodSignature(method: PsiMethod): String {
    val ownerName = method.containingClass?.qualifiedName
        ?: method.containingClass?.name
        ?: method.name
    val ownerPackageName = (method.containingFile as? PsiJavaFile)?.packageName
        ?: (method.navigationElement?.containingFile as? KtFile)?.packageFqName?.asString()
        ?: (method.containingFile as? KtFile)?.packageFqName?.asString()
        ?: method.containingClass?.qualifiedName?.let(::outermostPackageFromQualifiedName)
        ?: ""
    val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
        canonicalTypeTextNear(parameter.type, ownerPackageName) ?: parameter.type.canonicalText
    }
    val returnType = canonicalTypeTextNear(method.returnType, ownerPackageName)
        ?: if (method.isConstructor) ownerName else "void"
    return "$ownerName.${method.name}($parameters):$returnType"
}

/** 把 PSI 类型解析为规范类型文本（优先使用类限定名）。 */
fun canonicalTypeText(type: PsiType?): String? {
    val psiType = type ?: return null
    val resolvedClass = runCatching { (psiType as? PsiClassType)?.resolve() }.getOrNull()
    return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
}

/**
 * 在已知所有者包名的前提下解析类型文本。
 *
 * 优先使用类型的限定名；若类型没有包名且与所有者位于同一源文件包内，
 * 会尝试补上所有者包名前缀（通过 JavaPsiFacade 验证是否真实存在），避免出现短名歧义。
 */
internal fun canonicalTypeTextNear(type: PsiType?, ownerPackageName: String): String? {
    val normalized = canonicalTypeText(type) ?: return null
    if (normalized.contains('.') || ownerPackageName.isBlank()) {
        return normalized
    }
    val psiType = type as? PsiClassType ?: return normalized
    val resolved = runCatching { psiType.resolve() }.getOrNull()
    if (resolved?.qualifiedName != null) {
        return resolved.qualifiedName
    }
    val candidate = "$ownerPackageName.$normalized"
    val project = psiType.resolveScope.project ?: return normalized
    return if (JavaPsiFacade.getInstance(project).findClass(candidate, psiType.resolveScope) != null) {
        candidate
    } else {
        normalized
    }
}

/** 提取字段的类型引用列表（区分角色：类型使用、集合元素等）。 */
fun fieldTypeReferences(
    type: PsiType?,
    ownerPackageName: String?,
): List<JvmFieldTypeReference> {
    val references = linkedMapOf<String, JvmFieldTypeRole>()

    /** 把一个类型名以指定角色加入引用集合；若同名已有更高优先级角色，则保留旧角色。 */
    fun add(typeName: String?, role: JvmFieldTypeRole) {
        val normalized = normalizeReferenceTypeName(typeName, ownerPackageName) ?: return
        val current = references[normalized]
        if (current == null || role.precedence < current.precedence) {
            references[normalized] = role
        }
    }

    /** 递归遍历类型结构，根据容器类型（集合、Map、Provider 等）把内部参数分配到合适的角色上。 */
    fun visit(currentType: PsiType?, role: JvmFieldTypeRole) {
        when (currentType) {
            null -> return
            is PsiArrayType -> visit(currentType.componentType, role.elementRole())
            is PsiWildcardType -> visit(currentType.bound, role)
            is PsiClassType -> {
                val rawName = rawClassTypeName(currentType)
                val parameters = currentType.parameters.toList()
                val category = fieldContainerCategory(rawName)
                when {
                    category == FieldTypeContainerCategory.FUNCTION -> {
                        parameters.dropLast(1).forEach { parameter ->
                            visit(parameter, JvmFieldTypeRole.FUNCTION_PARAMETER)
                        }
                        visit(parameters.lastOrNull(), JvmFieldTypeRole.FUNCTION_RETURN)
                    }
                    category == FieldTypeContainerCategory.PROVIDER -> {
                        if (parameters.isEmpty()) {
                            add(canonicalTypeText(currentType), role)
                        } else {
                            parameters.forEach { parameter ->
                                visit(parameter, JvmFieldTypeRole.PROVIDER_RETURN)
                            }
                        }
                    }
                    category == FieldTypeContainerCategory.MAP -> {
                        parameters.getOrNull(0)?.let { keyType -> visit(keyType, role.mapKeyRole()) }
                        parameters.getOrNull(1)?.let { valueType -> visit(valueType, role.elementRole()) }
                        parameters.drop(2).forEach { parameter -> visit(parameter, role.typeArgumentRole()) }
                    }
                    category == FieldTypeContainerCategory.COLLECTION -> {
                        parameters.forEach { parameter -> visit(parameter, role.elementRole()) }
                    }
                    category == FieldTypeContainerCategory.WRAPPER -> {
                        parameters.forEach { parameter -> visit(parameter, role.wrapperRole()) }
                    }
                    else -> {
                        add(canonicalTypeText(currentType), role)
                        parameters.forEach { parameter -> visit(parameter, role.typeArgumentRole()) }
                    }
                }
            }
            else -> add(canonicalTypeText(currentType) ?: currentType.canonicalText, role)
        }
    }

    visit(type, JvmFieldTypeRole.DIRECT_VALUE)
    return references.map { (typeName, role) -> JvmFieldTypeReference(typeName, role) }
}

/**
 * 字段类型的容器种类，决定字段内部类型参数的递归角色分配策略。
 */
internal enum class FieldTypeContainerCategory {
    /** 集合类容器，元素以 COLLECTION_ELEMENT 角色收集。 */
    COLLECTION,
    /** 键值类容器，分别处理键和值。 */
    MAP,
    /** 延迟/异步生产者容器（Provider、Supplier 等）。 */
    PROVIDER,
    /** 函数类型容器（Kotlin FunctionN、Java SAM 等）。 */
    FUNCTION,
    /** 包装类容器（Optional、Reference 等）。 */
    WRAPPER,
    /** 其他普通类型，按直接值处理。 */
    OTHER,
}

/** 角色优先级：值越小代表角色越具体，发生同名冲突时优先保留更具体的角色。 */
internal val JvmFieldTypeRole.precedence: Int
    get() = when (this) {
        JvmFieldTypeRole.DIRECT_VALUE -> 0
        JvmFieldTypeRole.COLLECTION_ELEMENT -> 1
        JvmFieldTypeRole.MAP_VALUE -> 2
        JvmFieldTypeRole.MAP_KEY -> 3
        JvmFieldTypeRole.WRAPPER_VALUE -> 4
        JvmFieldTypeRole.TYPE_ARGUMENT -> 5
        JvmFieldTypeRole.PROVIDER_RETURN -> 6
        JvmFieldTypeRole.FUNCTION_RETURN -> 7
        JvmFieldTypeRole.FUNCTION_PARAMETER -> 8
    }

/** 在容器元素语义下递归时的角色推导：高优先级角色保持不变，普通角色降级为 COLLECTION_ELEMENT。 */
internal fun JvmFieldTypeRole.elementRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.COLLECTION_ELEMENT
    }

/** 进入 Map 键位置时的角色推导：保持高优先级角色，其余降级为 MAP_KEY。 */
internal fun JvmFieldTypeRole.mapKeyRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.MAP_KEY
    }

/** 进入包装类内部时的角色推导：保持高优先级角色，其余降级为 WRAPPER_VALUE。 */
internal fun JvmFieldTypeRole.wrapperRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.WRAPPER_VALUE
    }

/** 进入普通泛型参数时的角色推导：保持高优先级角色，其余降级为 TYPE_ARGUMENT。 */
internal fun JvmFieldTypeRole.typeArgumentRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.TYPE_ARGUMENT
    }

/** 提取 [PsiClassType] 对应的原始类全限定名，优先使用解析后的 qualifiedName。 */
internal fun rawClassTypeName(type: PsiClassType): String? {
    val resolved = runCatching { type.resolve() }.getOrNull()
    return resolved?.qualifiedName ?: normalizeReferenceTypeName(type.rawType().canonicalText, null)
}

/**
 * 根据类型全限定名（或简单名）判断其属于哪种容器类别。
 *
 * 通过查阅预置的集合、Map、Provider、Wrapper 类型表来识别，
 * 不在任何表中的视为 [FieldTypeContainerCategory.OTHER]。
 */
internal fun fieldContainerCategory(rawName: String?): FieldTypeContainerCategory {
    val normalized = rawName?.removeSuffix("?") ?: return FieldTypeContainerCategory.OTHER
    if (isJvmFunctionType(normalized)) {
        return FieldTypeContainerCategory.FUNCTION
    }
    val simpleName = normalized.substringAfterLast('.')
    if (normalized in providerTypeNames || simpleName in providerSimpleTypeNames) {
        return FieldTypeContainerCategory.PROVIDER
    }
    if (normalized in mapTypeNames || simpleName in mapSimpleTypeNames) {
        return FieldTypeContainerCategory.MAP
    }
    if (normalized in collectionTypeNames || simpleName in collectionSimpleTypeNames) {
        return FieldTypeContainerCategory.COLLECTION
    }
    if (normalized in wrapperTypeNames || simpleName in wrapperSimpleTypeNames) {
        return FieldTypeContainerCategory.WRAPPER
    }
    return FieldTypeContainerCategory.OTHER
}

/** Kotlin 函数类型匹配正则：Function0..FunctionN 或 kotlin.Function。
 * 提到顶层 val 避免每次调用都重新编译正则。 */
private val jvmFunctionTypeRegex = Regex("""^(kotlin\.|kotlin\.jvm\.functions\.)Function\d+$""")

/** 判断类型名是否属于 Kotlin 函数类型（Function0..FunctionN 或 kotlin.Function）。 */
internal fun isJvmFunctionType(typeName: String): Boolean =
    typeName == "kotlin.Function" || jvmFunctionTypeRegex.matches(typeName)

/**
 * 把类型名规范化为可用于引用索引的形式。
 *
 * 处理流程：去空白、去可空后缀、去数组后缀、补全 java.lang 隐式前缀；
 * 同时过滤掉原始类型，并在缺少包名时尝试用所有者包名补全。
 */
internal fun normalizeReferenceTypeName(
    typeName: String?,
    ownerPackageName: String?,
): String? {
    var normalized = typeName
        ?.trim()
        ?.removeSuffix("?")
        ?.takeIf(String::isNotBlank)
        ?: return null
    while (normalized.endsWith("[]")) {
        normalized = normalized.removeSuffix("[]")
    }
    normalized = normalizeImplicitJavaLangType(normalized.substringBefore('<'))
    if (normalized.isBlank() || normalized in primitiveTypeNames) {
        return null
    }
    if (normalized.contains('.') || ownerPackageName.isNullOrBlank()) {
        return normalized
    }
    return "$ownerPackageName.$normalized"
}

/** 把 Java 默认 import 的 java.lang 包下常用包装类型补全为全限定名，避免短名歧义。 */
internal fun normalizeImplicitJavaLangType(typeText: String): String {
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

/** 从全限定名中提取最外层包名（去掉嵌套类与类名），用于回退场景下推断 PSI 文件所在包。 */
internal fun outermostPackageFromQualifiedName(qualifiedName: String): String {
    val parts = qualifiedName.substringBefore('$').split('.').filter(String::isNotBlank)
    val classIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
    return when {
        classIndex > 0 -> parts.take(classIndex).joinToString(".")
        else -> qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
    }
}

/**
 * 判断路径中是否包含应该被排除的内容根目录片段。
 *
 * 总是排除版本控制、缓存目录；对于生成的输出目录（build/out 等），
 * 仅在它们没有出现在 `src` 之前时才排除，避免误伤用户源码。
 */
internal fun String.hasExcludedContentRootSegment(): Boolean {
    val segments = split('/').filter(String::isNotBlank)
    return segments.withIndex().any { (index, segment) ->
        segment in alwaysExcludedContentRootSegments ||
            segment in generatedContentRootSegments && "src" !in segments.take(index)
    }
}

/** 始终排除的内容根目录片段，主要覆盖各类工具缓存与依赖目录。 */
private val alwaysExcludedContentRootSegments = setOf(
    ".cache",
    ".git",
    ".gradle",
    ".idea",
    ".next",
    ".nuxt",
    ".parcel-cache",
    "build-idea-sandbox",
    "node_modules",
)

/** 由构建工具生成的输出目录片段，需结合上下文判断是否真的可排除。 */
private val generatedContentRootSegments = setOf(
    "build",
    "coverage",
    "dist",
    "out",
    "target",
    "temp",
    "tmp",
)

/** Java/Kotlin 原始类型名集合，不参与字段引用索引。 */
private val primitiveTypeNames = setOf(
    "boolean",
    "byte",
    "char",
    "double",
    "float",
    "int",
    "long",
    "short",
    "void",
)

/** 集合类全限定名集合，用于识别字段中作为容器使用的集合类型。 */
private val collectionTypeNames = setOf(
    "java.lang.Iterable",
    "java.util.Collection",
    "java.util.List",
    "java.util.Set",
    "java.util.Queue",
    "java.util.Deque",
    "java.util.SortedSet",
    "java.util.NavigableSet",
    "kotlin.collections.Collection",
    "kotlin.collections.Iterable",
    "kotlin.collections.List",
    "kotlin.collections.MutableCollection",
    "kotlin.collections.MutableIterable",
    "kotlin.collections.MutableList",
    "kotlin.collections.MutableSet",
    "kotlin.collections.Set",
    "scala.collection.Iterable",
    "scala.collection.Seq",
    "scala.collection.Set",
)
/** 集合类简单名集合，配合全限定名一起识别容器种类。 */
private val collectionSimpleTypeNames = setOf(
    "Collection",
    "Deque",
    "Iterable",
    "List",
    "MutableCollection",
    "MutableIterable",
    "MutableList",
    "MutableSet",
    "NavigableSet",
    "Queue",
    "Seq",
    "Set",
    "SortedSet",
)

/** Map 类全限定名集合，用于识别字段中作为键值容器的类型。 */
private val mapTypeNames = setOf(
    "java.util.Map",
    "java.util.SortedMap",
    "java.util.NavigableMap",
    "java.util.concurrent.ConcurrentMap",
    "kotlin.collections.Map",
    "kotlin.collections.MutableMap",
    "scala.collection.Map",
)
/** Map 类简单名集合，配合全限定名一起识别 Map 容器。 */
private val mapSimpleTypeNames = setOf(
    "ConcurrentMap",
    "Map",
    "MutableMap",
    "NavigableMap",
    "SortedMap",
)

/** Provider/生产者类全限定名集合，用于识别延迟/异步生产语义。 */
private val providerTypeNames = setOf(
    "com.google.inject.Provider",
    "dagger.Lazy",
    "javax.inject.Provider",
    "jakarta.inject.Provider",
    "java.util.concurrent.Callable",
    "java.util.function.Supplier",
    "kotlin.Lazy",
    "org.springframework.beans.factory.ObjectFactory",
    "org.springframework.beans.factory.ObjectProvider",
    "reactor.core.publisher.Flux",
    "reactor.core.publisher.Mono",
)
/** Provider 类简单名集合，配合全限定名一起识别生产者语义。 */
private val providerSimpleTypeNames = setOf(
    "Callable",
    "Flux",
    "Lazy",
    "Mono",
    "ObjectFactory",
    "ObjectProvider",
    "Provider",
    "Supplier",
)

/** 包装类全限定名集合（Optional/Reference/Result 等），其内部类型会被抽取为 WRAPPER_VALUE。 */
private val wrapperTypeNames = setOf(
    "java.lang.ref.Reference",
    "java.lang.ref.SoftReference",
    "java.lang.ref.WeakReference",
    "java.util.Optional",
    "java.util.OptionalDouble",
    "java.util.OptionalInt",
    "java.util.OptionalLong",
    "kotlin.Result",
)
/** 包装类简单名集合，配合全限定名一起识别包装语义。 */
private val wrapperSimpleTypeNames = setOf(
    "Optional",
    "OptionalDouble",
    "OptionalInt",
    "OptionalLong",
    "Reference",
    "Result",
    "SoftReference",
    "WeakReference",
)
