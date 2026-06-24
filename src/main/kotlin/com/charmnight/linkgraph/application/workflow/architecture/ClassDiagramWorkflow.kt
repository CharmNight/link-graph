package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.projection.business.ClassDiagramProjector
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphAnchor
import com.charmnight.linkgraph.application.indexed.IndexedGraphRelationDetail
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.cacheState
import com.charmnight.linkgraph.application.indexed.classDiagramScopeNodeId
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.projection.business.ClassUsageGraphProjector
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

/**
 * 类图工作流：根据前端请求协调类图索引构建、范围解析、关系补齐与使用处叠加，
 * 通过事件总线把请求态、结果和失败反馈给前端。
 *
 * 主要流程：
 * 1. 接收到 [IndexedGraphRequest] 后解析范围节点，发起首屏类图（结构/全量索引二选一）；
 * 2. 视情况再触发"当前范围调用补齐"或"完整关系补齐"两次异步任务；
 * 3. 当请求聚焦"类使用处"时，会跳过类图本身，直接走独立使用处检索；
 * 4. 关键阶段通过 [runtimeTrace] 输出渲染追踪，便于排查性能与可见性问题。
 */
internal class ClassDiagramWorkflow(
    /** 当前 IntelliJ 项目。 */
    private val project: Project,
    /** 类图索引、缓存与新鲜度查询能力。 */
    private val indexSupport: ClassDiagramIndexSupport,
    /** 应用层事件总线，用于把请求/结果事件转发到前端。 */
    private val eventSink: GraphEditorApplicationEventSink,
    /** 编辑器快照查询入口，可空（仅用作范围解析器回退）。 */
    snapshotProvider: EditorSnapshotProvider? = null,
    /** 类图投影器：把符号索引转换为前端可消费的可见图与全量图。 */
    private val projector: ClassDiagramProjector = ClassDiagramProjector(),
    /** 类使用处检索服务（PSI 层）。 */
    private val usageSearchService: ClassUsageSearchService = ClassUsageSearchService(project),
    /** 类使用处投影器：把检索结果组装成与类图同构的视图。 */
    private val usageProjector: ClassUsageGraphProjector = ClassUsageGraphProjector(),
    /** 类图范围解析器：把请求中的 scopeNodeId 还原为实际可用的锚点节点。 */
    private val scopeResolver: ClassDiagramScopeResolver = ClassDiagramScopeResolver(project, snapshotProvider),
    /** 日志记录器。 */
    private val logger: com.intellij.openapi.diagnostic.Logger,
    /** 渲染追踪回调，仅在开启追踪时输出阶段信息。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    /** 自增的请求 ID 序列，用于把多个异步回调绑定到同一个前端请求。 */
    private val requestIds = AtomicLong()

    /**
     * 入口方法：发起一次类图请求，先发出 started 事件，再以非阻塞读动作执行构建，
     * 最终根据结果分支发出 Loaded / Failed 事件，或继续触发补齐请求。
     */
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
                            request = request,
                            view = view,
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

    /**
     * 第二阶段：以"当前可见类"为种子补齐方法体内部的调用关系，
     * 用于在首屏结构图渲染完成后把当前范围内更深的关系叠加进来。
     */
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
                            request = request,
                            view = view,
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

    /**
     * 第二阶段：构建全量索引并投影完整关系，把首屏"仅结构"的视图升级为完整类图。
     * 适用于首屏结构图渲染成功且请求明确要求完整关系的场景。
     */
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
                            request = request,
                            view = view,
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

    /**
     * 在常规类图构建完成后，按请求把"切换到类使用处视图"叠加在结果上：
     * 请求要求 usage.enabled 时，会查询 PSI 使用处并把整个结果替换成使用处视图。
     */
    private fun applyUsageOverlay(
        request: IndexedGraphRequest,
        view: ClassDiagramResult,
    ): ClassDiagramResult {
        if (!request.usage.enabled) {
            return view
        }
        val targetHint = request.explicitUsageTargetHint() ?: run {
            logger.warn("无法解析类使用处目标：targetNodeId=${request.usage.targetNodeId}")
            return view
        }
        val usageStartedAt = System.nanoTime()
        val result = usageSearchService.search(
            target = targetHint,
            options = ClassUsageSearchOptions(
                maxUsageGroups = request.usage.maxUsageGroups,
                maxUsageEntries = request.usage.maxUsageEntries,
                includeImports = request.usage.includeImports,
            ),
        ) ?: run {
            logger.warn(
                "无法在 PSI 中找到类使用处目标：" +
                    "targetNodeId=${targetHint.nodeId.orEmpty()}, " +
                    "targetQualifiedName=${targetHint.qualifiedName.orEmpty()}",
            )
            return view
        }
        val projected = usageProjector.projectStandalone(result)
        traceStage("classDiagram.usage", usageStartedAt) {
            listOf(
                "targetNodeId=${result.target.nodeId}",
                "targetQualifiedName=${result.target.qualifiedName}",
                "groups=${result.summary.visibleGroupCount}/${result.summary.groupCount}",
                "entries=${result.summary.visibleUsageCount}/${result.summary.usageCount}",
                "truncated=${result.summary.truncated}",
                "mode=switch",
            )
        }
        return projected
    }

    /**
     * 独立使用处视图：当请求只关心一个类的使用处（锚点与目标一致、scope 为 ClassNeighborhood）时，
     * 跳过整张类图，直接用使用处检索结果构造一份与类图同构的视图。
     *
     * 与 applyUsageOverlay 一致：search 调用包在 runCatching 里，
     * ProcessCanceledException / 内部异常冒泡时记日志并返回 null，避免卡死工作台。
     */
    private fun buildStandaloneUsageView(request: IndexedGraphRequest): ClassDiagramResult? {
        if (!request.isStandaloneUsageRequest()) {
            return null
        }
        val targetHint = request.standaloneUsageTargetHint() ?: return null
        val usageStartedAt = System.nanoTime()
        val result = runCatching {
            usageSearchService.search(
                target = targetHint,
                options = ClassUsageSearchOptions(
                    maxUsageGroups = request.usage.maxUsageGroups,
                    maxUsageEntries = request.usage.maxUsageEntries,
                    includeImports = request.usage.includeImports,
                ),
            )
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.warn("类图独立使用处检索失败：targetNodeId=${targetHint.nodeId.orEmpty()}", error)
                null
            },
        ) ?: return null
        result ?: return null
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

    /**
     * 解析"独立使用处请求"中的目标提示，依次从 usage 显式字段、anchor 限定名兜底，
     * 并补充必要的 nodeId（缺失时按 JVM 稳定 ID 规则生成）。
     */
    private fun IndexedGraphRequest.standaloneUsageTargetHint(): ClassUsageSearchTargetHint? {
        explicitUsageTargetHint()?.let { return it }
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

    /**
     * 解析常规类图请求中附带的 usage 目标提示（仅在 usage.enabled 时有意义），
     * 不做 anchor 兜底，所有字段都来自 usage 配置。
     */
    private fun IndexedGraphRequest.explicitUsageTargetHint(): ClassUsageSearchTargetHint? {
        if (!usage.enabled) {
            return null
        }
        val qualifiedName = usage.targetQualifiedName
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val nodeId = usage.targetNodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: qualifiedName
                ?.let { com.charmnight.linkgraph.jvm.index.stableJvmId("class", it) }
            ?: return null
        return ClassUsageSearchTargetHint(
            qualifiedName = qualifiedName,
            nodeId = nodeId,
            sourceVirtualFileUrl = usage.sourceVirtualFileUrl,
            sourcePath = usage.sourcePath,
        )
    }

    /**
     * 判断请求是否为"独立使用处"：必须开启 usage，且锚点指向的类与 usage 目标一致，
     * 同时 scope 被收敛到 ClassNeighborhood，表示用户只想看这一个类的使用处而非整张类图。
     */
    private fun IndexedGraphRequest.isStandaloneUsageRequest(): Boolean {
        if (!usage.enabled) {
            return false
        }
        val anchorNodeId = (anchor as? IndexedGraphAnchor.ClassId)
            ?.nodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        val targetNodeId = usage.targetNodeId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        return anchorNodeId == targetNodeId && scope is IndexedGraphScope.ClassNeighborhood
    }

    /**
     * 输出一个渲染追踪阶段，包含阶段名、耗时与详情键值对；未配置 [runtimeTrace] 时直接跳过。
     */
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

    /**
     * 判断补齐全量类图时抛出的异常是否为可忽略的"良性取消"：
     * 项目已释放、IntelliJ 取消异常、VFS 已释放空指针等都不应作为构建失败上报。
     */
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

    /**
     * 识别 VFS 已释放导致的 NPE：堆栈中包含 PersistentFSImpl 且消息提及 vfsPeer，
     * 视作项目正在关闭期间的良性失败。
     */
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

    /**
     * 提取当前可见图里所有类/接口/枚举/注解/Record/Object 节点的 ID，
     * 作为 scoped 补齐阶段的种子类集合。
     */
    private fun ClassDiagramResult.visibleClassNodeIds(): Set<String> =
        visibleGraph.nodes
            .asSequence()
            .filter { node -> node.type in classDiagramBodyRelationNodeTypes }
            .map { node -> node.id }
            .toSet()

    private companion object {
        /** 类图方法体关系补齐时关注的"类级"节点类型集合。 */
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

/**
 * 单次类图构建任务的输出载荷，承载投影后的视图、供后续阶段复用的符号索引提示、
 * 实际生效的范围节点 ID，以及原始请求。
 */
private data class ClassDiagramViewPayload(
    val view: ClassDiagramResult? = null,
    val symbolIndexHint: JvmSymbolIndex? = null,
    val resolvedScopeNodeId: String? = null,
    val request: IndexedGraphRequest,
)

/**
 * 类图构建任务的统一结果包装：可能是成功载荷、失败异常，或者被取消（视为正常退出）。
 */
private data class ClassDiagramViewResult(
    val payload: ClassDiagramViewPayload? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        /** 成功工厂，仅携带载荷。 */
        fun success(payload: ClassDiagramViewPayload): ClassDiagramViewResult = ClassDiagramViewResult(payload = payload)
        /** 失败工厂，仅携带异常。 */
        fun failure(error: Throwable): ClassDiagramViewResult = ClassDiagramViewResult(failure = error)
        /** 取消工厂，三个字段都不设置。 */
        fun cancelled(): ClassDiagramViewResult = ClassDiagramViewResult(cancelled = true)
    }
}

/**
 * 类图工作流计划：根据原始请求推断这次应当走"独立使用处 / 首屏结构 / scoped 补齐 / 完整补齐"哪条路径，
 * 并集中维护各阶段对用户可见的中文提示文案。
 */
private data class ClassDiagramWorkflowPlan(
    val request: IndexedGraphRequest,
) {
    /** 该请求是否只关心类使用处（与类图本身互斥）。 */
    private val isUsageRequest: Boolean = request.usage.enabled

    /** 是否需要在首屏结构图之后追加一次"完整关系"补齐。 */
    fun shouldRequestCompleteRelations(): Boolean =
        !isUsageRequest && request.relationDetail == IndexedGraphRelationDetail.COMPLETE

    /** 是否需要在首屏结构图之后追加一次"当前范围调用"补齐。 */
    fun shouldRequestScopedBodyRelations(): Boolean =
        !isUsageRequest && request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS

    /** 请求开始时给用户的提示文案，根据 usage / 关系细节 / 是否有 scopeNode 区分。 */
    fun startMessage(scopeNodeId: String?): String =
        when {
            isUsageRequest -> "正在查找类使用处。"
            request.relationDetail == IndexedGraphRelationDetail.COMPLETE -> "正在构建项目类图并准备补齐完整关系。"
            request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS -> "正在构建类图并补齐当前范围调用。"
            scopeNodeId.isNullOrBlank() -> "正在构建项目类图。"
            else -> "正在从架构节点下钻类图。"
        }

    /** 首屏结构图加载成功但关系尚未补齐时的过渡文案。 */
    fun partialSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            request.relationDetail == IndexedGraphRelationDetail.COMPLETE -> "已加载类图结构，正在补齐完整关系。"
            request.relationDetail == IndexedGraphRelationDetail.SCOPED_BODY_RELATIONS -> "已加载类图结构，正在补齐当前范围调用。"
            else -> "已加载类图结构。"
        }

    /** 首屏即得到完整关系（无需补齐）时的成功文案。 */
    fun initialCompleteSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已加载类图。"
        }

    /** 完整关系补齐完成后的成功文案。 */
    fun completeRelationSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已补齐类图完整关系。"
        }

    /** 当前范围调用补齐完成后的成功文案。 */
    fun scopedRelationSuccessMessage(view: ClassDiagramResult): String =
        when {
            isUsageRequest && view.usage != null -> "已加载类使用处。"
            isUsageRequest -> "未找到类使用处目标。"
            else -> "已补齐当前范围调用。"
        }
}
