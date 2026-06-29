package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 [JvmOverrideShapeMatcher] 的匹配规则覆盖：
 * - 同名同参同类型（基础 case）
 * - 协变返回（returnType 不参与匹配）
 * - 泛型接口特化（接口 T vs 实现 String）
 * - Kotlin suspend / Continuation 描述符（两端一致即匹配）
 * - FQN vs SimpleName（`java.lang.String` vs `String`）
 *
 * 并验证修复了 P1 commit 引入的「同名同 arity 异类型」过匹配。
 */
class JvmOverrideShapeMatcherTest {
    @Test
    fun matchesExactSameParameterTypes() {
        assertTrue(matcher(listOf("String"), listOf("String")))
    }

    @Test
    fun matchesFqnAgainstSimpleName() {
        // index 在不同时机可能给出 java.lang.String 或 String；erasure 后等价
        assertTrue(matcher(listOf("java.lang.String"), listOf("String")))
        assertTrue(matcher(listOf("String"), listOf("java.lang.String")))
    }

    @Test
    fun matchesGenericTypeParameterAgainstConcreteType() {
        // 接口参数是 T（约定 type parameter），实现是具体类型 String
        assertTrue(matcher(listOf("T"), listOf("java.lang.String")))
        assertTrue(matcher(listOf("T"), listOf("String")))
        assertTrue(matcher(listOf("String"), listOf("T")))
    }

    @Test
    fun matchesCovariantReturnDoesNotAffectParameterMatching() {
        // returnType 不参与匹配，所以协变返回不阻断
        val base = method("process", listOf("String"), returnType = "Object")
        val candidate = method("process", listOf("String"), returnType = "Concrete")
        assertTrue(JvmOverrideShapeMatcher.matchesOverride(candidate, base))
    }

    @Test
    fun matchesSameArityDifferentTypeKindsRejected() {
        // 修复 P1 commit 的过匹配：同名同 arity 但类型不同（如 String vs Integer）不应匹配
        assertFalse(matcher(listOf("String"), listOf("Integer")))
        assertFalse(matcher(listOf("java.lang.String"), listOf("java.lang.Integer")))
        assertFalse(matcher(listOf("String"), listOf("java.math.BigDecimal")))
    }

    @Test
    fun matchesGenericParameterListPositionWise() {
        // 多参数 type parameter 在每个位置都可被具体类型替换
        assertTrue(matcher(listOf("T", "K"), listOf("String", "Integer")))
        assertTrue(matcher(listOf("T", "java.lang.String"), listOf("Integer", "String")))
        // 单 position 类型不同（且都不是 type parameter）→ 不匹配
        assertFalse(matcher(listOf("T", "String"), listOf("Integer", "BigDecimal")))
    }

    @Test
    fun matchesDifferentArityRejected() {
        assertFalse(matcher(listOf("String"), listOf("String", "Integer")))
        assertFalse(matcher(emptyList(), listOf("String")))
    }

    @Test
    fun matchesDifferentNameRejected() {
        val base = method("process", listOf("String"))
        val candidate = method("handle", listOf("String"))
        assertFalse(JvmOverrideShapeMatcher.matchesOverride(candidate, base))
    }

    @Test
    fun matchesErasesGenericsInType() {
        // List<String> 与 List<Integer> 在 JVM 层都是 List（erasure 一致）→ 视为同型
        // 注：这是 JVM 泛型擦除的语义，不是 source 端类型差异
        assertTrue(matcher(listOf("List<String>"), listOf("List<Integer>")))
        assertTrue(matcher(listOf("java.util.List<String>"), listOf("List")))
    }

    @Test
    fun matchesErasesArrayBrackets() {
        assertTrue(matcher(listOf("String[]"), listOf("String")))
        assertTrue(matcher(listOf("java.lang.String[]"), listOf("String")))
    }

    @Test
    fun matchesKotlinSuspendContinuationCompatible() {
        // suspend 在两端都展开为 Continuation 描述符；matcher 直接按字面比较 + erasure
        // 这里给出常见形式：两端一致即匹配
        val base = method("run", listOf("kotlin.coroutines.Continuation"))
        val candidate = method("run", listOf("kotlin.coroutines.Continuation"))
        assertTrue(JvmOverrideShapeMatcher.matchesOverride(candidate, base))
    }

    @Test
    fun matchesKotlinArrayReferenceVsJavaReferenceArray() {
        // Kotlin `Array<String>` 与 Java `String[]` 在 JVM 层是同一类型（`[Ljava/lang/String;`）。
        // PSI 在两端给出的 parameterTypes 字面量不同（Kotlin: `Array<String>`，Java: `String[]`），
        // matcher 必须归一化后视为相容，否则 Kotlin override Java 方法（参数 String[] ↔ Array<String>）会漏匹配。
        assertTrue(matcher(listOf("Array<String>"), listOf("String[]")))
        assertTrue(matcher(listOf("String[]"), listOf("Array<String>")))
        // 全限定元素类型也要归一化
        assertTrue(matcher(listOf("Array<java.lang.String>"), listOf("java.lang.String[]")))
    }

    @Test
    fun matchesKotlinPrimitiveArrayVsJavaPrimitiveArray() {
        // Kotlin `IntArray` / `ByteArray` 等映射到 Java `int[]` / `byte[]` 原生数组。
        // 同一 JVM 类型在不同语言 PSI 下字面量不同，必须归一化。
        assertTrue(matcher(listOf("IntArray"), listOf("int[]")))
        assertTrue(matcher(listOf("ByteArray"), listOf("byte[]")))
        assertTrue(matcher(listOf("LongArray"), listOf("long[]")))
        // 不对称：IntArray 映射 int[]（原生），Array<Int> 映射 Integer[]（装箱）——语义不同，不应匹配
        assertFalse(matcher(listOf("IntArray"), listOf("Integer[]")))
        assertFalse(matcher(listOf("IntArray"), listOf("Array<Int>")))
    }

    @Test
    fun matchesTypeParameterArrayAgainstConcreteArray() {
        // 泛型数组 `<T>` 与具体类型数组组合时仍按位置匹配
        assertTrue(matcher(listOf("Array<T>"), listOf("String[]")))
    }

    private fun matcher(
        candidateParamTypes: List<String>,
        baseParamTypes: List<String>,
    ): Boolean {
        val base = method("process", baseParamTypes)
        val candidate = method("process", candidateParamTypes)
        return JvmOverrideShapeMatcher.matchesOverride(candidate, base)
    }

    private fun method(
        name: String,
        parameterTypes: List<String>,
        returnType: String? = "void",
    ): JvmMethodSymbol = JvmMethodSymbol(
        id = "$name#${parameterTypes.joinToString(",")}",
        qualifiedName = "Test.$name",
        simpleName = name,
        ownerClassName = "Test",
        signature = "$name(${parameterTypes.joinToString(",")})",
        parameterTypes = parameterTypes,
        returnType = returnType,
        abstract = false,
        source = null,
        origin = SourceOrigin.PROJECT_SOURCE,
    )
}
