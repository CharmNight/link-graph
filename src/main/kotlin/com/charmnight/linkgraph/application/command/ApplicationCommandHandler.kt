package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest
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
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity

/**
 * 应用命令处理器契约。
 *
 * 把来自 UI/外部的命令分发到对应的工作流模块，
 * 子类按职责划分只处理自己关心的命令集合，便于横向扩展。
 */
internal interface ApplicationCommandHandler {
    /** 判断当前处理器能否处理指定命令，用于在责任链上做路由。 */
    fun canHandle(command: ApplicationCommand<*>): Boolean

    /** 真正执行命令，把命令参数映射到对应工作流的调用。 */
    fun handle(command: ApplicationCommand<*>): Any?
}

/**
 * 助手任务执行器接口。
 *
 * 抽象出助手相关任务的执行入口，让上层路由只关心意图，
 * 由具体实现决定把任务交给哪条工作流，从而解耦命令层与具体流程。
 */
internal interface AssistantTaskExecutor {
    /** 异步发起对图谱的美化/解释任务，附带聚焦节点与意图信息。 */
    fun executeExplanation(
        goal: String,
        focusNodeId: String?,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        intent: AssistantIntent,
        actionId: AssistantActionId,
    )

    /** 异步发起一次 QA 提问，可携带当前选中节点与来源线程。 */
    fun executeQa(
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode,
    )

    /** 触发生成方案的异步任务，由工作流编排后续步骤。 */
    fun executeGenerationPlan(userGoal: String)

    /** 针对生成方案中的某个条目发起一轮讨论，由助手回答用户问题。 */
    fun executeGenerationDiscussion(
        question: String,
        focusItemId: String?,
    )

    /** 基于选中的 diff 项发起审阅图谱的构建请求。 */
    fun executeReviewGraph(selectedDiffItemIds: List<String>)

    /** 基于选中的 diff 项发起一次 Diff 审阅问答。 */
    fun executeDiffReview(
        question: String,
        selectedDiffItemIds: List<String>,
    )
}

/**
 * 基于 Workflow 实现的助手任务执行器。
 *
 * 把抽象的助手任务桥接到具体的审阅/生成工作流，
 * 作为命令层与工作流层之间的「胶水」实现。
 */
internal class WorkflowAssistantTaskExecutor(
    // 审阅工作流，承担 QA、美化、Diff 审阅等能力
    private val reviewFlow: ReviewWorkflow,
    // 审阅图谱工作流，用于构建审阅场景的索引图谱
    private val reviewGraphFlow: ReviewGraphWorkflow,
    // 生成方案工作流，负责生成代码改动方案
    private val generationPlanFlow: GenerationPlanWorkflow,
    // 方案讨论工作流，负责方案条目的多轮问答
    private val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
) : AssistantTaskExecutor {
    override fun executeExplanation(
        goal: String,
        focusNodeId: String?,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        intent: AssistantIntent,
        actionId: AssistantActionId,
    ) {
        reviewFlow.requestGraphBeautificationAsync(
            goal = goal,
            focusNodeId = focusNodeId,
            followUp = followUp,
            granularity = granularity,
            assistantIntent = intent,
            assistantActionId = actionId,
        )
    }

    override fun executeQa(
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode,
    ) {
        reviewFlow.requestQaAsync(
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
    }

    override fun executeGenerationPlan(userGoal: String) {
        generationPlanFlow.requestGenerationPlanAsync(userGoal)
    }

    override fun executeGenerationDiscussion(
        question: String,
        focusItemId: String?,
    ) {
        generationDiscussionFlow.requestGenerationPlanDiscussionAsync(question, focusItemId)
    }

    override fun executeReviewGraph(selectedDiffItemIds: List<String>) {
        reviewGraphFlow.requestIndexedGraph(requestReviewGraphRequest(selectedDiffItemIds))
    }

    override fun executeDiffReview(
        question: String,
        selectedDiffItemIds: List<String>,
    ) {
        reviewFlow.requestDiffReviewAsync(question, selectedDiffItemIds)
    }
}

/**
 * 处理助手相关命令的命令处理器。
 *
 * 把 [ApplicationCommand.RequestAssistantTask] 委派给路由器，
 * 由路由器决定具体执行哪种助手任务。
 */
internal class AssistantApplicationCommandHandler(
    private val router: AssistantWorkflowRouter,
) : ApplicationCommandHandler {
    /** 便捷构造：直接传入执行器，内部自动包装成路由器。 */
    constructor(executor: AssistantTaskExecutor) : this(AssistantWorkflowRouter(executor))

    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestAssistantTask

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestAssistantTask -> router.route(command)
            else -> unhandled(command)
        }
}

/**
 * 处理「主体图谱」相关命令的处理器。
 *
 * 涵盖当前编辑器上下文预览、节点添加、加载、溢出节点展开、
 * 分析展示模式切换等动作，将命令映射到主体图谱工作流。
 */
