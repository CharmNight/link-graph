package com.charmnight.linkgraph.application.command

/** 组合根显式构造的命令处理 bean 集合。 */
internal class ApplicationCommandHandlers(
    val subject: SubjectApplicationCommandHandler,
    val indexedGraph: IndexedGraphApplicationCommandHandler,
    val workspace: WorkspaceApplicationCommandHandler,
    val sourceNavigation: SourceNavigationApplicationCommandHandler,
    val assistant: AssistantApplicationCommandHandler,
    val review: ReviewApplicationCommandHandler,
    val draft: DraftApplicationCommandHandler,
    val generation: GenerationApplicationCommandHandler,
    val debug: DebugApplicationCommandHandler,
)
