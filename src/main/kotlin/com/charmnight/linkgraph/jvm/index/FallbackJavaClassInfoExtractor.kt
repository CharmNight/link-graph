package com.charmnight.linkgraph.jvm.index

/** Java 类回退信息：限定名、父类名列表与实现的接口名列表。 */
internal data class FallbackJavaClassInfo(
    val qualifiedName: String,
    val extendsNames: List<String>,
    val implementsNames: List<String>,
)

/**
 * Java 类信息回退提取器：当 PSI 不可用时（例如纯文本解析场景），
 * 通过正则解析 Java 源码，提取类声明、父类与接口信息。
 */
internal object FallbackJavaClassInfoExtractor {
    /** 解析 Java 源码并返回以限定名为键的类信息映射。 */
    fun extract(
        relativePath: String,
        sourceText: String,
    ): Map<String, FallbackJavaClassInfo> {
        val code = sourceText
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(code)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val explicitImports = linkedMapOf<String, String>()
        val wildcardImports = mutableListOf<String>()
        Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;""")
            .findAll(code)
            .forEach { match ->
                val importName = match.groupValues[1]
                if (importName.endsWith(".*")) {
                    wildcardImports += importName.removeSuffix(".*")
                } else {
                    explicitImports[importName.substringAfterLast('.')] = importName
                }
            }
        val effectivePackageName = packageName.ifBlank { inferPackageName(relativePath) }
        return Regex(
            """\b(class|interface|enum)\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]*>)?(?:\s+extends\s+([^{}]+?))?(?:\s+implements\s+([^{}]+?))?\s*\{""",
        ).findAll(code)
            .mapNotNull { match ->
                val simpleName = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val qualifiedName = listOf(effectivePackageName, simpleName)
                    .filter(String::isNotBlank)
                    .joinToString(".")
                qualifiedName to FallbackJavaClassInfo(
                    qualifiedName = qualifiedName,
                    extendsNames = javaTypeNames(
                        match.groupValues.getOrNull(3),
                        effectivePackageName,
                        explicitImports,
                        wildcardImports,
                    ),
                    implementsNames = javaTypeNames(
                        match.groupValues.getOrNull(4),
                        effectivePackageName,
                        explicitImports,
                        wildcardImports,
                    ),
                )
            }
            .toMap()
    }

    /** 根据文件相对路径推断包名（src/main/java 之后的目录结构）。 */
    private fun inferPackageName(relativePath: String): String =
        relativePath
            .replace('\\', '/')
            .substringBeforeLast('/', missingDelimiterValue = "")
            .substringAfter("/src/main/java/", missingDelimiterValue = "")
            .substringAfter("/src/test/java/", missingDelimiterValue = "")
            .replace('/', '.')
            .trim('.')

    /** 把类声明中的类型名解析为完整限定名（基于导入与包名推断）。 */
    private fun javaTypeNames(
        rawTypeList: String?,
        packageName: String,
        explicitImports: Map<String, String>,
        wildcardImports: List<String>,
    ): List<String> =
        splitTopLevelCommaSeparatedTypes(rawTypeList)
            .mapNotNull { rawType ->
                val typeName = rawType
                    .substringBefore(" permits ")
                    .trim()
                    .stripJavaTypeArguments()
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                when {
                    typeName.contains('.') -> typeName
                    explicitImports[typeName] != null -> explicitImports[typeName]
                    typeName in implicitJavaLangTypeNames -> "java.lang.$typeName"
                    packageName.isNotBlank() -> "$packageName.$typeName"
                    wildcardImports.size == 1 -> "${wildcardImports.single()}.$typeName"
                    else -> typeName
                }
            }
            .distinct()

    /** 在泛型层级之外按顶层逗号拆分类型列表字符串。 */
    private fun splitTopLevelCommaSeparatedTypes(rawTypeList: String?): List<String> {
        val raw = rawTypeList?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        raw.forEachIndexed { index, char ->
            when (char) {
                '<' -> depth += 1
                '>' -> depth = (depth - 1).coerceAtLeast(0)
                ',' -> if (depth == 0) {
                    result += raw.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += raw.substring(start)
        return result.map(String::trim).filter(String::isNotBlank)
    }

    /** 移除 Java 类型字符串中的泛型参数部分。 */
    private fun String.stripJavaTypeArguments(): String {
        val builder = StringBuilder()
        var depth = 0
        forEach { char ->
            when (char) {
                '<' -> depth += 1
                '>' -> depth = (depth - 1).coerceAtLeast(0)
                else -> if (depth == 0) {
                    builder.append(char)
                }
            }
        }
        return builder.toString().trim()
    }

    /** 隐式 java.lang 包下的常见类型集合，无需显式导入。 */
    private val implicitJavaLangTypeNames = setOf(
        "String",
        "Object",
        "Integer",
        "Long",
        "Boolean",
        "Double",
        "Float",
        "Short",
        "Byte",
        "Character",
        "Void",
        "RuntimeException",
        "Exception",
        "Throwable",
        "Error",
        "Class",
        "Enum",
        "Record",
    )
}
