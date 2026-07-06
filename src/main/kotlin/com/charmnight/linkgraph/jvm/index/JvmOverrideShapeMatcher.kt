package com.charmnight.linkgraph.jvm.index

/**
 * 判断两个 [JvmMethodSymbol] 是否处于 JVM 层重写关系。
 *
 * 背景：原始实现按 `simpleName + parameterTypes` 严格相等匹配，会漏匹配
 * - 协变返回（两端返回类型不同）
 * - 泛型接口特化（接口参数是类型参数 `T`，实现是 `String`，PSI 给出的 parameterTypes 不一致）
 * - Kotlin suspend（Continuation 描述符在不同索引时机可能有不同呈现）
 * - Kotlin/Java 跨语言数组（Kotlin `Array<String>` ↔ Java `String[]`，Kotlin `IntArray` ↔ Java `int[]`）
 *
 * 替换为 `simpleName + 参数数量` 又过匹配：实现类内同名同参数数量但类型不同的方法（重载）
 * 会被误判为重写，污染候选集。
 *
 * 本匹配器走中间路线：
 * 1. simpleName 必须相等
 * 2. 参数类型列表长度必须相等
 * 3. 每个位置的参数类型「相容」：
 *    - 数组形式归一化并擦除后字符串相等（剥包名 / 泛型，保留数组维度）→ 相容
 *    - 任一端是单个大写字母（Java/Kotlin 通用泛型参数约定 T/K/V/E/R）→ 视为可覆盖，相容
 *    - 否则不相容（Integer 与 String、String 与 BigDecimal 等）
 *
 * 这个规则覆盖了 4 类场景（协变返回、泛型特化、Kotlin suspend、跨语言数组、同名重载）。
 *
 * 仍然不完美的边角：
 * - 多字母类型参数名（如 `Type`、`Elem`）不会被识别为泛型参数。
 *   代价是漏匹配（回退到原未解析状态），不会过匹配。下游可继续靠其他证据兜底。
 * - 嵌套数组 `Array<Array<String>>` 只剥一层泛型；JVM 罕见，不在常规重写检测范围。
 * - 同名同擦除结果但语义无关（极少见）会被匹配；通常被 `implementationClassIds`
 *   的 IMPLEMENTS/EXTENDS 关系约束（只在真正的子类里找），仍属罕见。
 */
internal object JvmOverrideShapeMatcher {
    /**
     * 判断 [candidate] 是否可能是 [baseMethod] 的重写。
     *
     * @see JvmOverrideShapeMatcher 类注释关于匹配规则的完整说明
     */
    fun matchesOverride(
        candidate: JvmMethodSymbol,
        baseMethod: JvmMethodSymbol,
    ): Boolean {
        if (candidate.simpleName != baseMethod.simpleName) return false
        if (candidate.parameterTypes.size != baseMethod.parameterTypes.size) return false
        return candidate.parameterTypes.asSequence().zip(baseMethod.parameterTypes.asSequence())
            .all { (candidateType, baseType) -> typesCompatible(candidateType, baseType) }
    }

    /**
     * 判断两个参数类型字符串在 JVM 层是否视为同一类型。
     *
     * 步骤：
     * 1. 数组形式归一化（[normalizeArrayNotation]）：`Array<X>` / `IntArray` → `X[]` / `int[]`
     * 2. 擦除后字符串相等 → 相容
     * 3. 任一端是单个大写字母（类型参数）→ 视为泛型参数，相容
     * 4. 否则不相容
     */
    private fun typesCompatible(candidateType: String, baseType: String): Boolean {
        val normalizedCandidate = eraseType(normalizeArrayNotation(candidateType))
        val normalizedBase = eraseType(normalizeArrayNotation(baseType))
        if (normalizedCandidate == normalizedBase) return true
        if (sameArrayDepth(normalizedCandidate, normalizedBase)) {
            if (isLikelyTypeParameterName(arrayElementType(normalizedCandidate))) return true
            if (isLikelyTypeParameterName(arrayElementType(normalizedBase))) return true
        }
        return false
    }

    /**
     * 把 Kotlin 数组形式归一化到 Java 数组字面量，让两端进入统一比较：
     * - `Array<ElementType>` → `ElementType[]`（含 FQN 形式 `kotlin.Array<...>` 与 FQN 元素 `Array<java.lang.String>`）
     * - 基本类型数组：`IntArray` / `ByteArray` / `ShortArray` / `LongArray` / `FloatArray` / `DoubleArray` /
     *   基本数组映射：`CharArray` / `BooleanArray` → `int[]` / `byte[]` / ...
     *
     * 注：归一化只处理一层数组；嵌套形式 `Array<Array<String>>` 不展开，作为已知边角。
     */
    private fun normalizeArrayNotation(type: String): String {
        // Kotlin `Array<X>` → `X[]`：X 可能含 . 包前缀（如 `Array<java.lang.String>`），
        // 也可能在 Array< 前带 kotlin. 前缀；用 lastIndexOf 在剥 . 之前定位 Array<
        val arrayPrefix = "Array<"
        val prefixIdx = type.lastIndexOf(arrayPrefix)
        if (prefixIdx >= 0 && type.endsWith(">")) {
            val elementType = type.substring(prefixIdx + arrayPrefix.length, type.length - 1).trim()
            return "$elementType[]"
        }
        // Kotlin 原生数组类 → Java 原生数组
        val simpleName = type.substringAfterLast('.')
        KOTLIN_PRIMITIVE_ARRAY_NAMES[simpleName]?.let { return it }
        return type
    }

    /**
     * 把类型字符串归一化到可比较形式：
     * - 去掉尖括号泛型参数：`List<String>` → `List`、`Map<K,V>` → `Map`
     * - 保留数组方括号：`String[]` → `String[]`，避免数组与标量重载互相误配
     * - 去掉包前缀：`java.lang.String` → `String`
     *
     * 注：故意保留内部类 `$` 分隔符——`Outer$Inner` 与 `Outer.Inner` 在 JVM 层不是同一类型，
     * 也不属于本匹配器要兼顾的「源码 vs 字节码」差异。
     */
    private fun eraseType(type: String): String {
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

    private fun sameArrayDepth(left: String, right: String): Boolean =
        arrayDepth(left) == arrayDepth(right)

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

    /**
     * 判断擦除后的类型字符串是否像一个类型参数名。
     *
     * 约定：单个大写字母是 Java/Kotlin 通用泛型参数名（`T`/`K`/`V`/`E`/`R`）。
     * 多字符简单名不按类型参数处理——这会导致少量漏匹配（如 `Type`、`Elem`），
     * 但避免了「类名是单字母」的过匹配风险（罕见但合法）。
     */
    private fun isLikelyTypeParameterName(erasedType: String): Boolean =
        erasedType.length == 1 && erasedType[0].isUpperCase()

    /** Kotlin 原生数组 SimpleName → Java 原生数组字面量。 */
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
