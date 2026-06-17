package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.architecture.ClassDiagramProjector
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRelationDetail
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.cacheState
import com.charmnight.linkgraph.application.indexed.classDiagramScopeNodeId
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.usage.ClassUsageGraphProjector
import com.charmnight.linkgraph.usage.ClassUsageSearchOptions
import com.charmnight.linkgraph.usage.ClassUsageSearchService
import com.charmnight.linkgraph.usage.ClassUsageSearchTargetHint
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal class ClassDiagramWorkflow(
    private val project: Project,
    private val indexSupport: ClassDiagramIndexSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    snapshotProvider: EditorSnapshotProvider? = null,
    private val projector: ClassDiagramProjector = ClassDiagramProjector(),
    private val usageSearchService: ClassUsageSearchService = ClassUsageSearchService(project),
    private val usageProjector: ClassUsageGraphProjector = ClassUsageGraphProjector(),
    private val scopeResolver: ClassDiagramScopeResolver = ClassDiagramScopeResolver(project, snapshotProvider),
    private val logger: com.intellij.openapi.diagnostic.Logger,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private val requestIds = AtomicLong()

    fun requestIndexedGraph(request: IndexedGraphRequest) {
        val requestId = requestIds.incrementAndGet()
        val scopeNodeId = request.classDiagramScopeNodeId()
        val workflowPlan = ClassDiagramWorkflowPlan(request)
        val startMessage = workflowPlan.startMessage(scopeNodeId)
        val runningState = AsyncRequestState.running(
            requestId = requestId,
            scene = request.view.name,
            statusMessage = startMessage,
        )
        eventSink.emit(
            GraphEditorApplicationEvent.IndexedGraphRequestStarted(
                view = IndexedGraphView.CLASS_DIAGRAM,
                requestState = runningState,
                statusMessage = startMessage,
            ),
        )
        val resolvedScopeNodeId = runCatching {
            if (project.isDisposed) {
                null
            } else {
                scopeResolver.resolve(scopeNodeId)
            }
        }.onFailure { error ->
            logger.warn("解析类图范围失败，将回退到项目默认锚点", error)
        }.getOrNull()
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                buildStandaloneUsageView(request)?.let { view ->
                    return@nonBlocking ClassDiagramViewResult.success(
                        ClassDiagramViewPayload(
                            view = view,
                            resolvedScopeNodeId = view.anchorNodeId,
                            request = request,
                        ),
                    )
                }
                var indexForCompleteBuild: com.charmnight.linkgraph.architecture.ArchitectureGraphIndex? = null
                runCatching {
                    val hasFullIndex = indexSupport.hasFullIndex(request)
                    val cacheState = request.cacheState(hasFullIndex)
                    val indexStartedAt = System.nanoTime()
                    val index = if (hasFullIndex) {
                        indexSupport.buildIndex(request)
                    } else {
                        indexSupport.buildClassDiagramStructureIndex(request)
                    }
                    indexForCompleteBuild = index
                    val symbolIndexHint = if (hasFullIndex) {
                        null
                    } else {
                        index.symbolIndex
                    }
                    val relationCompleteness = if (hasFullIndex) {
                        "COMPLETE"
                    } else {
                        ClassDiagramFastIndex.RELATION_COMPLETENESS_PARTIAL
                    }
                    traceStage(
                        if (hasFullIndex) {
                            "classDiagram.buildIndex"
                        } else {
                            "classDiagram.buildStructureIndex"
                        },
                        indexStartedAt,
                    ) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "requestedScopeNodeId=${scopeNodeId.orEmpty()}",
                            "resolvedScopeNodeId=${resolvedScopeNodeId.orEmpty()}",
                            "relationCompleteness=$relationCompleteness",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "fields=${index.symbolIndex.fieldsByQualifiedName.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(
                        index = index,
                        scopeNodeId = resolvedScopeNodeId,
                        relationCompleteness = relationCompleteness,
                        request = request,
                        cacheState = cacheState,
                        freshness = indexSupport.freshness(),
                    ).let { view ->
                        applyUsageOverlay(
                            index = index,
                            request = request,
                            view = view,
                            scopeNodeId = resolvedScopeNodeId,
                        )
                    }.also { view ->
                        traceStage("classDiagram.project", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "requestedScopeNodeId=${scopeNodeId.orEmpty()}",
                                "resolvedScopeNodeId=${resolvedScopeNodeId.orEmpty()}",
                                "relationCompleteness=$relationCompleteness",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                            )
                        }
                    }.let { view ->
                        ClassDiagramViewPayload(
                            view = view,
                            symbolIndexHint = symbolIndexHint,
                            resolvedScopeNodeId = resolvedScopeNodeId,
                            request = request,
                        )
                    }
                }.fold(
                    onSuccess = { payload -> ClassDiagramViewResult.success(payload) },
                    onFailure = { error ->
                        val partialIndex = indexForCompleteBuild
                        if (partialIndex != null &&
                            !indexSupport.hasFullIndex(request) &&
                            workflowPlan.shouldRequestCompleteRelations()
                        ) {
                            requestCompleteClassDiagram(
                                scopeNodeId = resolvedScopeNodeId,
                                symbolIndexHint = partialIndex.symbolIndex,
                                request = request,
                                requestId = requestId,
                                startedAtEpochMillis = runningState.startedAtEpochMillis,
                            )
                        }
                        ClassDiagramViewResult.failure(error)
                    },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("构建类图失败", result.failure)
                        val message = "加载类图失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.CLASS_DIAGRAM,
                                requestState = AsyncRequestState.failed(
                                    message = message,
                                    requestId = requestId,
                                    scene = request.view.name,
                                    startedAtEpochMillis = runningState.startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                    result.payload?.view != null -> {
                        val view = result.payload.view
                        val complete = view.summary.relationCompleteness == "COMPLETE"
                        if (complete) {
                            val message = workflowPlan.initialCompleteSuccessMessage(view)
                            eventSink.emit(
                                GraphEditorApplicationEvent.ClassDiagramLoaded(
                                    view = view,
                                    requestState = AsyncRequestState.succeeded(
                                        requestId = requestId,
                                        scene = request.view.name,
                                        statusMessage = message,
                                        startedAtEpochMillis = runningState.startedAtEpochMillis,
                                    ),
                                    statusMessage = message,
                                ),
                            )
                        } else {
                            if (view.visibleGraph.nodes.isNotEmpty() || view.fullGraph.nodes.isNotEmpty()) {
                                val message = workflowPlan.partialSuccessMessage(view)
                                eventSink.emit(
                                    GraphEditorApplicationEvent.ClassDiagramLoaded(
                                        view = view,
                                        requestState = AsyncRequestState.succeeded(
                                            requestId = requestId,
                                            scene = request.view.name,
                                            statusMessage = message,
                                            startedAtEpochMillis = runningState.startedAtEpochMillis,
                                        ),
                                        statusMessage = message,
                                    ),
                                )
                            }
                            when {
                                workflowPlan.shouldRequestCompleteRelations() -> {
                                    requestCompleteClassDiagram(
                                        result.payload.resolvedScopeNodeId,
                                        result.payload.symbolIndexHint,
                                        result.payload.request,
                                        requestId,
                                        runningState.startedAtEpochMillis,
                                    )
                                }
                                workflowPlan.shouldRequestScopedBodyRelations() -> {
                                    requestScopedClassDiagram(
                                        scopeNodeId = result.payload.resolvedScopeNodeId,
                                        symbolIndexHint = result.payload.symbolIndexHint,
                                        sourceClassIds = view.visibleClassNodeIds(),
                                        request = result.payload.request,
                                        requestId = requestId,
                                        startedAtEpochMillis = runningState.startedAtEpochMillis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun requestScopedClassDiagram(
        scopeNodeId: String?,
        symbolIndexHint: JvmSymbolIndex?,
        sourceClassIds: Set<String>,
        request: IndexedGraphRequest,
        requestId: Long,
        startedAtEpochMillis: Long?,
    ) {
        if (sourceClassIds.isEmpty()) {
            return
        }
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                runCatching {
                    val cacheState = request.cacheState(indexSupport.hasFullIndex(request))
                    val indexStartedAt = System.nanoTime()
                    val index = indexSupport.buildScopedClassDiagramIndex(
                        request = request,
                        symbolIndexHint = symbolIndexHint,
                        sourceClassIds = sourceClassIds,
                    )
                    traceStage("classDiagram.scopedBuildIndex", indexStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "scopeNodeId=${scopeNodeId.orEmpty()}",
                            "relationCompleteness=${IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS.name}",
                            "sourceClasses=${sourceClassIds.size}",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "fields=${index.symbolIndex.fieldsByQualifiedName.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(
                        index = index,
                        scopeNodeId = scopeNodeId,
                        relationCompleteness = IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS.name,
                        request = request,
                        cacheState = cacheState,
                        freshness = indexSupport.freshness(),
                    ).let { view ->
                        applyUsageOverlay(
                            index = index,
                            request = request,
                            view = view,
                            scopeNodeId = scopeNodeId,
                        )
                    }.also { view ->
                        traceStage("classDiagram.scopedProject", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "scopeNodeId=${scopeNodeId.orEmpty()}",
                                "relationCompleteness=${IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS.name}",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                            )
                        }
                    }.let { view ->
                        ClassDiagramViewPayload(view = view, request = request)
                    }
                }.fold(
                    onSuccess = { payload -> ClassDiagramViewResult.success(payload) },
                    onFailure = { error -> ClassDiagramViewResult.failure(error) },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        if (isBenignCompleteClassDiagramCancellation(result.failure)) {
                            return@finishOnUiThread
                        }
                        logger.warn("补齐当前范围类图调用失败", result.failure)
                        val message = "补齐当前范围调用失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.CLASS_DIAGRAM,
                                requestState = AsyncRequestState.failed(
                                    message = message,
                                    requestId = requestId,
                                    scene = request.view.name,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                    result.payload?.view != null -> {
                        val message = ClassDiagramWorkflowPlan(request).scopedRelationSuccessMessage(result.payload.view)
                        eventSink.emit(
                            GraphEditorApplicationEvent.ClassDiagramLoaded(
                                view = result.payload.view,
                                requestState = AsyncRequestState.succeeded(
                                    requestId = requestId,
                                    scene = request.view.name,
                                    statusMessage = message,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun requestCompleteClassDiagram(
        scopeNodeId: String?,
        symbolIndexHint: JvmSymbolIndex?,
        request: IndexedGraphRequest,
        requestId: Long,
        startedAtEpochMillis: Long?,
    ) {
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                runCatching {
                    val hadCachedFullIndex = indexSupport.hasFullIndex(request)
                    val cacheState = request.cacheState(hadCachedFullIndex)
                    val indexStartedAt = System.nanoTime()
                    val index = indexSupport.buildIndex(
                        request = request,
                        symbolIndexHint = symbolIndexHint,
                    )
                    traceStage("classDiagram.completeBuildIndex", indexStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "scopeNodeId=${scopeNodeId.orEmpty()}",
                            "relationCompleteness=COMPLETE",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "fields=${index.symbolIndex.fieldsByQualifiedName.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(
                        index = index,
                        scopeNodeId = scopeNodeId,
                        relationCompleteness = "COMPLETE",
                        request = request,
                        cacheState = cacheState,
                        freshness = indexSupport.freshness(),
                    ).let { view ->
                        applyUsageOverlay(
                            index = index,
                            request = request,
                            view = view,
                            scopeNodeId = scopeNodeId,
                        )
                    }.also { view ->
                        traceStage("classDiagram.completeProject", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "scopeNodeId=${scopeNodeId.orEmpty()}",
                                "relationCompleteness=COMPLETE",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                            )
                        }
                    }.let { view ->
                        ClassDiagramViewPayload(view = view, request = request)
                    }
                }.fold(
                    onSuccess = { payload -> ClassDiagramViewResult.success(payload) },
                    onFailure = { error -> ClassDiagramViewResult.failure(error) },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        if (isBenignCompleteClassDiagramCancellation(result.failure)) {
                            return@finishOnUiThread
                        }
                        logger.warn("补齐类图完整关系失败", result.failure)
                        val message = "补齐类图完整关系失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.CLASS_DIAGRAM,
                                requestState = AsyncRequestState.failed(
                                    message = message,
                                    requestId = requestId,
                                    scene = request.view.name,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                    result.payload?.view != null -> {
                        val message = ClassDiagramWorkflowPlan(request).completeRelationSuccessMessage(result.payload.view)
                        eventSink.emit(
                            GraphEditorApplicationEvent.ClassDiagramLoaded(
                                view = result.payload.view,
                                requestState = AsyncRequestState.succeeded(
                                    requestId = requestId,
                                    scene = request.view.name,
                                    statusMessage = message,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyUsageOverlay(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
        view: ClassDiagramResult,
        scopeNodeId: String?,
    ): ClassDiagramResult {
        if (!request.usage.enabled) {
            return view
        }
        val target = resolveUsageTarget(
            index = index,
            request = request,
            view = view,
            scopeNodeId = scopeNodeId,
        ) ?: run {
            logger.warn("无法解析类使用处目标：targetNodeId=${request.usage.targetNodeId}, scopeNodeId=${scopeNodeId.orEmpty()}")
            return view
        }
        val usageStartedAt = System.nanoTime()
        val result = usageSearchService.search(
            target = ClassUsageSearchTargetHint(
                qualifiedName = target.qualifiedName,
                nodeId = target.nodeId,
                sourceVirtualFileUrl = target.sourceVirtualFileUrl,
                sourcePath = target.sourcePath,
            ),
            options = ClassUsageSearchOptions(
                maxUsageGroups = request.usage.maxUsageGroups,
                maxUsageEntries = request.usage.maxUsageEntries,
                includeImports = request.usage.includeImports,
            ),
        ) ?: run {
            logger.warn("无法在 PSI 中找到类使用处目标：${target.qualifiedName}")
            return view
        }
        val projected = usageProjector.project(view, result)
        traceStage("classDiagram.usage", usageStartedAt) {
            listOf(
                "targetNodeId=${result.target.nodeId}",
                "targetQualifiedName=${result.target.qualifiedName}",
                "groups=${result.summary.visibleGroupCount}/${result.summary.groupCount}",
                "entries=${result.summary.visibleUsageCount}/${result.summary.usageCount}",
                "truncated=${result.summary.truncated}",
            )
        }
        return projected
    }

    private fun buildStandaloneUsageView(request: IndexedGraphRequest): ClassDiagramResult? {
        if (!request.usage.enabled) {
            return null
        }
        val targetHint = request.standaloneUsageTargetHint() ?: return null
        val usageStartedAt = System.nanoTime()
        val result = usageSearchService.search(
            target = targetHint,
            options = ClassUsageSearchOptions(
                maxUsageGroups = request.usage.maxUsageGroups,
                maxUsageEntries = request.usage.maxUsageEntries,
                includeImports = request.usage.includeImports,
            ),
        ) ?: return null
        traceStage("classDiagram.usage", usageStartedAt) {
            listOf(
                "targetNodeId=${result.target.nodeId}",
                "targetQualifiedName=${result.target.qualifiedName}",
                "groups=${result.summary.visibleGroupCount}/${result.summary.groupCount}",
                "entries=${result.summary.visibleUsageCount}/${result.summary.usageCount}",
                "truncated=${result.summary.truncated}",
                "mode=standalone",
            )
        }
        return usageProjector.projectStandalone(result)
    }

    private fun IndexedGraphRequest.standaloneUsageTargetHint(): ClassUsageSearchTargetHint? {
        val qualifiedName = usage.targetQualifiedName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: (anchor as? com.charmnight.linkgraph.application.indexed.IndexedGraphAnchor.ClassName)
                ?.qualifiedName
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val nodeId = usage.targetNodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: qualifiedName?.let { com.charmnight.linkgraph.jvm.index.stableJvmId("class", it) }
            ?: return null
        return ClassUsageSearchTargetHint(
            qualifiedName = qualifiedName,
            nodeId = nodeId,
            sourceVirtualFileUrl = usage.sourceVirtualFileUrl,
            sourcePath = usage.sourcePath,
        )
    }

    private fun resolveUsageTarget(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
        view: ClassDiagramResult,
        scopeNodeId: String?,
    ): ResolvedClassUsageTarget? {
        val candidates = listOfNotNull(
            request.usage.targetNodeId,
            scopeNodeId,
            view.summary.anchorTypeNodeId,
            view.anchorNodeId,
            request.classDiagramScopeNodeId(),
        ).map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        candidates.forEach { nodeId ->
            resolveUsageTarget(index, nodeId)?.let { return it }
        }
        view.summary.anchorTypeQualifiedName
            ?.takeIf(String::isNotBlank)
            ?.let { qualifiedName -> index.findClass(qualifiedName) }
            ?.let { symbol -> return symbol.toResolvedClassUsageTarget() }
        return null
    }

    private fun resolveUsageTarget(
        index: ArchitectureGraphIndex,
        nodeId: String,
    ): ResolvedClassUsageTarget? {
        (index.findSymbol(nodeId) as? JvmClassSymbol)
            ?.let { symbol -> return symbol.toResolvedClassUsageTarget() }
        val architectureNode = index.node(nodeId) ?: return null
        architectureNode.memberClassIds
            .takeIf { memberIds -> memberIds.size == 1 }
            ?.single()
            ?.let { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
            ?.let { symbol -> return symbol.toResolvedClassUsageTarget() }
        return index.findClass(architectureNode.qualifiedName)
            ?.let { symbol -> symbol.toResolvedClassUsageTarget() }
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }

    private fun isBenignCompleteClassDiagramCancellation(throwable: Throwable): Boolean {
        if (project.isDisposed) {
            return true
        }
        if (throwable is ProcessCanceledException || throwable is AlreadyDisposedException || throwable is CancellationException) {
            return true
        }
        if (throwable.isVfsDisposedFailure()) {
            return true
        }
        return throwable.cause?.let(::isBenignCompleteClassDiagramCancellation) == true
    }

    private fun Throwable.isVfsDisposedFailure(): Boolean {
        if (this !is NullPointerException) {
            return false
        }
        val message = message.orEmpty()
        return "vfsPeer" in message &&
            stackTrace.any { frame ->
                frame.className == "com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl"
            }
    }

    private fun ClassDiagramResult.visibleClassNodeIds(): Set<String> =
        visibleGraph.nodes
            .asSequence()
            .filter { node -> node.type in classDiagramBodyRelationNodeTypes }
            .map { node -> node.id }
            .toSet()

    private companion object {
        private val classDiagramBodyRelationNodeTypes = setOf(
            NodeType.CLASS,
            NodeType.INTERFACE,
            NodeType.ENUM,
            NodeType.ANNOTATION,
            NodeType.RECORD,
            NodeType.OBJECT,
        )
    }
}

private data class ClassDiagramViewPayload(
    val view: ClassDiagramResult? = null,
    val symbolIndexHint: JvmSymbolIndex? = null,
    val resolvedScopeNodeId: String? = null,
    val request: IndexedGraphRequest,
)

private data class ClassDiagramViewResult(
    val payload: ClassDiagramViewPayload? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(payload: ClassDiagramViewPayload): ClassDiagramViewResult = ClassDiagramViewResult(payload = payload)
        fun failure(error: Throwable): ClassDiagramViewResult = ClassDiagramViewResult(failure = error)
        fun cancelled(): ClassDiagramViewResult = ClassDiagramViewResult(cancelled = true)
    }
}

private data class ResolvedClassUsageTarget(
    val nodeId: String,
    val qualifiedName: String,
    val sourceVirtualFileUrl: String?,
    val sourcePath: String?,
)

private fun JvmClassSymbol.toResolvedClassUsageTarget(): ResolvedClassUsageTarget =
    ResolvedClassUsageTarget(
        nodeId = id,
        qualifiedName = qualifiedName,
        sourceVirtualFileUrl = source?.virtualFileUrl,
        sourcePath = source?.displayPath,
    )

private data class ClassDiagramWorkflowPlan(
    val request: IndexedGraphRequest,
) {
    private val isUsageRequest: Boolean = request.usage.enabled

    fun shouldRequestCompleteRelations(): Boolean =
        !isUsageRequest && request.relationDetail == IndexedGraphRelationDetail.COMPLETE

    fun shouldRequestScopedBodyRelations(): Boolean =
        !isUsageRequest && request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS

    fun startMessage(scopeNodeId: String?): String =
        when {
            isUsageRequest -> "正在查找类使用处。"
            request.relationDetail == IndexedGraphRelationDetail.COMPLETE -> "正在构建项目类图并准备补齐完整关系。"
            request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS -> "正在构建类图并补齐当前范围调用。"
            scopeNodeId.isNullOrBlank() -> "正在构建项目类图。"
            else -> "正在从架构节点下钻类图。"
        }

    fun partialSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            request.relationDetail == IndexedGraphRelationDetail.COMPLETE -> "已加载类图结构，正在补齐完整关系。"
            request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS -> "已加载类图结构，正在补齐当前范围调用。"
            else -> "已加载类图结构。"
        }

    fun initialCompleteSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已加载类图。"
        }

    fun completeRelationSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已补齐类图完整关系。"
        }

    fun scopedRelationSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已补齐当前范围调用。"
        }
}