internal class SubjectApplicationCommandHandler(
    private val subjectFlow: SubjectGraphWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.PreviewCurrentEditorSubjectKind ||
            command is ApplicationCommand.AddCurrentEditorContextNode ||
            command is ApplicationCommand.LoadCurrentEditorContextGraph ||
            command is ApplicationCommand.RequestExpandOverflowNode ||
            command is ApplicationCommand.RequestAnalysisDisplayMode

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.PreviewCurrentEditorSubjectKind ->
                subjectFlow.previewCurrentEditorSubjectKind(command.editor)
            ApplicationCommand.AddCurrentEditorContextNode ->
                subjectFlow.addCurrentEditorContextNode()
            is ApplicationCommand.LoadCurrentEditorContextGraph ->
                subjectFlow.loadCurrentEditorContextGraphAsync(command.editor)
            is ApplicationCommand.RequestExpandOverflowNode ->
                subjectFlow.requestExpandOverflowNode(command.nodeId)
            is ApplicationCommand.RequestAnalysisDisplayMode ->
                subjectFlow.requestAnalysisDisplayMode(command.displayMode)
            else -> unhandled(command)
        }
}

/**
 * 处理索引型图谱构建命令的处理器。
 *
 * 根据请求的视图类型（架构、类图、审阅）把命令分发到对应工作流，
 * 让前端只需发出统一命令，由该处理器负责内部路由。
 */
internal class IndexedGraphApplicationCommandHandler(
    private val architectureGraphFlow: ArchitectureGraphWorkflow,
    private val classDiagramFlow: ClassDiagramWorkflow,
    private val reviewGraphFlow: ReviewGraphWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestIndexedGraph

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestIndexedGraph -> {
                when (command.request.view) {
                    IndexedGraphView.ARCHITECTURE -> architectureGraphFlow.requestIndexedGraph(command.request)
                    IndexedGraphView.CLASS_DIAGRAM -> classDiagramFlow.requestIndexedGraph(command.request)
                    IndexedGraphView.REVIEW -> reviewGraphFlow.requestIndexedGraph(command.request)
                }
            }
            else -> unhandled(command)
        }
}

/**
 * 处理工作台图谱相关命令的处理器。
 *
 * 负责图谱加载、Mermaid 导入导出、Diff 模式展示、图编辑请求处理、
 * 布局变更回写、同步预览请求等命令，并在加载类动作前重置工作台上下文。
 */
internal class WorkspaceApplicationCommandHandler(
    private val workspaceFlow: GraphWorkspaceWorkflow,
    private val workspaceChangeCoordinator: WorkspaceChangeCoordinator,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.LoadGraph ||
            command is ApplicationCommand.ImportMermaid ||
            command is ApplicationCommand.ExportMermaid ||
            command is ApplicationCommand.ShowDiffMode ||
            command is ApplicationCommand.ApplyGraphEditRequest ||
            command is ApplicationCommand.LayoutChanged ||
            command is ApplicationCommand.RequestSyncPreview

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.LoadGraph -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.loadGraph(command.graph, command.source)
            }
            is ApplicationCommand.ImportMermaid -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.importMermaid(command.mermaid)
            }
            ApplicationCommand.ExportMermaid -> workspaceFlow.exportMermaid()
            ApplicationCommand.ShowDiffMode ->
                workspaceChangeCoordinator.invalidateRequests().let { workspaceFlow.showDiffMode() }
            is ApplicationCommand.ApplyGraphEditRequest -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.handleGraphEditRequest(command.parseResult)
            }
            is ApplicationCommand.LayoutChanged ->
                workspaceFlow.handleFrontendLayoutChanged(command.positions)
            ApplicationCommand.RequestSyncPreview -> workspaceFlow.requestSyncPreview()
            else -> unhandled(command)
        }
}

/**
 * 处理源码跳转与调用展开相关命令的处理器。
 *
 * 涵盖从图谱节点跳转到源码、展开调用、移除调用展开以及打开设置等动作，
 * 让用户能在图谱与源码之间双向协同。
 */
internal class SourceNavigationApplicationCommandHandler(
    private val sourceNavigationFlow: SourceNavigationWorkflow,
    private val invocationExpansionFlow: InvocationExpansionWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestSourceNavigation ||
            command is ApplicationCommand.RequestExpandInvocation ||
            command is ApplicationCommand.RequestRemoveInvocationExpansion ||
            command is ApplicationCommand.OpenSettings

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestSourceNavigation ->
                sourceNavigationFlow.requestSourceNavigation(command.nodeId)
            is ApplicationCommand.RequestExpandInvocation ->
                invocationExpansionFlow.requestExpandInvocation(command.nodeId)
            is ApplicationCommand.RequestRemoveInvocationExpansion ->
                invocationExpansionFlow.requestRemoveInvocationExpansion(command.expansionId)
            ApplicationCommand.OpenSettings -> sourceNavigationFlow.openSettings()
            else -> unhandled(command)
        }
}

/**
 * 处理审阅/QA 相关命令的处理器。
 *
 * 支持 QA 失败请求重试以及调查线程的状态处置，
 * 让审阅阶段的人机交互闭环更顺畅。
 */
