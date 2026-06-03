package com.charmnight.linkgraph.application.command

internal class ApplicationCommandDispatcher(
    private val handlers: List<ApplicationCommandHandler>,
) {
    fun <R> dispatch(command: ApplicationCommand<R>): R {
        val handler = handlers.firstOrNull { candidate -> candidate.canHandle(command) }
            ?: error("No application command handler registered for ${command::class.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return handler.handle(command) as R
    }
}
