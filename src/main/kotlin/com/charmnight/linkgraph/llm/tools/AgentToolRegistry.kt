package com.charmnight.linkgraph.llm.tools

/**
 * 统一管理可用工具集合。
 * capability 只按名称拿工具，避免把具体实现类散落到 workflow 或 runtime 之外。
 */
class AgentToolRegistry(
    tools: List<AgentTool>,
) {
    private val toolsByName = LinkedHashMap<String, AgentTool>().apply {
        tools.forEach { tool -> put(tool.name, tool) }
    }

    /** 按名称查找工具，不存在时返回 null。 */
    fun find(name: String): AgentTool? = toolsByName[name]

    /** 按名称查找工具，不存在时直接失败。 */
    fun require(name: String): AgentTool {
        return find(name) ?: throw IllegalArgumentException("未注册的工具: $name")
    }

    /** 列出所有已注册工具。 */
    fun all(): List<AgentTool> = toolsByName.values.toList()
}
