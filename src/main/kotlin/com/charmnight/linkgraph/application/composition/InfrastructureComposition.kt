package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.diagnostics.GraphDiagnosticsLogger
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureIndexWorkflowSupport
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.DefaultGraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
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
 * 所有组件都使用 [LazyThreadSafetyMode.PUBLICATION] 进行懒加载，这样即使 EDT 和后台线程
 * 并发首次访问也能保证安全，同时避免在热点路径上承担同步懒加载的额外开销。
 */
internal class InfrastructureComposition(
    /** 当前项目实例。 */
    private val project: Project,
    /** 用于记录日志的诊断入口。 */
    private val logger: Logger,
) {
    /**
     * 测试覆盖项：从 project service 动态获取，让测试通过 [LinkGraphProjectTestOverrides] 注入。
     *
     * 生产环境永远拿到默认实例（所有字段为 null），不会影响运行时行为；
     * 测试通过 `project.replaceService(LinkGraphProjectTestOverrides::class.java, fake, disposable)` 注入。
     *
     * P3-2：从构造参数移到内部 getter，composition 接口不再暴露测试 hook。
     */
    private val testOverrides: LinkGraphProjectTestOverrides
        get() = project.getService(LinkGraphProjectTestOverrides::class.java)

    /** 项目级运行时辅助，提供打开设置、生成设置等能力。 */
    val runtimeSupport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        LinkGraphProjectRuntimeSupport(
            project = project,
            logger = logger,
            openSettingsOverrideProvider = { testOverrides.openSettings },
            effectiveGenerationSettingsOverrideProvider = { testOverrides.effectiveGenerationSettings },
        )
    }

    /** 表现层端口，统一对外提供编辑器快照、应用快照、事件汇等子端口。 */
    val presentationProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        project.getService(GraphEditorPresentationProvider::class.java)
    }

    /** 编辑器快照提供者，用于读取当前编辑器中的图谱视图状态。 */
    val editorSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.editorSnapshotProvider()
    }

    /** 应用级快照提供者，提供跨工具窗口的统一应用态视图。 */
    val applicationSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.applicationSnapshotProvider()
    }

    /** 工具图谱快照提供者，用于读取工具侧的图谱内容。 */
    val toolGraphSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.toolGraphSnapshotProvider()
    }

    /** 工作区图谱提交器，用于把工作区的草稿图谱合并回主图谱。 */
    val workspaceGraphCommitter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.workspaceGraphCommitter()
    }

    /** 事件汇，集中接收来自 UI 和后台的各类事件。 */
    val eventSink by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.eventSink()
    }

    /** 智能体产物仓库，用于持久化生成过程中产生的各类产物。 */
    val artifactStore by lazy(LazyThreadSafetyMode.PUBLICATION) {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    }

    /** 异步请求生命周期支持，负责追踪与超时控制。 */
    val asyncRequestLifecycle by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AsyncRequestLifecycleSupport(
            project = project,
            timeoutOverrideProvider = { testOverrides.asyncRequestTimeoutMillis },
        )
    }

    /** Mermaid 文本导入器。 */
    val mermaidImporter by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidImporter() }
    /** Mermaid 文本校验器。 */
    val mermaidValidator by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidValidator() }
    /** Mermaid 文本导出器。 */
    val mermaidExporter by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidExporter() }

    /** 图谱差异计算器。 */
    val graphDiffer by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiffer() }
    /** 同步预览规划器。 */
    val syncPreviewPlanner by lazy(LazyThreadSafetyMode.PUBLICATION) { SyncPreviewPlanner() }
    /** 图谱补丁应用服务。 */
    val graphPatchApplyService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphPatchApplyService() }

    /**
     * P3-1 统一 LLM gateway 入口：项目级共享，由 EP 注册的 contributor 路由到具体协议实现。
     *
     * 第三方通过 plugin.xml 的 `<com.charmnight.linkgraph.llmGatewayContributor>` EP 注册自定义协议，
     * 由本类把 contributor 包装为 [com.charmnight.linkgraph.llm.LlmGateway]，
     * 实际 HTTP 请求强制经过 [com.charmnight.linkgraph.llm.LlmGatewayClient]（SSRF / size guard 自动应用）。
     */
    val llmGateway by lazy(LazyThreadSafetyMode.PUBLICATION) {
        com.charmnight.linkgraph.llm.LlmGatewayCompositionRoot.createGateway(project)
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
    val taskRunner by lazy(LazyThreadSafetyMode.PUBLICATION) {
        project.getService(com.charmnight.linkgraph.application.runtime.TaskRunner::class.java)
    }

    /** 图谱生成服务。 */
    val graphGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphGenerationService(gateway = llmGateway) }
    /** 图谱 QA 补丁服务。 */
    val graphQaPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphQaPatchService(gateway = llmGateway) }
    /** 草稿工作台服务。 */
    val draftWorkbenchService by lazy(LazyThreadSafetyMode.PUBLICATION) { DraftWorkbenchService() }
    /** 风险消解服务。 */
    val riskResolutionService by lazy(LazyThreadSafetyMode.PUBLICATION) { RiskResolutionService() }
    /** 图谱差异补丁服务。 */
    val graphDiffPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiffPatchService(gateway = llmGateway) }
    /** 图谱美化服务，用于在展示前对生成结果做风格优化。 */
    val graphBeautificationService: GraphBeautificationService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DefaultGraphBeautificationService(gateway = llmGateway)
    }
    /** 代码生成服务。 */
    val codeGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) { CodeGenerationService(gateway = llmGateway) }
    /** 代码草稿写入服务，把生成结果写入到目标位置。 */
    val codeDraftWriterService by lazy(LazyThreadSafetyMode.PUBLICATION) { CodeDraftWriterService(project) }
    /** 图谱诊断日志记录器。 */
    val graphDiagnosticsLogger by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiagnosticsLogger(logger) }
    /** 调试用图谱工厂，主要用于构造调试态样例图谱。 */
    val debugGraphFactory by lazy(LazyThreadSafetyMode.PUBLICATION) { DebugGraphFactory() }

    /** 架构索引工作流支持，封装架构维度的索引与查询逻辑。 */
    val architectureIndexSupport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ArchitectureIndexWorkflowSupport(project)
    }

    /** 规划上下文工厂，把差异、同步、生成等组件组合为统一的规划入口。 */
    val planningContextFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            projectBasePathProvider = { project.basePath },
        )
    }
}
