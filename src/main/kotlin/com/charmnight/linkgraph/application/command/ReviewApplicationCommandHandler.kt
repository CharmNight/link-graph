package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.ReviewWorkflow

/** 处理问答重试和调查线程决策命令。 */
internal class ReviewApplicationCommandHandler(
    private val reviewFlow: ReviewWorkflow,
) {
    fun handle(command: ApplicationCommand.RetryLastQaRequest) {
        reviewFlow.retryLastQaRequestAsync()
    }

    fun handle(command: ApplicationCommand.ResolveInvestigationThread) {
        reviewFlow.resolveInvestigationThread(command.threadId, command.status, command.note)
    }
}
