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
            .all { (candidateType, baseType) -> JvmTypeEraser.typesCompatible(candidateType, baseType) }
    }
}
