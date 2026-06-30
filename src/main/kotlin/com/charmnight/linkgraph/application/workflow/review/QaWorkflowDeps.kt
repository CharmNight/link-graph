package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.investigation.application.InvestigationGraphPatchAdapter
import com.charmnight.linkgraph.investigation.application.InvestigationPipeline
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.artifact.ArtifactStore
import com.charmnight.linkgraph.agent.capability.QaCapability
import com.charmnight.linkgraph.agent.capability.QaCapabilityInput
import com.charmnight.linkgraph.agent.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaConversationService
import com.charmnight.linkgraph.workbench.QaModeClassifier
import com.charmnight.linkgraph.workbench.QaRequestLifecycleService
import com.charmnight.linkgraph.application.workflow.QaResultNormalizer
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.project.Project

/**
 * QA 工作流共享依赖容器（P2-1 深度重构）。
 *
 * 把 ReviewWorkflow 中 QA 执行路径用到的 17 个实例依赖打包为一个不可变数据类，
 * 供 QaOrchestrator / QaRuntimeExecutor 等独立 class 接收，避免每个 class 构造器
 * 暴露 15+ 个参数。
 */
internal data class QaWorkflowDeps(
    val project: Project,
    val snapshotProvider: EditorSnapshotProvider,
    val toolGraphSnapshotProvider: ToolGraphSnapshotProvider,
    val eventSink: GraphEditorApplicationEventSink,
    val planningContextFactory: PlanningContextFactory,
    val settingsProvider: () -> LinkGraphSettingsState,
    val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    val agentRunCoordinator: AgentRunCoordinator,
    val artifactStoreProvider: () -> ArtifactStore,
    val graphEditRequestExecutor: GraphEditRequestExecutor?,
    val runtimeQaTraceEnabled: Boolean,
    val qaCapabilityFactory: (QaCapability.QaExecutor) -> QaCapability,
    val qaRequestLifecycleService: QaRequestLifecycleService,
    val qaResultNormalizer: QaResultNormalizer,
    val riskResolutionService: RiskResolutionService,
    val investigationPipelineFactory: (Project) -> InvestigationPipeline,
    val investigationGraphPatchAdapter: InvestigationGraphPatchAdapter,
    val qaModeClassifier: QaModeClassifier,
) {
    /** 快照便捷访问。 */
    fun snapshot(): WorkflowEditorSnapshot = snapshotProvider.snapshot()

    /** 当前设置便捷访问。 */
    fun settings(): LinkGraphSettingsState = settingsProvider()
}
