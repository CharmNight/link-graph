package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.runtime.SameThreadTaskRunner
import com.charmnight.linkgraph.application.runtime.TaskRunner
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.application.usecase.InvocationExpansionUseCase
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.jvm.index.JvmImplementationSignatureResolver
import com.charmnight.linkgraph.jvm.index.NoopJvmImplementationSignatureResolver
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 调用展开工作流：把图谱中代表一次方法调用的节点展开为该方法自身的链路，
 * 并把展开得到的子图合并进当前工作区图谱；也负责撤销之前已合入的展开。
 * 内部串联校验、目标解析、语义分析、合并提交与反馈发送。
 */
internal class InvocationExpansionWorkflow(
    /** 当前 IntelliJ 项目。 */
    private val project: Project,
    /** 编辑器快照提供者，用于读取当前工作区图谱及选中状态。 */
    private val snapshotProvider: EditorSnapshotProvider,
    /** 工作区图谱提交器，负责把合并后的图谱写回并触发同步。 */
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    /** 应用事件出口，用于向 UI 推送反馈。 */
    private val eventSink: GraphEditorApplicationEventSink,
    /** 语义分析器延迟提供者，避免不必要的初始化。 */
    private val semanticAnalyzerProvider: () -> SemanticAnalyzer,
    /** 分析结果工厂延迟提供者，把分析结果转为可渲染的图谱。 */
    private val analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    /** 用于把 PSI 方法包装为统一代码主题句柄的工厂。 */
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory,
    /** 目标解析自定义覆盖点，测试或扩展时可注入替代实现。 */
    private val targetResolverHook: () -> ((Project, String) -> InvocationExpansionTarget)?,
    /** 主题解析自定义覆盖点，测试或扩展时可注入替代实现。 */
    private val subjectResolverHook: () -> ((String) -> CodeSubjectHandle?)?,
    /** 日志记录器。 */
    private val logger: Logger,
    /** 平台读锁/UI/后台调度端口，避免 workflow 直接依赖 IntelliJ threading API。 */
    private val taskRunner: TaskRunner = SameThreadTaskRunner(),
    /** 索引缺失实现边时的实现签名 fallback。 */
    private val implementationSignatureResolver: JvmImplementationSignatureResolver = NoopJvmImplementationSignatureResolver,
    /** 封装展开/合并/移除核心规则的业务用例。 */
    private val useCase: InvocationExpansionUseCase = InvocationExpansionUseCase(),
    /** 把签名解析为可展开目标的解析器。 */
    private val targetResolver: InvocationExpansionTargetResolver = InvocationExpansionTargetResolver(),
) {
    /** 接收一个调用节点 ID，校验后展开其对应方法并把链路合入工作区图谱。 */
    fun requestExpandInvocation(nodeId: String) {
        val snapshot = snapshotProvider.snapshot()
        val node = findInvocationNode(snapshot, nodeId)
        if (node == null) {
            emitFeedback(ApplicationFeedbackLevel.WARNING, "未找到需要展开的调用节点。")
            return
        }

        when (useCase.validateInvocationNode(node)) {
            InvocationExpansionUseCase.ValidationResult.NOT_INVOCATION -> {
                emitFeedback(ApplicationFeedbackLevel.WARNING, "当前节点不是可展开的方法调用节点。")
                return
            }
            InvocationExpansionUseCase.ValidationResult.MISSING_SIGNATURE -> {
                emitFeedback(ApplicationFeedbackLevel.WARNING, "当前调用节点缺少目标方法签名，无法展开。")
                return
            }
            InvocationExpansionUseCase.ValidationResult.READY -> Unit
        }

        val sourceSignature = node.signature.orEmpty().trim()
        val target = resolveTarget(sourceSignature)
        val targetSignatures = expandableTargetSignatures(target, sourceSignature)
        if (targetSignatures.isEmpty()) {
            emitFeedback(ApplicationFeedbackLevel.INFO, nonExpandableMessage(target, sourceSignature))
            return
        }

        var analysisFailed = false
        var workingGraph = snapshot.workspaceGraph
        var mergedCount = 0
        val missingSignatures = mutableListOf<String>()
        targetSignatures.forEach { targetSignature ->
            val handle = resolveSubject(targetSignature)
            if (handle == null) {
                missingSignatures += targetSignature
                return@forEach
            }
            val mergeResult = runCatching {
                val analysisResult = semanticAnalyzerProvider().analyze(
                    handle = handle,
                    capturePolicy = SemanticCapturePolicy(),
                    budgetPolicy = TraversalBudgetPolicy(),
                )
                val targetGraph = analysisOutcomeFactoryProvider()
                    .create(analysisResult, AnalysisDisplayMode.FLOWCHART)
                    .fullGraph
                val targetEntryNodeId = resolveTargetEntryNodeId(analysisResult, targetGraph, targetSignature)
                targetEntryNodeId ?: return@runCatching null
                useCase.mergeExpansion(
                    workspace = workingGraph,
                    sourceInvocationNode = node,
                    targetGraph = targetGraph,
                    targetEntryNodeId = targetEntryNodeId,
                    targetSignature = targetSignature,
                )
            }.onFailure { throwable ->
                analysisFailed = true
                logger.warn("展开调用方法失败", throwable)
                emitFeedback(
                    ApplicationFeedbackLevel.ERROR,
                    "展开调用方法失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }.getOrNull()
            if (mergeResult != null) {
                workingGraph = mergeResult.graph
                mergedCount += 1
            }
        }

        if (mergedCount == 0) {
            when {
                missingSignatures.isNotEmpty() ->
                    emitFeedback(ApplicationFeedbackLevel.WARNING, "未在当前项目中找到方法：${missingSignatures.joinToString("；")}")
                !analysisFailed ->
                    emitFeedback(ApplicationFeedbackLevel.WARNING, "目标方法没有可合入当前图的链路。")
            }
            return
        }

        val committed = workspaceGraphCommitter.commitWorkspaceGraph(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = workingGraph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            preserveDraftPatchUndo = true,
            workingGraphDirty = true,
            syncBrowser = true,
        )
        if (committed) {
            emitFeedback(ApplicationFeedbackLevel.SUCCESS, "已展开调用方法：${node.title}")
        } else {
            emitFeedback(ApplicationFeedbackLevel.WARNING, "当前图已变化，请重新选择调用节点后再展开。")
        }
    }

    /** 将可展开目标归一化为一个或多个目标签名；外部/未解析目标返回空列表。 */
    private fun expandableTargetSignatures(
        target: InvocationExpansionTarget,
        sourceSignature: String,
    ): List<String> =
        when (target.kind) {
            InvocationExpansionTargetKind.PROJECT_SOURCE ->
                listOf(target.signature?.takeIf(String::isNotBlank) ?: sourceSignature)
            InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS ->
                target.candidateSignatures.map(String::trim).filter(String::isNotBlank).distinct()
            else -> emptyList()
        }

    /** 根据展开批次 ID，撤销之前已合入工作区图谱的展开内容。 */
    fun requestRemoveInvocationExpansion(expansionId: String) {
        val trimmedExpansionId = expansionId.trim()
        if (trimmedExpansionId.isEmpty()) {
            emitFeedback(ApplicationFeedbackLevel.WARNING, "缺少需要移除的展开批次。")
            return
        }
        val snapshot = snapshotProvider.snapshot()
        val removalResult = useCase.removeExpansion(snapshot.workspaceGraph, trimmedExpansionId)
        if (!removalResult.removed) {
            emitFeedback(ApplicationFeedbackLevel.INFO, "未找到对应的调用展开内容。")
            return
        }

        val committed = workspaceGraphCommitter.commitWorkspaceGraph(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = removalResult.graph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            preserveDraftPatchUndo = true,
            workingGraphDirty = true,
            syncBrowser = true,
        )
        if (committed) {
            emitFeedback(ApplicationFeedbackLevel.SUCCESS, "已移除调用展开内容。")
        } else {
            emitFeedback(ApplicationFeedbackLevel.WARNING, "当前图已变化，请重新选择展开内容后再移除。")
        }
    }

    /** 解析方法签名所属的展开目标（项目源码 / JDK / 三方库 / 跨服务等），优先使用自定义覆盖。 */
    private fun resolveTarget(signature: String): InvocationExpansionTarget {
        targetResolverHook()?.invoke(project, signature)?.let { target -> return target }
        val index = project.architectureIndexRuntime().index()
        return targetResolver.resolve(signature, index, implementationSignatureResolver)
    }

    /** 把签名解析为可分析的代码主题句柄，优先使用自定义覆盖，否则在 PSI 中定位方法并包装。 */
    private fun resolveSubject(signature: String): CodeSubjectHandle? {
        subjectResolverHook()?.invoke(signature)?.let { handle -> return handle }
        return taskRunner.read {
            val method = DebugMethodSignatureLocator.find(project, signature)
            if (method == null) {
                logResolveSubjectFailure(signature, "methodNotFound")
                return@read null
            }
            val file = method.containingFile ?: method.navigationElement.containingFile
            if (file == null) {
                logResolveSubjectFailure(
                    signature = signature,
                    reason = "fileMissing",
                    method = method,
                )
                return@read null
            }
            codeSubjectHandleFactory.create(file, method)
        }
    }

    private fun logResolveSubjectFailure(
        signature: String,
        reason: String,
        method: com.intellij.psi.PsiMethod? = null,
    ) {
        if (!LinkGraphDebugEnvironment.isEnabled(DEBUG_TRACE_ENV)) {
            return
        }
        logger.warn(
            "debug 调用展开定位目标失败: " +
                "signature=$signature, " +
                "reason=$reason, " +
                "methodSignature=${method?.let { candidate -> com.charmnight.linkgraph.semantic.subject.methodSignature(candidate) }.orEmpty()}, " +
                "methodClass=${method?.containingClass?.qualifiedName.orEmpty()}, " +
                "containingFile=${method?.containingFile?.virtualFile?.path ?: method?.containingFile?.name.orEmpty()}, " +
                "navigationFile=${method?.navigationElement?.containingFile?.virtualFile?.path ?: method?.navigationElement?.containingFile?.name.orEmpty()}",
        )
    }

    /** 在编辑器快照的多张视图与工作区图谱中查找代表该调用的可展开节点。 */
    private fun findInvocationNode(
        snapshot: com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot,
        nodeId: String,
    ): GraphNode? {
        val candidateNodeIds = linkedSetOf(nodeId)
        sequenceOf(
            snapshot.flowchartView.projectionIndex,
            snapshot.factGraphView.projectionIndex,
            snapshot.resourceRelationView.projectionIndex,
        ).mapNotNull { projectionIndex -> projectionIndex.nodeMapping(nodeId) }
            .flatMap { mapping -> mapping.canonicalNodeIds.asSequence() }
            .forEach(candidateNodeIds::add)

        return candidateNodeIds
            .asSequence()
            .mapNotNull { candidateNodeId ->
                snapshot.trustedNavigationNodes[candidateNodeId]
                    ?: sequenceOf(
                        currentVisibleGraph(snapshot),
                        snapshot.flowchartView.visibleGraph,
                        snapshot.factGraphView.visibleGraph,
                        snapshot.workspaceGraph,
                    ).flatMap { graph -> graph.nodes.asSequence() }
                        .firstOrNull { node -> node.id == candidateNodeId }
            }
            .firstOrNull { node ->
                node.type == com.charmnight.linkgraph.model.NodeType.FLOW_ACTION &&
                    node.metadata["flow.kind"] == "INVOCATION"
            }
    }

    /** 在展开得到的子图中找出与目标签名匹配的入口节点 ID，作为合并时的对接点。 */
    private fun resolveTargetEntryNodeId(
        analysisResult: SemanticAnalysisResult,
        targetGraph: GraphDocument,
        targetSignature: String,
    ): String? {
        val graphNodeIds = targetGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        analysisResult.anchors
            .asSequence()
            .mapNotNull { anchor -> anchor.targetUnitId }
            .firstOrNull { unitId -> unitId in graphNodeIds }
            ?.let { unitId -> return unitId }

        val methodUnitId = analysisResult.semanticUnits
            .filterIsInstance<MethodLikeUnit>()
            .firstOrNull { unit -> unit.signature == targetSignature }
            ?.id
            ?.takeIf { unitId -> unitId in graphNodeIds }
        return methodUnitId ?: targetGraph.nodes.firstOrNull()?.id
    }

    /** 针对不可展开的目标（JDK、三方库、多实现、跨服务等）生成对应的用户提示文案。 */
    private fun nonExpandableMessage(
        target: InvocationExpansionTarget,
        signature: String,
    ): String {
        return target.message ?: when (target.kind) {
            InvocationExpansionTargetKind.EXTERNAL_JDK -> "JDK 方法不合入当前图，可使用打开源码/详情查看：$signature"
            InvocationExpansionTargetKind.EXTERNAL_LIBRARY -> "三方组件方法不合入当前图，可使用打开源码/详情查看：$signature"
            InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS ->
                "接口或抽象方法存在多个实现，暂不自动展开：${target.candidateSignatures.joinToString("；").ifBlank { signature }}"
            InvocationExpansionTargetKind.NO_IMPLEMENTATION -> "未找到接口或抽象方法的项目内实现，暂不展开：$signature"
            InvocationExpansionTargetKind.CROSS_SERVICE -> "跨服务调用暂不合入当前图：$signature"
            InvocationExpansionTargetKind.NOT_FOUND -> "未找到可展开的目标方法：$signature"
            InvocationExpansionTargetKind.PROJECT_SOURCE -> "目标方法可展开：$signature"
        }
    }

    /** 把一条反馈事件发送到应用事件出口。 */
    private fun emitFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
    ) {
        eventSink.emit(GraphEditorApplicationEvent.Feedback(level, message))
    }

    private companion object {
        private const val DEBUG_TRACE_ENV = "LINKGRAPH_DEBUG_TRACE"
    }

}
