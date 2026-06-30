package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import kotlin.reflect.KClass

/**
 * 强类型 Agent 工具基类。
 *
 * 历史上 [AgentTool.invoke] 入参是 `ToolInputPayload`，每个 tool 自己用 `requiredString("xxx")` /
 * `optionalInt("xxx")` 读字段——缺编译期校验、字段命名依赖魔法字符串，重构时容易漏改。
 *
 * 本基类把入参解析与业务执行解耦：
 * - 子类声明具体 [I] (input data class)，实现 [parseInput] 从 Map 抽字段
 * - 子类实现 [invokeTyped] 用强类型 [I] 执行业务
 * - 基类把 parse 异常包装成 [ToolResult] failure 返回，业务层只关心正常路径
 *
 * 与既有 [AgentTool] 关系：
 * - TypedAgentTool 仍是 AgentTool（最终 invoke 签名不变）
 * - registry、调用方零改动
 * - 老的 `ToolInputPayload` 直读 tool 可继续直接实现 AgentTool；TypedAgentTool 是首选实现方式
 *
 * 设计取舍：没有用反射 / Gson 反序列化构造 [I]，而是要求子类显式 [parseInput] ——
 * 反射方案虽然样板更短，但失去对「缺失字段 vs 默认值」的精确控制，也无法在 parse 阶段做范围校验。
 * 显式 parse 函数让校验逻辑就地表达。
 */
abstract class TypedAgentTool<I : Any> : AgentTool {
    /**
     * 解析 + 执行的模板方法。
     *
     * parse 失败时把异常消息转化为 failure ToolResult（保留 [InputParseException.payload]）；
     * parse 成功后委托给 [invokeTyped]。
     */
    final override fun invoke(
        input: ToolInputPayload,
        context: ToolExecutionContext,
    ): ToolResult {
        val parsed = try {
            parseInput(input)
        } catch (e: InputParseException) {
            return failure(e.message ?: "input parse failed", payload = e.payload)
        }
        return invokeTyped(parsed, context)
    }

    /** 从 Map 解析出强类型 input；缺失必填字段或类型不匹配时抛 [InputParseException]。 */
    protected abstract fun parseInput(raw: ToolInputPayload): I

    /** 用解析后的强类型 input 执行工具。 */
    protected abstract fun invokeTyped(input: I, context: ToolExecutionContext): ToolResult

    // ---------- 共享的入参解析辅助函数 ----------

    /** 抛 [InputParseException]：必填字段缺失或为空白。 */
    protected fun missing(key: String): Nothing = throw InputParseException("$key 不能为空")

    /** 抛 [InputParseException]：字段存在但类型不符合预期，消息给出期望与实际类型，便于模型纠正。 */
    protected fun wrongType(key: String, expected: String, actual: String): Nothing =
        throw InputParseException("$key 类型不匹配：期望 $expected，实际 $actual")

    /** 抛 [InputParseException]：枚举值不在允许集合内，消息列出可选值，便于模型纠正。 */
    protected fun wrongEnum(key: String, actual: String, allowed: List<String>): Nothing =
        throw InputParseException("$key 值 '$actual' 不在允许集合内：${allowed.sorted().joinToString(", ")}")

    /** 读必填字符串；缺失或空白时抛 [InputParseException]。 */
    protected fun requireString(raw: ToolInputPayload, key: String): String =
        raw.optionalString(key) ?: missing(key)

    /** 读可选字符串；保持与 [ToolInputSupport.optionalString] 一致的语义。 */
    protected fun optionalString(raw: ToolInputPayload, key: String): String? = raw.optionalString(key)

    /** 读可选整数；保持与 [ToolInputSupport.optionalInt] 一致的语义。 */
    protected fun optionalInt(raw: ToolInputPayload, key: String): Int? = raw.optionalInt(key)

    /** 读可选字符串列表；保持与 [ToolInputSupport.optionalStringList] 一致的语义。 */
    protected fun optionalStringList(raw: ToolInputPayload, key: String): List<String> =
        raw.optionalStringList(key)

    /**
     * 读必填强类型值；缺失或类型不匹配时抛 [InputParseException]，消息区分两种场景：
     * - value 为 null/不在 map → [missing]
     * - value 类型不符 → [wrongType]，附期望与实际类型，便于模型纠正
     *
     * 注：非 inline 是为了避免「public-API inline 不能访问 internal inline」的限制；
     * 子类调用时显式传入 [KClass]。
     */
    protected fun <T : Any> requireValue(raw: ToolInputPayload, key: String, clazz: KClass<T>): T {
        val value = raw[key]
        if (value == null) missing(key)
        @Suppress("UNCHECKED_CAST")
        return if (clazz.isInstance(value)) {
            value as T
        } else {
            wrongType(key, expected = clazz.simpleName ?: "未知", actual = value?.javaClass?.simpleName ?: "null")
        }
    }

    /**
     * 读可选列表；类型不匹配的元素自动过滤。
     *
     * 与 [requireValue] 同理，非 inline；子类调用时显式传入 [KClass]。
     */
    protected fun <T : Any> optionalList(raw: ToolInputPayload, key: String, clazz: KClass<T>): List<T> {
        val rawList = (raw[key] as? List<*>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        return rawList.filter { clazz.isInstance(it) } as List<T>
    }

    /**
     * parse 阶段异常；可携带 [payload]，由基类透传到 failure ToolResult，
     * 让模型在缺失字段时仍能拿到部分有用的上下文（如已知可选字段值）。
     */
    class InputParseException(
        message: String,
        val payload: ToolPayload = emptyMap(),
    ) : IllegalArgumentException(message)
}
