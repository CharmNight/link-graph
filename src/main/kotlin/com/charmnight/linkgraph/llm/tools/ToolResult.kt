package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

/**
 * 工具统一返回结果。
 *
 * 第一阶段先采用轻量 map payload，后续如果某类工具结构稳定，再逐步收敛为强类型结果对象。
 * 工具调用方按 toolName 区分处理逻辑，按 success 判断成功失败，
 * 失败时从 errorMessage 取原因，成功时从 payload 取结构化数据。
 */
data class ToolResult(
    /** 产出该结果的工具名。 */
    val toolName: String,
    /** 工具执行是否成功。 */
    val success: Boolean = true,
    /** 结构化负载。键由具体工具自定义，值可以是任意 JSON 友好的类型。 */
    val payload: ToolPayload = emptyMap(),
    /** 失败说明。仅在 success=false 时有意义。 */
    val errorMessage: String? = null,
)
