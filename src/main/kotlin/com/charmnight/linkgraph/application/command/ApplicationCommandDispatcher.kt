package com.charmnight.linkgraph.application.command

/** 应用层命令派发器：把类型化命令交给组合根中显式定义的 handler bean。 */
internal class ApplicationCommandDispatcher(
    private val handlers: ApplicationCommandHandlers,
) {
    fun <R> dispatch(command: ApplicationCommand<R>): R = command.dispatchTo(handlers)
}
