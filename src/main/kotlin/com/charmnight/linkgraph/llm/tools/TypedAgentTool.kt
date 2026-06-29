package com.charmnight.linkgraph.llm.tools

/**
 * 强类型 Agent 工具基类（m4 引入）。
 *
 * 历史上 [AgentTool.invoke] 入参是 `Map<String, Any?>`，每个 tool 自己用 `requiredString("xxx")` /
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
 * - 老的 `Map<String, Any?>` 直读 tool 可继续直接实现 AgentTool；TypedAgentTool 是首选实现方式
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
        input: Map<String, Any?>,
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
    protected abstract fun parseInput(raw: Map<String, Any?>): I

    /** 用解析后的强类型 input 执行工具。 */
    protected abstract fun invokeTyped(input: I, context: ToolExecutionContext): ToolResult

    // ---------- 共享的 input 解析 helper ----------

    /** 抛 [InputParseException]，让基类模板把异常翻译为 failure ToolResult。 */
    protected fun missing(key: String): Nothing = throw InputParseException("$key 不能为空")

    /** 读必填字符串；缺失或空白时抛 [InputParseException]。 */
    protected fun requireString(raw: Map<String, Any?>, key: String): String =
        raw.optionalString(key) ?: missing(key)

    /** 读可选字符串；保持与 [ToolInputSupport.optionalString] 一致的语义。 */
    protected fun optionalString(raw: Map<String, Any?>, key: String): String? = raw.optionalString(key)

    /** 读可选整数；保持与 [ToolInputSupport.optionalInt] 一致的语义。 */
    protected fun optionalInt(raw: Map<String, Any?>, key: String): Int? = raw.optionalInt(key)

    /** 读可选字符串列表；保持与 [ToolInputSupport.optionalStringList] 一致的语义。 */
    protected fun optionalStringList(raw: Map<String, Any?>, key: String): List<String> =
        raw.optionalStringList(key)

    /**
     * 读必填强类型值；类型不匹配或缺失时抛 [InputParseException]。
     *
     * 注：非 inline 是为了避免「public-API inline 不能访问 internal inline」的限制；
     * 子类调用时显式传入 [KClass]。
     */
    protected fun <T : Any> requireValue(raw: Map<String, Any?>, key: String, clazz: kotlin.reflect.KClass<T>): T {
        val value = raw[key]
        @Suppress("UNCHECKED_CAST")
        return if (clazz.isInstance(value)) value as T else missing(key)
    }

    /**
     * 读可选列表；类型不匹配的元素自动过滤。
     *
     * 与 [requireValue] 同理，非 inline；子类调用时显式传入 [KClass]。
     */
    protected fun <T : Any> optionalList(raw: Map<String, Any?>, key: String, clazz: kotlin.reflect.KClass<T>): List<T> {
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
        val payload: Map<String, Any?> = emptyMap(),
    ) : IllegalArgumentException(message)
}
