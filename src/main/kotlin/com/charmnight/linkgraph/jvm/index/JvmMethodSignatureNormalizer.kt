package com.charmnight.linkgraph.jvm.index

/**
 * 将源码/索引/调试输入中的方法签名规整为可比较形式。
 *
 * 返回类型不参与 JVM 重载，但调试定位会用完整签名做精确回映，因此这里仍保留返回类型；
 * 参数与返回类型统一走 [JvmTypeEraser]，确保数组、泛型与包名前缀规则只有一个来源。
 */
internal object JvmMethodSignatureNormalizer {
    fun comparableMethodSignature(signature: String): String {
        val trimmedSignature = signature.trim()
        val argumentsStart = trimmedSignature.indexOf('(')
        if (argumentsStart <= 0) {
            return trimmedSignature
        }
        val ownerAndMethod = trimmedSignature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = ownerAndMethod)
        val methodName = ownerAndMethod.substringAfterLast('.')
        val simpleOwner = owner.substringAfterLast('.')
        val argumentsEnd = trimmedSignature.indexOf(')', startIndex = argumentsStart)
        if (argumentsEnd < argumentsStart) {
            return "$simpleOwner.$methodName${trimmedSignature.substring(argumentsStart)}"
        }
        val parameters = splitParameterTypes(trimmedSignature.substring(argumentsStart + 1, argumentsEnd))
            .joinToString(",") { type -> JvmTypeEraser.eraseTypeWithArrayNormalization(type) }
        val returnType = trimmedSignature
            .substring(argumentsEnd + 1)
            .removePrefix(":")
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(JvmTypeEraser::eraseTypeWithArrayNormalization)
            ?: ""
        return "$simpleOwner.$methodName($parameters):$returnType"
    }

    private fun splitParameterTypes(parameters: String): List<String> {
        if (parameters.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var genericDepth = 0
        parameters.forEach { char ->
            when (char) {
                '<' -> {
                    genericDepth += 1
                    current.append(char)
                }
                '>' -> {
                    if (genericDepth > 0) genericDepth -= 1
                    current.append(char)
                }
                ',' -> {
                    if (genericDepth == 0) {
                        current.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
                        current.clear()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        current.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
        return result
    }
}
