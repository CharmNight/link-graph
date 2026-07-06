package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.artifact.ConfirmedDraftArtifactWriter
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeHooks
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
import com.charmnight.linkgraph.application.workflow.generation.GenerationWorkflowDependencies
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow
import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.agent.capability.CodegenCapability
import com.charmnight.linkgraph.agent.capability.PlanCapability
import com.charmnight.linkgraph.agent.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.jvm.index.PsiJvmImplementationSignatureResolver
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MyBatisXmlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.SqlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.XmlResourceSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.YamlPropertiesSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.intellij.diff.DiffManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * 在基础设施组合之上装配工作流层；所有工作流均以 PUBLICATION 模式延迟构造，
 * 共享依赖统一来自基础设施组合，使工作流之间的相互调用（如评审流复用工作台编辑执行器）
 * 保持显式可追溯。
 */
internal class WorkflowComposition(
    private val project: Project,
    private val logger: Logger,
    private val infrastructure: InfrastructureComposition,
) {
    /**
     * 测试覆盖项：从 project service 动态获取（P3-2 重构）。
     * 生产环境永远拿到默认实例；测试通过 replaceService 注入。
     */
    private val runtimeHooks: LinkGraphProjectRuntimeHooks
        get() = project.getService(LinkGraphProjectRuntimeHooks::class.java)

    // P2-1: 共享语义基础设施委托给 CompositionSharedInfrastructure
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CompositionSharedInfrastructure.createCodeSubjectHandleFactory()
    }

    private val defaultSubjectLocator: SubjectLocator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CompositionSharedInfrastructure.createDefaultSubjectLocator()
    }

    private val defaultSemanticAnalyzer: SemanticAnalyzer by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CompositionSharedInfrastructure.createDefaultSemanticAnalyzer(project, logger, infrastructure)
    }

    private val defaultAnalysisOutcomeFactory: AnalysisOutcomeFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CompositionSharedInfrastructure.createDefaultAnalysisOutcomeFactory(infrastructure)
    }

    private val subjectLocator: SubjectLocator
        get() = CompositionSharedInfrastructure.resolveSubjectLocator(runtimeHooks)

    private val semanticAnalyzer: SemanticAnalyzer
        get() = CompositionSharedInfrastructure.resolveSemanticAnalyzer(runtimeHooks, project, logger, infrastructure)

    /** 分析结果工厂入口：优先使用测试覆盖注入的实现，否则回落到默认工厂。 */
    private val analysisOutcomeFactory: AnalysisOutcomeFactory
        get() = runtimeHooks.analysisOutcomeFactory ?: defaultAnalysisOutcomeFactory

    /** 主题图谱工作流：以光标主题为入口触发语义分析并写入工作台图谱。 */
    val subjectFlow: SubjectGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SubjectGraphWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            taskRunner = infrastructure.taskRunner,
            subjectLocatorProvider = { subjectLocator },
            semanticAnalyzerProvider = { semanticAnalyzer },
            analysisOutcomeFactoryProvider = { analysisOutcomeFactory },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            onInvalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            onLogGraphDiagnostics = infrastructure.graphDiagnosticsLogger::log,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
            logger = logger,
        )
    }

    /** 工作台变更协调器：在工作台提交变更后联动清理主题分析缓存与异步请求，保证多流之间状态一致。 */
    val workspaceChangeCoordinator: WorkspaceChangeCoordinator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        WorkspaceChangeCoordinator(
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            clearSubjectAnalysisCache = subjectFlow::clearLastAnalysisCache,
            invalidateAsyncRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
        )
    }

    /** 工作台工作流：负责图谱的导入导出、Diff、合并预览与编辑请求处理等核心工作台能力。 */
    val workspaceFlow: GraphWorkspaceWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GraphWorkspaceWorkflow(
            snapshotProvider = infrastructure.editorSnapshotProvider,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            mermaidImporter = infrastructure.mermaidImporter,
            mermaidValidator = infrastructure.mermaidValidator,
            mermaidExporter = infrastructure.mermaidExporter,
            graphDiffer = infrastructure.graphDiffer,
            syncPreviewPlanner = infrastructure.syncPreviewPlanner,
            copyToClipboard = { exported ->
                runCatching {
                    CopyPasteManager.getInstance().setContents(StringSelection(exported))
                    true
                }.getOrDefault(false)
            },
        )
    }

    /** 草稿补丁工作流：把应用层快照中的草稿补丁应用到目标图谱并发布事件。 */
    val draftPatchFlow: DraftPatchWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DraftPatchWorkflow(
            snapshotProvider = infrastructure.applicationSnapshotProvider,
            eventSink = infrastructure.eventSink,
            graphPatchApplyService = infrastructure.graphPatchApplyService,
        )
    }

    /** 生成类工作流共享依赖集合：包含规划上下文、代码生成、产物写入、智能体运行协调等通用能力。 */
    val generationDependencies: GenerationWorkflowDependencies by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationWorkflowDependencies(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            toolGraphSnapshotProvider = infrastructure.toolGraphSnapshotProvider,
            planningContextFactory = infrastructure.planningContextFactory,
            graphGenerationService = infrastructure.graphGenerationService,
            codeGenerationService = infrastructure.codeGenerationService,
            codeDraftWriterService = infrastructure.codeDraftWriterService,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            eventSink = infrastructure.eventSink,
            settingsProvider = infrastructure.runtimeSupport::effectiveGenerationSettings,
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            taskRunner = infrastructure.taskRunner,
            logger = logger,
            artifactStoreProvider = { infrastructure.artifactStore },
            agentRunCoordinator = AgentRunCoordinator(),
            planCapabilityFactory = { planExecutor ->
                PlanCapability(planExecutor = planExecutor)
            },
            codegenCapabilityFactory = { codegenExecutor ->
                CodegenCapability(project = project, codegenExecutor = codegenExecutor)
            },
            riskResolutionService = infrastructure.riskResolutionService,
            generationPlanDiscussionService = infrastructure.generationPlanDiscussionService,
            showCodeDraftMergeRequest = { currentProject, request ->
                DiffManager.getInstance().showMerge(currentProject, request)
            },
        )
    }

    /** 生成计划工作流：与用户协作确定代码生成计划，作为后续代码草稿生成的前置流程。 */
    val generationPlanFlow: GenerationPlanWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationPlanWorkflow(generationDependencies)
    }

    /** 生成计划讨论工作流：围绕生成计划开展多轮对话，沉淀用户反馈。 */
    val generationDiscussionFlow: GenerationPlanDiscussionWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationPlanDiscussionWorkflow(generationDependencies)
    }

    /** 代码草稿生成工作流：基于生成计划产出可应用的代码草稿。 */
    val codeDraftGenerationFlow: CodeDraftGenerationWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeDraftGenerationWorkflow(generationDependencies)
    }

    /** 代码草稿应用工作流：把已确认的代码草稿合并入项目，触发 Diff 视图与文件写入。 */
    val codeDraftApplyFlow: CodeDraftApplyWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeDraftApplyWorkflow(generationDependencies)
    }

    /** 评审工作流：综合 QA 补丁、Diff 补丁与图谱美化能力，对当前图谱进行质量评审与修复。 */
    val reviewFlow: ReviewWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReviewWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            toolGraphSnapshotProvider = infrastructure.toolGraphSnapshotProvider,
            eventSink = infrastructure.eventSink,
            planningContextFactory = infrastructure.planningContextFactory,
            graphQaPatchService = infrastructure.graphQaPatchService,
            graphDiffPatchService = infrastructure.graphDiffPatchService,
            graphBeautificationService = infrastructure.graphBeautificationService,
            graphDiffer = infrastructure.graphDiffer,
            settingsProvider = infrastructure.runtimeSupport::effectiveGenerationSettings,
            qaExecutorHook = { runtimeHooks.qaExecutor },
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            taskRunner = infrastructure.taskRunner,
            logger = logger,
            artifactStoreProvider = { infrastructure.artifactStore },
            graphEditRequestExecutor = workspaceFlow::handleGraphEditRequest,
        )
    }

    /** 源码导航工作流：把图谱节点跳转请求映射到编辑器中的具体代码位置。 */
    val sourceNavigationFlow: SourceNavigationWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SourceNavigationWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            eventSink = infrastructure.eventSink,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            navigationNodeFinder = ::findTrustedNavigationNodeFromIndex,
            showSettingsDialog = infrastructure.runtimeSupport::openSettingsDialog,
            logger = logger,
            taskRunner = infrastructure.taskRunner,
        )
    }

    /** 调用展开工作流：以某个调用为起点向下展开更深的语义关系，丰富当前图谱区域。 */
    val invocationExpansionFlow: InvocationExpansionWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        InvocationExpansionWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            semanticAnalyzerProvider = { semanticAnalyzer },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            targetResolverHook = { runtimeHooks.invocationExpansionTargetResolver },
            subjectResolverHook = { runtimeHooks.invocationExpansionSubjectResolver },
            logger = logger,
            taskRunner = infrastructure.taskRunner,
            implementationSignatureResolver = PsiJvmImplementationSignatureResolver(project),
        )
    }

    /** 架构图谱工作流：维护并对外提供架构索引，作为跨文件/跨服务关系展示的数据源。 */
    val architectureGraphFlow: ArchitectureGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ArchitectureGraphWorkflow(
            project = project,
            indexSupport = infrastructure.architectureIndexSupport,
            eventSink = infrastructure.eventSink,
            logger = logger,
            taskRunner = infrastructure.taskRunner,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    /** 类图工作流：基于架构索引生成类级别关系视图，用于评审整体结构。 */
    val classDiagramFlow: ClassDiagramWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ClassDiagramWorkflow(
            project = project,
            indexSupport = infrastructure.architectureIndexSupport,
            eventSink = infrastructure.eventSink,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            logger = logger,
            taskRunner = infrastructure.taskRunner,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    /** 评审图谱工作流：基于架构索引和图谱差异能力，提供评审所需的对比视图。 */
    val reviewGraphFlow: ReviewGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReviewGraphWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            indexSupport = infrastructure.architectureIndexSupport,
            graphDiffer = infrastructure.graphDiffer,
            eventSink = infrastructure.eventSink,
            logger = logger,
            taskRunner = infrastructure.taskRunner,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    /** 已确认草稿变更协调器：统一处理草稿确认后的图谱应用、产物写入、缓存失效与诊断记录。 */
    val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ConfirmedDraftChangeCoordinator(
            snapshotProvider = infrastructure.applicationSnapshotProvider,
            eventSink = infrastructure.eventSink,
            draftWorkbenchService = infrastructure.draftWorkbenchService,
            graphPatchApplyService = infrastructure.graphPatchApplyService,
            graphDiagnosticsLogger = infrastructure.graphDiagnosticsLogger,
            artifactWriter = ConfirmedDraftArtifactWriter { infrastructure.artifactStore },
            invalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            logger = logger,
            runtimeTrace = infrastructure.runtimeSupport.eagerRuntimeTraceSink(),
        )
    }

    /** 项目调试工作流：在调试会话中提供专用图谱，复用主题图谱并触发 QA 缓存失效。 */
    val debugFlow: ProjectDebugWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ProjectDebugWorkflow(
            logger = logger,
            debugGraphFactory = infrastructure.debugGraphFactory,
            subjectGraphWorkflow = subjectFlow,
            invalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            eventSink = infrastructure.eventSink,
        )
    }
}

/**
 * 在编辑器快照的可信导航节点集合中查找指定节点，避免跳转到尚未通过校验的临时节点。
 */
private fun findTrustedNavigationNodeFromIndex(
    snapshot: WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? = snapshot.trustedNavigationNodes[nodeId]
