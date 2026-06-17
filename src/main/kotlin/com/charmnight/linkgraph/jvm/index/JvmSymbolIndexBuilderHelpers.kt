package com.charmnight.linkgraph.jvm.index

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import org.jetbrains.kotlin.psi.KtFile

fun stableJvmId(kind: String, rawKey: String): String {
    val normalizedKind = kind.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "jvm" }
    val normalizedKey = rawKey.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
    return "jvm:$normalizedKind:$normalizedKey"
}

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

fun canonicalTypeText(type: PsiType?): String? {
    val psiType = type ?: return null
    val resolvedClass = runCatching { (psiType as? PsiClassType)?.resolve() }.getOrNull()
    return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
}

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

fun fieldTypeReferences(
    type: PsiType?,
    ownerPackageName: String?,
): List<JvmFieldTypeReference> {
    val references = linkedMapOf<String, JvmFieldTypeRole>()

    fun add(typeName: String?, role: JvmFieldTypeRole) {
        val normalized = normalizeReferenceTypeName(typeName, ownerPackageName) ?: return
        val current = references[normalized]
        if (current == null || role.precedence < current.precedence) {
            references[normalized] = role
        }
    }

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

internal enum class FieldTypeContainerCategory {
    COLLECTION,
    MAP,
    PROVIDER,
    FUNCTION,
    WRAPPER,
    OTHER,
}

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

internal fun JvmFieldTypeRole.elementRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.COLLECTION_ELEMENT
    }

internal fun JvmFieldTypeRole.mapKeyRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.MAP_KEY
    }

internal fun JvmFieldTypeRole.wrapperRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.WRAPPER_VALUE
    }

internal fun JvmFieldTypeRole.typeArgumentRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.TYPE_ARGUMENT
    }

internal fun rawClassTypeName(type: PsiClassType): String? {
    val resolved = runCatching { type.resolve() }.getOrNull()
    return resolved?.qualifiedName ?: normalizeReferenceTypeName(type.rawType().canonicalText, null)
}

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

internal fun isJvmFunctionType(typeName: String): Boolean =
    typeName == "kotlin.Function" ||
        Regex("""^(kotlin\.|kotlin\.jvm\.functions\.)Function\d+$""").matches(typeName)

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

internal fun outermostPackageFromQualifiedName(qualifiedName: String): String {
    val parts = qualifiedName.substringBefore('$').split('.').filter(String::isNotBlank)
    val classIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
    return when {
        classIndex > 0 -> parts.take(classIndex).joinToString(".")
        else -> qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
    }
}

internal fun String.hasExcludedContentRootSegment(): Boolean {
    val segments = split('/').filter(String::isNotBlank)
    return segments.withIndex().any { (index, segment) ->
        segment in alwaysExcludedContentRootSegments ||
            segment in generatedContentRootSegments && "src" !in segments.take(index)
    }
}

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

private val generatedContentRootSegments = setOf(
    "build",
    "coverage",
    "dist",
    "out",
    "target",
    "temp",
    "tmp",
)

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

private val mapTypeNames = setOf(
    "java.util.Map",
    "java.util.SortedMap",
    "java.util.NavigableMap",
    "java.util.concurrent.ConcurrentMap",
    "kotlin.collections.Map",
    "kotlin.collections.MutableMap",
    "scala.collection.Map",
)
private val mapSimpleTypeNames = setOf(
    "ConcurrentMap",
    "Map",
    "MutableMap",
    "NavigableMap",
    "SortedMap",
)

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