internal class ReviewApplicationCommandHandler(
    private val reviewFlow: ReviewWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RetryLastQaRequest ||
            command is ApplicationCommand.ResolveInvestigationThread

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            ApplicationCommand.RetryLastQaRequest -> reviewFlow.retryLastQaRequestAsync()
            is ApplicationCommand.ResolveInvestigationThread -> reviewFlow.resolveInvestigationThread(
                threadId = command.threadId,
                status = command.status,
                note = command.note,
            )
            else -> unhandled(command)
        }
}

/**
 * 处理草稿确认与草稿补丁相关命令的处理器。
 *
 * 负责候选变更的确认/撤销、草稿补丁的预览应用/清除/恢复/撤销等动作，
 * 把草稿流转过程的全部操作集中收敛到本处理器。
 */
internal class DraftApplicationCommandHandler(
    private val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator,
    private val draftPatchFlow: DraftPatchWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.ConfirmQaCandidateChange ||
            command is ApplicationCommand.UnconfirmQaCandidateChange ||
            command is ApplicationCommand.ApplyDraftPatchPreview ||
            command is ApplicationCommand.ClearDraftPatchPreview ||
            command is ApplicationCommand.RestoreDraftPatchPreview ||
            command is ApplicationCommand.UndoLastDraftPatchApply

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.ConfirmQaCandidateChange ->
                confirmedDraftCoordinator.confirm(command.changeId)
            is ApplicationCommand.UnconfirmQaCandidateChange ->
                confirmedDraftCoordinator.unconfirm(command.changeId)
            is ApplicationCommand.ApplyDraftPatchPreview ->
                draftPatchFlow.applyDraftPatchPreview(command.operationIds)
            ApplicationCommand.ClearDraftPatchPreview ->
                draftPatchFlow.clearDraftPatchPreview()
            is ApplicationCommand.RestoreDraftPatchPreview ->
                draftPatchFlow.restoreDraftPatchPreview(command.source)
            ApplicationCommand.UndoLastDraftPatchApply ->
                draftPatchFlow.undoLastDraftPatchApply()
            else -> unhandled(command)
        }
}

/**
 * 处理代码生成相关命令的处理器。
 *
 * 涵盖代码草稿的请求、批量/单个应用、原生 diff 打开以及跳转到草稿目标路径，
 * 同时提供 diff 入口的可覆盖钩子，便于测试或定制场景替换默认行为。
 */
internal class GenerationApplicationCommandHandler(
    private val generationPlanFlow: GenerationPlanWorkflow,
    private val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
    private val codeDraftGenerationFlow: CodeDraftGenerationWorkflow,
    private val codeDraftApplyFlow: CodeDraftApplyWorkflow,
    // 打开代码草稿原生 diff 的覆盖函数，便于在测试或定制场景中替换默认实现
    private val openCodeDraftNativeDiffOverrideProvider: () -> ((String) -> Unit)? = { null },
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestCodeDrafts ||
            command is ApplicationCommand.ApplyCodeDrafts ||
            command is ApplicationCommand.ApplySingleCodeDraft ||
            command is ApplicationCommand.OpenCodeDraftNativeDiff ||
            command is ApplicationCommand.RequestDraftNavigation

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            ApplicationCommand.RequestCodeDrafts ->
                codeDraftGenerationFlow.requestCodeDraftsAsync()
            ApplicationCommand.ApplyCodeDrafts ->
                codeDraftApplyFlow.applyCodeDrafts()
            is ApplicationCommand.ApplySingleCodeDraft ->
                codeDraftApplyFlow.applySingleCodeDraft(command.draftId)
            is ApplicationCommand.OpenCodeDraftNativeDiff ->
                openCodeDraftNativeDiffOverrideProvider()?.invoke(command.draftId)
                    ?: codeDraftApplyFlow.openCodeDraftNativeDiff(command.draftId)
            is ApplicationCommand.RequestDraftNavigation ->
                codeDraftApplyFlow.requestDraftNavigation(command.targetPath)
            else -> unhandled(command)
        }
}

/**
 * 处理调试相关命令的处理器。
 *
 * 支持调试场景下切换分析展示模式、按方法签名加载调试图谱，以及按模式加载整图，
 * 让用户在调试期间能看到与方法/运行态相关的图谱视图。
 */
internal class DebugApplicationCommandHandler(
    private val debugFlow: ProjectDebugWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode ||
            command is ApplicationCommand.LoadDebugMethodGraphBySignature ||
            command is ApplicationCommand.LoadDebugGraph

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode ->
                debugFlow.prepareDebugRequestedAnalysisDisplayModeIfPresent(command.envName)
            is ApplicationCommand.LoadDebugMethodGraphBySignature ->
                debugFlow.loadDebugMethodGraphBySignatureAsync(command.signature)
            is ApplicationCommand.LoadDebugGraph ->
                debugFlow.loadDebugGraph(command.mode)
            else -> unhandled(command)
        }
}

/** 统一抛出「命令被路由到了错误的处理器」错误，便于快速定位路由配置问题。 */
private fun unhandled(command: ApplicationCommand<*>): Nothing =
    error("Command ${command::class.qualifiedName} was routed to the wrong handler")
