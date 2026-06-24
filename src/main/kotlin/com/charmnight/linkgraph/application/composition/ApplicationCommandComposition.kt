package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.command.ApplicationCommandDispatcher
import com.charmnight.linkgraph.application.command.AssistantApplicationCommandHandler
import com.charmnight.linkgraph.application.command.DebugApplicationCommandHandler
import com.charmnight.linkgraph.application.command.DraftApplicationCommandHandler
import com.charmnight.linkgraph.application.command.GenerationApplicationCommandHandler
import com.charmnight.linkgraph.application.command.IndexedGraphApplicationCommandHandler
import com.charmnight.linkgraph.application.command.ReviewApplicationCommandHandler
import com.charmnight.linkgraph.application.command.SourceNavigationApplicationCommandHandler
import com.charmnight.linkgraph.application.command.SubjectApplicationCommandHandler
import com.charmnight.linkgraph.application.command.WorkflowAssistantTaskExecutor
import com.charmnight.linkgraph.application.command.WorkspaceApplicationCommandHandler
import com.charmnight.linkgraph.application.workflow.ConfirmedDraftChangeCoordinator
import com.charmnight.linkgraph.application.workflow.DraftPatchWorkflow
import com.charmnight.linkgraph.application.workflow.GraphWorkspaceWorkflow
import com.charmnight.linkgraph.application.workflow.InvocationExpansionWorkflow
import com.charmnight.linkgraph.application.workflow.ProjectDebugWorkflow
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.application.workflow.SourceNavigationWorkflow
import com.charmnight.linkgraph.application.workflow.SubjectGraphWorkflow
import com.charmnight.linkgraph.application.workflow.WorkspaceChangeCoordinator
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureGraphWorkflow
import com.charmnight.linkgraph.application.workflow.architecture.ClassDiagramWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow

/**
 * 应用命令组合根。
 *
 * 把所有 ApplicationCommandHandler 实例按固定顺序注册到统一的调度器中，
 * 上层只需拿到 dispatcher 即可派发任意命令，不需要关心各 handler 的依赖关系。
 * 命令处理器的注册顺序决定了匹配优先级，新增 handler 时需谨慎放置位置。
 */
internal class ApplicationCommandComposition(
    /** 已组装好的所有工作流集合，作为各 handler 的依赖来源。 */
    private val workflows: ApplicationWorkflows,
    /** 打开代码草稿原生 diff 视图的覆盖回调；调用方必须显式注入（不再提供默认 { null }）。 */
    private val openCodeDraftNativeDiffOverrideProvider: () -> ((String) -> Unit)?,
) {
    /**
     * 构造聚合所有 handler 的命令调度器。
     */
    fun dispatcher(): ApplicationCommandDispatcher =
        ApplicationCommandDispatcher(
            listOf(
                SubjectApplicationCommandHandler(workflows.subjectFlow),
                IndexedGraphApplicationCommandHandler(
                    architectureGraphFlow = workflows.architectureGraphFlow,
                    classDiagramFlow = workflows.classDiagramFlow,
                    reviewGraphFlow = workflows.reviewGraphFlow,
                ),
                WorkspaceApplicationCommandHandler(
                    workspaceFlow = workflows.workspaceFlow,
                    workspaceChangeCoordinator = workflows.workspaceChangeCoordinator,
                ),
                SourceNavigationApplicationCommandHandler(
                    sourceNavigationFlow = workflows.sourceNavigationFlow,
                    invocationExpansionFlow = workflows.invocationExpansionFlow,
                ),
                AssistantApplicationCommandHandler(
                    WorkflowAssistantTaskExecutor(
                        reviewFlow = workflows.reviewFlow,
                        reviewGraphFlow = workflows.reviewGraphFlow,
                        generationPlanFlow = workflows.generationPlanFlow,
                        generationDiscussionFlow = workflows.generationDiscussionFlow,
                    ),
                ),
                ReviewApplicationCommandHandler(workflows.reviewFlow),
                DraftApplicationCommandHandler(
                    confirmedDraftCoordinator = workflows.confirmedDraftCoordinator,
                    draftPatchFlow = workflows.draftPatchFlow,
                ),
                GenerationApplicationCommandHandler(
                    generationPlanFlow = workflows.generationPlanFlow,
                    generationDiscussionFlow = workflows.generationDiscussionFlow,
                    codeDraftGenerationFlow = workflows.codeDraftGenerationFlow,
                    codeDraftApplyFlow = workflows.codeDraftApplyFlow,
                    openCodeDraftNativeDiffOverrideProvider = openCodeDraftNativeDiffOverrideProvider,
                ),
                DebugApplicationCommandHandler(workflows.debugFlow),
            ),
        )
}

/**
 * 应用层工作流集合。
 *
 * 把所有工作流及其协调器聚合在一个数据对象里，方便作为依赖统一注入到命令处理器、
 * 事件处理器等其他组件中，避免散落的多处构造导致依赖图难以追踪。
 */
internal data class ApplicationWorkflows(
    val subjectFlow: SubjectGraphWorkflow,
    val workspaceChangeCoordinator: WorkspaceChangeCoordinator,
    val workspaceFlow: GraphWorkspaceWorkflow,
    val draftPatchFlow: DraftPatchWorkflow,
    val generationPlanFlow: GenerationPlanWorkflow,
    val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
    val codeDraftGenerationFlow: CodeDraftGenerationWorkflow,
    val codeDraftApplyFlow: CodeDraftApplyWorkflow,
    val reviewFlow: ReviewWorkflow,
    val sourceNavigationFlow: SourceNavigationWorkflow,
    val invocationExpansionFlow: InvocationExpansionWorkflow,
    val architectureGraphFlow: ArchitectureGraphWorkflow,
    val classDiagramFlow: ClassDiagramWorkflow,
    val reviewGraphFlow: ReviewGraphWorkflow,
    val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator,
    val debugFlow: ProjectDebugWorkflow,
)
