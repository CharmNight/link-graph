package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.command.ApplicationCommandDispatcher
import com.charmnight.linkgraph.application.composition.ApplicationCommandComposition
import com.charmnight.linkgraph.application.composition.ApplicationWorkflowComposition
import com.charmnight.linkgraph.application.composition.ApplicationWorkflows
import com.charmnight.linkgraph.application.composition.InfrastructureComposition
import com.charmnight.linkgraph.application.composition.LifecycleLazy
import com.charmnight.linkgraph.application.composition.WorkflowComposition
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeHooks
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 项目级组合根，统一装配 Link Graph 工作流及其共享协作者。
 *
 * 该服务只负责对象装配，不包含业务逻辑。
 * 生产入口应依赖聚焦的命令服务（包装各工作流），
 * 而不是直接依赖这个宽口径的项目服务门面。
 * 单个工作流的装配放在 [WorkflowComposition]；
 * 共享的基础设施协作者放在 [InfrastructureComposition]。
 */
@Service(Service.Level.PROJECT)
internal class GraphEditorApplicationService(
    /** 当前 IntelliJ 项目。 */
    private val project: Project,
) : Disposable {
    /** 测试桩注入入口；运行期为空，仅在单元测试中被覆写。 */
    private val runtimeHooks: LinkGraphProjectRuntimeHooks
        get() = project.getService(LinkGraphProjectRuntimeHooks::class.java)

    /** 共享基础设施协作者集合；惰性初始化以避免循环依赖。 */
    private val infrastructure: InfrastructureComposition by lazy {
        InfrastructureComposition(
            project = project,
            logger = logger,
        )
    }

    /** 各工作流装配集合；惰性初始化以保证装配顺序。 */
    private val workflowsDelegate = LifecycleLazy(
        initializer = {
            WorkflowComposition(
                project = project,
                logger = logger,
                infrastructure = infrastructure,
            )
        },
        disposer = WorkflowComposition::dispose,
    )
    private val workflows: WorkflowComposition by workflowsDelegate

    /** 应用工作流集合包装器，把单个工作流统一暴露给命令派发层。 */
    private val workflowComposition by lazy {
        ApplicationWorkflowComposition(
            workflowsProvider = {
                ApplicationWorkflows(
                    subjectFlow = workflows.subjectFlow,
                    workspaceChangeCoordinator = workflows.workspaceChangeCoordinator,
                    workspaceFlow = workflows.workspaceFlow,
                    draftPatchFlow = workflows.draftPatchFlow,
                    generationPlanFlow = workflows.generationPlanFlow,
                    generationDiscussionFlow = workflows.generationDiscussionFlow,
                    codeDraftGenerationFlow = workflows.codeDraftGenerationFlow,
                    codeDraftApplyFlow = workflows.codeDraftApplyFlow,
                    reviewFlow = workflows.reviewFlow,
                    sourceNavigationFlow = workflows.sourceNavigationFlow,
                    invocationExpansionFlow = workflows.invocationExpansionFlow,
                    architectureGraphFlow = workflows.architectureGraphFlow,
                    classDiagramFlow = workflows.classDiagramFlow,
                    reviewGraphFlow = workflows.reviewGraphFlow,
                    confirmedDraftCoordinator = workflows.confirmedDraftCoordinator,
                    debugFlow = workflows.debugFlow,
                )
            },
        )
    }

    /** 应用命令派发器；惰性初始化以集中装配各命令处理器。 */
    private val commandDispatcherDelegate = LifecycleLazy(
        initializer = {
            ApplicationCommandComposition(
                workflows = workflowComposition.workflows(),
                openCodeDraftNativeDiffHook = { runtimeHooks.openCodeDraftNativeDiff },
            ).dispatcher()
        },
        disposer = {},
    )
    val commandDispatcher: ApplicationCommandDispatcher by commandDispatcherDelegate

    /** 释放工作流持有的资源。 */
    override fun dispose() {
        commandDispatcherDelegate.dispose()
        workflowsDelegate.dispose()
    }

    companion object {
        /** 调试环境变量名：用于覆写分析展示模式。 */
        const val DEBUG_ANALYSIS_DISPLAY_MODE_ENV: String = "LINKGRAPH_DEBUG_ANALYSIS_DISPLAY_MODE"
        /** 本服务的日志记录器，供基础设施与工作流组合共享。 */
        private val logger = Logger.getInstance(GraphEditorApplicationService::class.java)
    }
}
