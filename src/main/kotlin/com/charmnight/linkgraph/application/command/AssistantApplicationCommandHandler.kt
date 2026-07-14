package com.charmnight.linkgraph.application.command

/** 处理统一助手任务并委托到助手工作流路由。 */
internal class AssistantApplicationCommandHandler(
    private val router: AssistantWorkflowRouter,
) {
    constructor(executor: AssistantTaskExecutor) : this(AssistantWorkflowRouter(executor))

    fun handle(command: ApplicationCommand.RequestAssistantTask) {
        router.route(command)
    }
}
