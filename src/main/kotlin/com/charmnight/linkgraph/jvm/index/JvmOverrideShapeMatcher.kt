package com.charmnight.linkgraph.jvm.index

/**
 * 判断两个 [JvmMethodSymbol] 是否处于 JVM-level override 关系。
 *
 * 背景：原始实现按 `simpleName + parameterTypes` 严格相等匹配，会漏匹配
 * - 协变返回（两端 returnType 不同）
 * - 泛型接口特化（接口参数是 type parameter `T`，实现是 `String`，PSI 给出的 parameterTypes 不一致）
 * - Kotlin suspend（Continuation 描述符在不同索引时机可能有不同呈现）
 *
 * 替换为 `simpleName + arity` 又过匹配：实现类内同名同 arity 但类型不同的方法（重载）
 * 会被误判为 override，污染候选集。
 *
 * 本匹配器走中间路线：
 * 1. simpleName 必须相等
 * 2. parameterTypes 长度必须相等
 * 3. 每个位置的参数类型「相容」：
 *    - erase 后字符串相等（剥 package / 泛型 / 数组）→ 相容
 *    - 任一端是单个大写字母（Java/Kotlin 通用泛型参数约定 T/K/V/E/R）→ 视为可覆盖，相容
 *    - 否则不相容（Integer 与 String、String 与 BigDecimal 等）
 *
 * 这个规则覆盖了 P1 commit 列出的 4 类场景（协变返回、泛型特化、Kotlin suspend、同名重载），
 * 又堵住了「同 arity 异类型」的过匹配。
 *
 * 仍然不完美的边角：
 * - 多字母 type parameter 名（如 `Type`、`Elem`）不会被识别为泛型参数。
 *   代价是漏匹配（fallback 到原 unresolved），不会过匹配。下游可继续靠其他证据兜底。
 * - 同名同 erasure 但语义无关（极少见）会被匹配；通常被 `implementationClassIds`
 *   的 IMPLEMENTS/EXTENDS 关系约束（只在真正的子类里找），仍属罕见。
 */
internal object JvmOverrideShapeMatcher {
    /**
     * 判断 [candidate] 是否可能是 [baseMethod] 的 override。
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
     * 1. erase 后字符串相等 → 相容
     * 2. 任一端是单个大写字母（type parameter）→ 视为泛型参数，相容
     * 3. 否则不相容
     */
    private fun typesCompatible(candidateType: String, baseType: String): Boolean {
        val erasedCandidate = eraseType(candidateType)
        val erasedBase = eraseType(baseType)
        if (erasedCandidate == erasedBase) return true
        if (isLikelyTypeParameterName(erasedCandidate)) return true
        if (isLikelyTypeParameterName(erasedBase)) return true
        return false
    }

    /**
     * 把类型字符串归一化到可比较形式：
     * - 去掉尖括号泛型参数：`List<String>` → `List`、`Map<K,V>` → `Map`
     * - 去掉数组方括号：`String[]` → `String`
     * - 去掉包前缀：`java.lang.String` → `String`
     *
     * 注：故意保留内部类 `$` 分隔符——`Outer$Inner` 与 `Outer.Inner` 在 JVM 层不是同一类型，
     * 也不属于本匹配器要兼顾的「源码 vs 字节码」差异。
     */
    private fun eraseType(type: String): String =
        type.substringBefore('<')
            .replace("[]", "")
            .substringAfterLast('.')
            .trim()

    /**
     * 判断 erase 后的类型字符串是否像一个 type parameter 名。
     *
     * 约定：单个大写字母是 Java/Kotlin 通用泛型参数名（`T`/`K`/`V`/`E`/`R`）。
     * 多字符 SimpleName 不按 type parameter 处理——这会导致少量漏匹配（如 `Type`、`Elem`），
     * 但避免了「类名是单字母」的过匹配风险（罕见但合法）。
     */
    private fun isLikelyTypeParameterName(erasedType: String): Boolean =
        erasedType.length == 1 && erasedType[0].isUpperCase()
}
