package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.diagnostics.GraphDiagnosticsLogger
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.application.port.GraphBeautificationPort
import com.charmnight.linkgraph.application.port.LlmApplicationServices
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeHooks
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureIndexWorkflowSupport
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.agent.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 基础设施组合：集中构建项目内各工作流共享的协作组件，包括运行时辅助、表现层端口、
 * 异步请求生命周期、规划上下文、Mermaid/Diff/Sync 等领域服务，以及架构索引支持。
 *
 * 组件按需初始化；默认同步 lazy 保证并发首访时每个 bean 的 initializer 只执行一次，
 * 维护项目级对象身份和状态一致性。
 */
internal class InfrastructureComposition(
    /** 当前项目实例。 */
    private val project: Project,
    /** 用于记录日志的诊断入口。 */
    private val logger: Logger,
) {
    /**
     * 测试覆盖项：从 project service 动态获取，让测试通过 [LinkGraphProjectRuntimeHooks] 注入。
     *
     * 生产环境永远拿到默认实例（所有字段为 null），不会影响运行时行为；
     * 测试通过 `project.replaceService(LinkGraphProjectRuntimeHooks::class.java, fake, disposable)` 注入。
     *
     * P3-2：从构造参数移到内部 getter，composition 接口不再暴露测试 hook。
     */
    private val runtimeHooks: LinkGraphProjectRuntimeHooks
        get() = project.getService(LinkGraphProjectRuntimeHooks::class.java)

    /** 项目级运行时辅助，提供打开设置、生成设置等能力。 */
    val runtimeSupport by lazy {
        LinkGraphProjectRuntimeSupport(
            project = project,
            logger = logger,
            openSettingsHook = { runtimeHooks.openSettings },
            effectiveGenerationSettingsHook = { runtimeHooks.effectiveGenerationSettings },
        )
    }

    /** 表现层端口，统一对外提供编辑器快照、应用快照、事件汇等子端口。 */
    val presentationProvider by lazy {
        project.getService(GraphEditorPresentationProvider::class.java)
    }

    /** 编辑器快照提供者，用于读取当前编辑器中的图谱视图状态。 */
    val editorSnapshotProvider by lazy {
        presentationProvider.editorSnapshotProvider()
    }

    /** 应用级快照提供者，提供跨工具窗口的统一应用态视图。 */
    val applicationSnapshotProvider by lazy {
        presentationProvider.applicationSnapshotProvider()
    }

    /** 工具图谱快照提供者，用于读取工具侧的图谱内容。 */
    val toolGraphSnapshotProvider by lazy {
        presentationProvider.toolGraphSnapshotProvider()
    }

    /** 工作区图谱提交器，用于把工作区的草稿图谱合并回主图谱。 */
    val workspaceGraphCommitter by lazy {
        presentationProvider.workspaceGraphCommitter()
    }

    /** 事件汇，集中接收来自 UI 和后台的各类事件。 */
    val eventSink by lazy {
        presentationProvider.eventSink()
    }

    /** 智能体产物仓库，用于持久化生成过程中产生的各类产物。 */
    val artifactStore by lazy {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    }

    /** 异步请求生命周期支持，负责追踪与超时控制。 */
    val asyncRequestLifecycle by lazy {
        AsyncRequestLifecycleSupport(
            project = project,
            timeoutMillisSupplier = { runtimeHooks.asyncRequestTimeoutMillis },
            onRequestsInvalidated = { invalidated ->
                eventSink.emit(
                    GraphEditorApplicationEvent.AsyncRequestsInvalidated(
                        qaRequestId = invalidated.qaRequestId,
                        diffReviewRequestId = invalidated.diffReviewRequestId,
                        beautificationRequestId = invalidated.beautificationRequestId,
                        generationPlanRequestId = invalidated.generationPlanRequestId,
                        generationPlanDiscussionRequestId = invalidated.generationPlanDiscussionRequestId,
                        codeDraftRequestId = invalidated.codeDraftRequestId,
                    ),
                )
            },
        )
    }

    /** Mermaid 文本导入器。 */
    val mermaidImporter by lazy { MermaidImporter() }
    /** Mermaid 文本校验器。 */
    val mermaidValidator by lazy { MermaidValidator() }
    /** Mermaid 文本导出器。 */
    val mermaidExporter by lazy { MermaidExporter() }

    /** 图谱差异计算器。 */
    val graphDiffer by lazy { GraphDiffer() }
    /** 同步预览规划器。 */
    val syncPreviewPlanner by lazy { SyncPreviewPlanner() }
    /** 图谱补丁应用服务。 */
    val graphPatchApplyService by lazy { GraphPatchApplyService() }

    /** LLM 应用端口；具体网关和服务装配位于应用层之外。 */
    private val llmServices by lazy {
        project.getService(LlmApplicationServices::class.java)
    }

    /**
     * P4-3 平台无关任务调度入口：项目级共享 [com.charmnight.linkgraph.application.runtime.TaskRunner]。
     *
     * 通过 `project.getService(TaskRunner::class.java)` 取得实现，与 [presentationProvider] 同模式。
     * 生产实现是 [com.charmnight.linkgraph.ui.runtime.IntelliJTaskRunnerAdapter]（在 plugin.xml 注册）；
     * 测试实现走 `testServiceImplementation`，自动替换为 [com.charmnight.linkgraph.application.runtime.SameThreadTaskRunner]。
     *
     * workflow 通过本字段调度后台 / UI / 读锁任务，不再直接 import IntelliJ 的 ReadAction / invokeLater /
     * AppExecutorUtil / ModalityState。application 层与 IntelliJ 平台解耦。
     */
    val taskRunner by lazy {
        project.getService(com.charmnight.linkgraph.application.runtime.TaskRunner::class.java)
    }

    /** 图谱生成服务。 */
    val graphGenerationService by lazy { llmServices.graphGenerationService() }
    /** 图谱 QA 补丁服务。 */
    val graphQaPatchService by lazy { llmServices.graphQaPatchService() }
    /** 草稿工作台服务。 */
    val draftWorkbenchService by lazy { DraftWorkbenchService() }
    /** 风险消解服务。 */
    val riskResolutionService by lazy { RiskResolutionService() }
    /** 图谱差异补丁服务。 */
    val graphDiffPatchService by lazy { llmServices.graphDiffPatchService() }
    /** 图谱美化服务，用于在展示前对生成结果做风格优化。 */
    val graphBeautificationService: GraphBeautificationPort by lazy {
        llmServices.graphBeautificationService()
    }
    /** 生成计划追问服务。 */
    val generationPlanDiscussionService by lazy {
        llmServices.generationPlanDiscussionService()
    }
    /** 代码生成服务。 */
    val codeGenerationService by lazy { llmServices.codeGenerationService() }
    /** 代码草稿写入服务，把生成结果写入到目标位置。 */
    val codeDraftWriterService by lazy { CodeDraftWriterService(project) }
    /** 图谱诊断日志记录器。 */
    val graphDiagnosticsLogger by lazy { GraphDiagnosticsLogger(logger) }
    /** 调试用图谱工厂，主要用于构造调试态样例图谱。 */
    val debugGraphFactory by lazy { DebugGraphFactory() }

    /** 架构索引工作流支持，封装架构维度的索引与查询逻辑。 */
    val architectureIndexSupport by lazy {
        ArchitectureIndexWorkflowSupport(project)
    }

    /** 规划上下文工厂，把差异、同步、生成等组件组合为统一的规划入口。 */
    val planningContextFactory by lazy {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            projectBasePathProvider = { project.basePath },
        )
    }
}
