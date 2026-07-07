package com.charmnight.linkgraph.jvm.index

/**
 * JVM 方法签名比较使用的类型文本归一化规则。
 *
 * 该对象只处理索引与 PSI/前端签名之间的稳定文本差异：
 * - 去掉包名前缀；
 * - 擦除泛型参数；
 * - 保留数组维度；
 * - 可选地把 Kotlin 数组字面量归一到 JVM/Java 数组形式。
 */
internal object JvmTypeEraser {
    fun eraseType(type: String): String {
        val trimmed = type.trim().removeSuffix("?")
        val arraySuffix = buildString {
            var rest = trimmed
            while (rest.endsWith("[]")) {
                append("[]")
                rest = rest.removeSuffix("[]")
            }
        }
        val withoutArrays = trimmed.removeSuffix(arraySuffix)
        val erased = withoutArrays.substringBefore('<').substringAfterLast('.').trim()
        return erased + arraySuffix
    }

    fun eraseTypeWithArrayNormalization(type: String): String =
        eraseType(normalizeArrayNotation(type))

    fun typesCompatible(candidateType: String, baseType: String): Boolean {
        val normalizedCandidate = eraseTypeWithArrayNormalization(candidateType)
        val normalizedBase = eraseTypeWithArrayNormalization(baseType)
        if (normalizedCandidate == normalizedBase) return true
        if (arrayDepth(normalizedCandidate) == arrayDepth(normalizedBase)) {
            if (isLikelyTypeParameterName(arrayElementType(normalizedCandidate))) return true
            if (isLikelyTypeParameterName(arrayElementType(normalizedBase))) return true
        }
        return false
    }

    fun isLikelyTypeParameterName(erasedType: String): Boolean =
        erasedType.length == 1 && erasedType[0].isUpperCase()

    private fun normalizeArrayNotation(type: String): String {
        val normalizedType = type.trim().removeSuffix("?")
        val arrayPrefix = "Array<"
        val prefixIdx = normalizedType.lastIndexOf(arrayPrefix)
        if (prefixIdx >= 0 && normalizedType.endsWith(">")) {
            val elementType = normalizedType.substring(prefixIdx + arrayPrefix.length, normalizedType.length - 1).trim()
            return "$elementType[]"
        }
        val simpleName = normalizedType.substringAfterLast('.')
        KOTLIN_PRIMITIVE_ARRAY_NAMES[simpleName]?.let { return it }
        return normalizedType
    }

    private fun arrayDepth(type: String): Int {
        var rest = type
        var depth = 0
        while (rest.endsWith("[]")) {
            depth += 1
            rest = rest.removeSuffix("[]")
        }
        return depth
    }

    private fun arrayElementType(type: String): String {
        var rest = type
        while (rest.endsWith("[]")) {
            rest = rest.removeSuffix("[]")
        }
        return rest
    }

    private val KOTLIN_PRIMITIVE_ARRAY_NAMES: Map<String, String> = mapOf(
        "IntArray" to "int[]",
        "ByteArray" to "byte[]",
        "ShortArray" to "short[]",
        "LongArray" to "long[]",
        "FloatArray" to "float[]",
        "DoubleArray" to "double[]",
        "CharArray" to "char[]",
        "BooleanArray" to "boolean[]",
    )
}
