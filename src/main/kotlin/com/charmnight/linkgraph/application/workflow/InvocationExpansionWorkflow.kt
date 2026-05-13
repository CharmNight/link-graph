package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.application.usecase.InvocationExpansionUseCase
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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal class InvocationExpansionWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    private val eventSink: GraphEditorApplicationEventSink,
    private val semanticAnalyzerProvider: () -> SemanticAnalyzer,
    private val analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory,
    private val targetResolverOverrideProvider: () -> ((Project, String) -> InvocationExpansionTarget)?,
    private val subjectResolverOverrideProvider: () -> ((String) -> CodeSubjectHandle?)?,
    private val logger: Logger,
    private val useCase: InvocationExpansionUseCase = InvocationExpansionUseCase(),
) {
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
        if (target.kind != InvocationExpansionTargetKind.PROJECT_SOURCE) {
            emitFeedback(ApplicationFeedbackLevel.INFO, nonExpandableMessage(target, sourceSignature))
            return
        }

        val targetSignature = target.signature?.takeIf(String::isNotBlank) ?: sourceSignature
        val handle = resolveSubject(targetSignature)
        if (handle == null) {
            emitFeedback(ApplicationFeedbackLevel.WARNING, "未在当前项目中找到方法：$targetSignature")
            return
        }

        var analysisFailed = false
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
                ?: return@runCatching null
            useCase.mergeExpansion(
                workspace = snapshot.workspaceGraph,
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

        if (mergeResult == null) {
            if (!analysisFailed) {
                emitFeedback(ApplicationFeedbackLevel.WARNING, "目标方法没有可合入当前图的链路。")
            }
            return
        }

        val committed = workspaceGraphCommitter.commitWorkspaceGraph(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = mergeResult.graph,
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

    private fun resolveTarget(signature: String): InvocationExpansionTarget {
        targetResolverOverrideProvider()?.invoke(project, signature)?.let { target -> return target }
        val ownerName = signature.substringBefore('(', signature).substringBeforeLast('.', missingDelimiterValue = signature)
        return when {
            ownerName.startsWith("java.") || ownerName.startsWith("javax.") || ownerName.startsWith("jdk.") ->
                InvocationExpansionTarget(InvocationExpansionTargetKind.EXTERNAL_JDK)
            THIRD_PARTY_PREFIXES.any(ownerName::startsWith) ->
                InvocationExpansionTarget(InvocationExpansionTargetKind.EXTERNAL_LIBRARY)
            else -> InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
    }

    private fun resolveSubject(signature: String): CodeSubjectHandle? {
        subjectResolverOverrideProvider()?.invoke(signature)?.let { handle -> return handle }
        return ReadAction.compute<CodeSubjectHandle?, RuntimeException> {
            val method = DebugMethodSignatureLocator.find(project, signature) ?: return@compute null
            val file = method.containingFile ?: method.navigationElement.containingFile ?: return@compute null
            codeSubjectHandleFactory.create(file, method)
        }
    }

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

    private fun nonExpandableMessage(
        target: InvocationExpansionTarget,
        signature: String,
    ): String {
        return target.message ?: when (target.kind) {
            InvocationExpansionTargetKind.EXTERNAL_JDK -> "JDK 方法不合入当前图，可使用打开源码/详情查看：$signature"
            InvocationExpansionTargetKind.EXTERNAL_LIBRARY -> "三方组件方法不合入当前图，可使用打开源码/详情查看：$signature"
            InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS ->
                "接口或抽象方法存在多个实现，暂不自动展开：${target.candidateSignatures.joinToString("；").ifBlank { signature }}"
            InvocationExpansionTargetKind.NO_IMPLEMENTATION -> "未找到接口或抽象方法的唯一实现，暂不展开：$signature"
            InvocationExpansionTargetKind.CROSS_SERVICE -> "跨服务调用暂不合入当前图：$signature"
            InvocationExpansionTargetKind.NOT_FOUND -> "未找到可展开的目标方法：$signature"
            InvocationExpansionTargetKind.PROJECT_SOURCE -> "目标方法可展开：$signature"
        }
    }

    private fun emitFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
    ) {
        eventSink.emit(GraphEditorApplicationEvent.Feedback(level, message))
    }

    private companion object {
        val THIRD_PARTY_PREFIXES = listOf(
            "org.",
            "com.fasterxml.",
            "com.google.",
            "io.",
            "reactor.",
            "kotlin.",
        )
    }
}
