package com.charmnight.linkgraph.llm.tools

/**
 * 统一管理可用工具集合。
 * capability 只按名称拿工具，避免把具体实现类散落到 workflow 或 runtime 之外。
 */
class AgentToolRegistry(
    tools: List<AgentTool>,
) {
    private val toolsByName = LinkedHashMap<String, AgentTool>().apply {
        val duplicateNames = tools
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
        require(duplicateNames.isEmpty()) {
            "重复注册的工具名: ${duplicateNames.joinToString()}"
        }
        tools.forEach { tool -> put(tool.name, tool) }
    }

    /** 按名称查找工具，不存在时返回 null。 */
    fun find(name: String): AgentTool? = toolsByName[name]?.let(::withRuntimeAllowedToolGuard)

    /** 按名称查找工具，不存在时直接失败。 */
    fun require(name: String): AgentTool {
        return find(name) ?: throw IllegalArgumentException("未注册的工具: $name")
    }

    /** 列出所有已注册工具。 */
    fun all(): List<AgentTool> = toolsByName.values.map(::withRuntimeAllowedToolGuard)

    /** 列出所有已注册工具名。 */
    fun names(): Set<String> = toolsByName.keys.toSet()

    private fun withRuntimeAllowedToolGuard(tool: AgentTool): AgentTool =
        object : AgentTool {
            override val name: String = tool.name
            override val description: String = tool.description

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult {
                val allowedToolNames = context.allowedToolNames ?: return tool.invoke(input, context)
                check(name in allowedToolNames) {
                    "工具未被当前 capability 允许: $name"
                }
                return tool.invoke(input, context)
            }
        }
}
