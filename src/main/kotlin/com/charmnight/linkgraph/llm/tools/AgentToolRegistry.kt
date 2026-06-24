package com.charmnight.linkgraph.llm.tools

/**
 * 统一管理可用工具集合。
 *
 * capability 只按名称拿工具，避免把具体实现类散落到 workflow 或 runtime 之外。
 * 本注册中心还内置"capability 允许工具"的运行时校验：
 * 即使 capability 拿到了某个工具，也必须在自己的 allowedToolNames 中声明才能调用，
 * 防止 capability 误用未声明的工具。
 */
class AgentToolRegistry(
    tools: List<AgentTool>,
) {
    /** 工具名 → 工具对象的有序映射；保留注册顺序便于稳定遍历。 */
    private val toolsByName = LinkedHashMap<String, AgentTool>().apply {
        // 启动时检查重复注册，避免运行时随机命中某个版本
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

    /** 按名称查找工具，不存在时返回 null。返回的对象已包装为运行时校验版。 */
    fun find(name: String): AgentTool? = toolsByName[name]?.let(::withRuntimeAllowedToolGuard)

    /** 按名称查找工具，不存在时直接失败。 */
    fun require(name: String): AgentTool {
        return find(name) ?: throw IllegalArgumentException("未注册的工具: $name")
    }

    /** 列出所有已注册工具（包装版）。 */
    fun all(): List<AgentTool> = toolsByName.values.map(::withRuntimeAllowedToolGuard)

    /** 列出所有已注册工具名。 */
    fun names(): Set<String> = toolsByName.keys.toSet()

    /**
     * 把工具包装为带运行时校验的版本。
     * 调用 invoke 前检查 capability 是否声明允许使用该工具，未声明则抛错。
     * 这种"动态代理"风格让校验逻辑只在一个地方维护。
     */
    private fun withRuntimeAllowedToolGuard(tool: AgentTool): AgentTool =
        object : AgentTool {
            override val name: String = tool.name
            override val description: String = tool.description

            override fun invoke(
                input: Map<String, Any?>,
                context: ToolExecutionContext,
            ): ToolResult {
                // allowedToolNames 为 null 表示该调用点尚未启用 capability 策略，直接放行
                val allowedToolNames = context.allowedToolNames ?: return tool.invoke(input, context)
                check(name in allowedToolNames) {
                    "工具未被当前 capability 允许: $name"
                }
                return tool.invoke(input, context)
            }
        }
}
