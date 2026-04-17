package com.charmnight.linkgraph.llm.tools

/**
 * 工具统一返回结果。
 * 第一阶段先采用轻量 map payload，后续如果某类工具结构稳定，再逐步收敛为强类型结果对象。
 */
data class ToolResult(
    /** 产出该结果的工具名。 */
    val toolName: String,
    /** 工具执行是否成功。 */
    val success: Boolean = true,
    /** 结构化负载。 */
    val payload: Map<String, Any?> = emptyMap(),
    /** 失败说明。 */
    val errorMessage: String? = null,
)
