package com.charmnight.linkgraph.jvm.index

/**
 * JVM 类型 / 方法描述符与 Scala 源码顶层声明的纯解析逻辑（P2-1 拆分）。
 *
 * 这些函数无状态、无 PSI 依赖，把字符串解析成结构化数据；
 * 与 JvmSymbolIndexBuilder 的索引构建主流程解耦后便于复用与单独测试。
 */

/** 方法描述符解析结果：参数类型列表 + 返回类型（已转 Java 类型名）。 */
internal data class MethodDescriptor(
    val parameterTypes: List<String>,
    val returnType: String,
)

/** 类型描述符解析结果：解析出的类型名与解析结束位置（用于连续解析下一个类型）。 */
internal data class ParsedDescriptorType(
    val typeName: String,
    val nextOffset: Int,
)

/**
 * 解析 JVM 方法描述符（如 `(Ljava/lang/String;I)V`）为 [MethodDescriptor]。
 *
 * 入参必须是合法的方法描述符（以 `(` 开头，参数列表后接 `)` 和返回类型描述符）；
 * 解析失败返回 null。
 */
internal fun methodDescriptor(descriptor: String): MethodDescriptor? {
    if (!descriptor.startsWith("(")) {
        return null
    }
    var offset = 1
    val parameters = mutableListOf<String>()
    while (offset < descriptor.length && descriptor[offset] != ')') {
        val parsed = parseDescriptorType(descriptor, offset) ?: return null
        parameters += parsed.typeName
        offset = parsed.nextOffset
    }
    if (offset >= descriptor.length || descriptor[offset] != ')') {
        return null
    }
    val returnType = parseDescriptorType(descriptor, offset + 1)?.typeName ?: return null
    return MethodDescriptor(parameters, returnType)
}

/** 解析单个 JVM 类型描述符（要求整段都被消费），返回对应的 Java 类型文本。 */
internal fun descriptorTypeName(descriptor: String): String? =
    parseDescriptorType(descriptor, 0)?.takeIf { parsed -> parsed.nextOffset == descriptor.length }?.typeName

/** 从描述符的指定偏移开始解析一个类型（含数组维度），返回类型名与解析结束后的下一个偏移。 */
internal fun parseDescriptorType(
    descriptor: String,
    startOffset: Int,
): ParsedDescriptorType? {
    if (startOffset >= descriptor.length) {
        return null
    }
    var offset = startOffset
    var arrayDepth = 0
    while (offset < descriptor.length && descriptor[offset] == '[') {
        arrayDepth++
        offset++
    }
    if (offset >= descriptor.length) {
        return null
    }
    val baseType = when (val marker = descriptor[offset]) {
        'B' -> "byte".also { offset++ }
        'C' -> "char".also { offset++ }
        'D' -> "double".also { offset++ }
        'F' -> "float".also { offset++ }
        'I' -> "int".also { offset++ }
        'J' -> "long".also { offset++ }
        'S' -> "short".also { offset++ }
        'Z' -> "boolean".also { offset++ }
        'V' -> "void".also { offset++ }
        'L' -> {
            val end = descriptor.indexOf(';', startIndex = offset)
            if (end < 0) {
                return null
            }
            descriptor.substring(offset + 1, end).replace('/', '.').also { offset = end + 1 }
        }
        else -> return null
    }
    return ParsedDescriptorType(
        typeName = baseType + "[]".repeat(arrayDepth),
        nextOffset = offset,
    )
}

/** Scala 源码顶层声明：kind（class/trait/object/enum）+ name + startLine。 */
internal data class ScalaTopLevelDeclaration(
    val kind: String,
    val name: String,
    val startLine: Int,
)

/** 用正则从 Scala 源码顶部抽取 package 声明的全限定名，兼容末尾花括号语法。 */
internal fun scalaPackageName(text: String): String? =
    Regex("""(?m)^\s*package\s+([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\s*(?:$|\{)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)

/** 用正则枚举 Scala 顶层 class/trait/object/enum，剔除块注释干扰后返回声明列表。 */
internal fun scalaTopLevelDeclarations(text: String): List<ScalaTopLevelDeclaration> {
    val withoutBlockComments = text.replace(Regex("""(?s)/\*.*?\*/"""), "")
    return Regex(
        """(?m)^\s*(?:@[^\n]+\s*)*(?:(?:final|sealed|abstract|case|private|protected|implicit|open)\s+)*(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_$]*)""",
    ).findAll(withoutBlockComments)
        .map { match ->
            ScalaTopLevelDeclaration(
                kind = match.groupValues[1],
                name = match.groupValues[2],
                startLine = withoutBlockComments.take(match.range.first).count { char -> char == '\n' } + 1,
            )
        }
        .toList()
}
