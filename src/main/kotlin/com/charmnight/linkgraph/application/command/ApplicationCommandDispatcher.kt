package com.charmnight.linkgraph.application.command

/**
 * 应用层命令派发器。
 *
 * 持有一组 [ApplicationCommandHandler]，收到命令时按顺序找到第一个能处理该命令的 handler 并执行。
 * 这种"责任链 + 派发"的写法让命令和处理逻辑可以按需扩展，新增命令只需新增 handler。
 *
 * 找不到 handler 时直接抛出 IllegalStateException，因为这种情形属于编程错误（注册遗漏），
 * 不应让上层吞掉错误。
 */
internal class ApplicationCommandDispatcher(
    /** 候选 handler 列表；顺序敏感，前面的优先处理。 */
    private val handlers: List<ApplicationCommandHandler>,
) {
    /**
     * 派发命令到匹配的 handler 上并返回结果。
     *
     * @param command 待派发的命令
     * @return handler 返回的结果，类型由命令定义
     * @throws IllegalStateException 没有任何 handler 能处理该命令时抛出
     */
    fun <R> dispatch(command: ApplicationCommand<R>): R {
        val handler = handlers.firstOrNull { candidate -> candidate.canHandle(command) }
            ?: error("No application command handler registered for ${command::class.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return handler.handle(command) as R
    }
}
