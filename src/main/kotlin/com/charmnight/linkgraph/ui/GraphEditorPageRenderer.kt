package com.charmnight.linkgraph.ui

import com.intellij.ui.JBColor
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument

/**
 * 把项目状态序列化成前端可直接消费的 bootstrap JSON，并注入到入口 HTML。
 */
class GraphEditorPageRenderer {
    // P2-1 真正的架构分解：bootstrap payload 组装委托给独立的 BootstrapPayloadAssembler
    private val payloadAssembler = BootstrapPayloadAssembler(this)
    companion object {
        /** 允许完整内联的次级图层最大节点数。 */
        private const val MAX_SECONDARY_LAYER_SERIALIZED_NODES = 96
        /** 允许完整内联的次级图层最大边数。 */
        private const val MAX_SECONDARY_LAYER_SERIALIZED_EDGES = 144
        /** 需要从语义元数据中过滤掉的 UI 前缀。 */
        private const val UI_PREFIX = "ui."
        /** 需要从语义元数据中过滤掉的布局前缀。 */
        private const val LAYOUT_PREFIX = "layout."

        /** 通过 [JBColor] 判断当前 IDE 是否处于暗色主题。 */
        private fun isIdeaDarkTheme(): Boolean = !JBColor.isBright()
    }

    /** 为指定会话生成 bootstrap 脚本和自定义事件。 */
    fun bootstrapScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        debugTracingEnabled: Boolean = false,
    ): String {
        return bootstrapScript(
            sessionId = sessionId,
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
            debugTracingEnabled = debugTracingEnabled,
        )
    }

    /** 为指定会话生成 bootstrap 脚本和自定义事件。 */
    fun bootstrapScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        debugTracingEnabled: Boolean = false,
    ): String {
        /** 当前快照序列化后的状态 JSON。 */
        val stateJson = bootstrapJson(snapshot, artifactRefs)
        /** 包含会话信息的外层事件载荷。 */
        val envelopeJson = encodeSnapshotEnvelopeJson(
            sessionId = sessionId,
            snapshot = snapshot,
            stateJson = stateJson,
        )
        val debugPrefix = if (debugTracingEnabled) {
            """
            window.__linkGraphDebugEnabled = true;
            window.__linkGraphTraceBuffer = Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer : [];
            console.warn("link-graph bootstrap start: revision=${snapshot.snapshotRevision}");
            """.trimIndent()
        } else {
            ""
        }
        val debugSuffix = if (debugTracingEnabled) {
            """console.warn("link-graph bootstrap dispatched: revision=${snapshot.snapshotRevision}");"""
        } else {
            ""
        }
        return """
            $debugPrefix
            window.linkGraphBootstrap = $stateJson;
            window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));
            $debugSuffix
        """.trimIndent()
    }

    /** 把 bootstrap 脚本注入入口 HTML 的 `<head>` 中。 */
    fun render(
        entryHtml: String,
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        debugTracingEnabled: Boolean = false,
    ): String {
        return render(
            entryHtml = entryHtml,
            sessionId = sessionId,
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
            debugTracingEnabled = debugTracingEnabled,
        )
    }

    /** 把 bootstrap 脚本注入入口 HTML 的 `<head>` 中。 */
    fun render(
        entryHtml: String,
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        debugTracingEnabled: Boolean = false,
        darkTheme: Boolean = isIdeaDarkTheme(),
    ): String {
        /** JCEF 内联 HTML 不能可靠读取宿主 CSS，先用 IDE Laf 注入标准 color-scheme。 */
        val themeScript = """
            <script>
              document.documentElement.dataset.ideaTheme = "${if (darkTheme) "dark" else "light"}";
              document.documentElement.style.colorScheme = "${if (darkTheme) "dark" else "light"}";
            </script>
        """.trimIndent()
        /** 注入页面的脚本标签内容。 */
        val bootstrapScript = """
            <script>
              ${bootstrapScript(
                  sessionId = sessionId,
                  snapshot = snapshot,
                  artifactRefs = artifactRefs,
                  debugTracingEnabled = debugTracingEnabled,
              )}
            </script>
        """.trimIndent()
        return if (entryHtml.contains("</head>", ignoreCase = true)) {
            entryHtml.replace("</head>", "$themeScript\n$bootstrapScript\n</head>", ignoreCase = true)
        } else {
            "$themeScript\n$bootstrapScript\n$entryHtml"
        }
    }

    /** 直接返回前端所需的 bootstrap JSON。 */
    fun bootstrapJson(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): String {
        return bootstrapJson(
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 直接返回前端所需的 bootstrap JSON。 */
    fun bootstrapJson(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): String {
        return encodeBootstrapJson(snapshot, artifactRefs)
    }

    /** 生成携带会话信息的外层 envelope JSON。 */
    private fun encodeSnapshotEnvelopeJson(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        stateJson: String,
    ): String {
        /** 发往前端事件的外层载荷。 */
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "revision" to snapshot.snapshotRevision,
            "state" to JsonCodec.parseValue(stateJson),
        )
        return JsonCodec.toScriptSafeJson(payload)
    }

    /** 将完整编辑器快照编码成前端 bootstrap JSON。 */
    private fun encodeBootstrapJson(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
    ): String {
        return JsonCodec.toScriptSafeJson(bootstrapPayload(snapshot, artifactRefs))
    }

    /** 构建完整 bootstrap 状态载荷（DTO），供 init 与增量 slice 复用。 */
    internal fun bootstrapPayload(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): BootstrapPayloadDto {
        return bootstrapPayload(
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 构建完整 bootstrap 状态载荷（DTO），委托给 [payloadAssembler]。 */
    internal fun bootstrapPayload(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): BootstrapPayloadDto = payloadAssembler.assemble(snapshot, artifactRefs)

    /** 把多个场景的运行时状态映射为前端使用的字典结构。 */
    /** sceneStatesToMap / graphSceneStateToMap 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

    /** 把异步请求状态转换成前端 DTO：详见 top-level fun asyncRequestStateToDto。 */
    internal fun requestStateToDto(
        state: com.charmnight.linkgraph.ui.AsyncRequestState,
        hasPromptPreview: Boolean = state.promptPreviewAvailable,
    ): AsyncRequestStateDto = com.charmnight.linkgraph.ui.asyncRequestStateToDto(state, hasPromptPreview)

    /** 把 QA 请求恢复状态（最近成功/失败的请求）转换为前端 DTO。 */
    internal fun qaRequestRecoveryStateToDto(
        state: com.charmnight.linkgraph.workbench.QaRequestRecoveryState,
    ): QaRequestRecoveryStateDto = com.charmnight.linkgraph.ui.qaRequestRecoveryStateToDto(state)

    /** 把可重放的 QA 请求转换为前端 DTO：详见 top-level fun replayableQaRequestToDto。 */
    private fun replayableQaRequestToDto(
        request: com.charmnight.linkgraph.workbench.ReplayableQaRequest,
    ): ReplayableQaRequestDto = com.charmnight.linkgraph.ui.replayableQaRequestToDto(request)

    /** 把助手结果存储（按结果 ID 索引的多种轮次结果）展开为前端可消费的嵌套结构。 */
    internal fun assistantResultStoreToDto(
        store: com.charmnight.linkgraph.workbench.AssistantResultStore,
        artifactRefs: Map<String, GraphEditorArtifactRegistry.AssistantResultArtifacts>,
    ): Map<String, AssistantResultEntryDto> = store.results.mapValues { (resultId, entry) ->
        val resultArtifacts = artifactRefs[resultId] ?: GraphEditorArtifactRegistry.AssistantResultArtifacts()
        AssistantResultEntryDto(
            kind = entry.kind.name,
            failure = entry.failure?.let(::assistantFailureResultToDto),
            explanation = if (entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.EXPLANATION) {
                entry.explanation?.let { result ->
                    beautificationResultToDto(
                        result,
                        promptPreviewArtifactId = resultArtifacts.explanationPromptPreviewArtifactId,
                    )
                }
            } else null,
            qa = if (entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.QA) {
                entry.qa?.let { result ->
                    patchResultToDto(
                        result,
                        promptPreviewArtifactId = resultArtifacts.qaPromptPreviewArtifactId,
                    )
                }
            } else null,
            generationPlan = if (
                entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.GENERATION_PLAN ||
                entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT
            ) {
                entry.generationPlan?.let { plan ->
                    generationPlanToDto(
                        plan,
                        promptPreviewArtifactId = resultArtifacts.generationPlanPromptPreviewArtifactId,
                    )
                }
            } else null,
            generationDiscussionSession = if (
                entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.GENERATION_PLAN ||
                entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT
            ) {
                entry.generationDiscussionSession?.let { session ->
                    generationPlanDiscussionSessionToDto(
                        session,
                        promptPreviewArtifactId = resultArtifacts.generationDiscussionPromptPreviewArtifactId,
                    )
                }
            } else null,
            codeDraftWarnings = if (entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT) {
                entry.codeDraftWarnings
            } else null,
            codeDrafts = if (entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT) {
                entry.codeDrafts.map { draft ->
                    generatedCodeDraftToDto(
                        draft,
                        contentArtifactId = resultArtifacts.codeDraftContentArtifactIds[draft.id],
                    )
                }
            } else null,
            check = if (entry.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CHECK_RESULT) {
                entry.check?.let { result ->
                    patchResultToDto(
                        result,
                        promptPreviewArtifactId = resultArtifacts.checkPromptPreviewArtifactId,
                    )
                }
            } else null,
        )
    }

    /** 把助手调用失败结果转换为前端字段，携带错误消息、阶段与时间戳。 */
    /** assistantFailureResultToMap / generationPlanToMap 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

    /** 把生成的代码草稿转换为前端 DTO：详见 top-level fun generatedCodeDraftToDto。 */
    internal fun generatedCodeDraftToDto(
        draft: com.charmnight.linkgraph.codegen.GeneratedCodeDraft,
        contentArtifactId: String?,
    ): GeneratedCodeDraftDto = com.charmnight.linkgraph.ui.generatedCodeDraftToDto(draft, contentArtifactId)

    /** 把阶段准入判定转换为前端 DTO。 */
    internal fun stageEligibilityDecisionToDto(
        decision: com.charmnight.linkgraph.workbench.StageEligibilityDecision,
    ): StageEligibilityDecisionDto = com.charmnight.linkgraph.ui.stageEligibilityDecisionToDto(decision)

    /** 把源码跳转状态转换为前端 DTO：详见 top-level fun sourceNavigationStateToDto。 */
    internal fun sourceNavigationStateToDto(
        state: com.charmnight.linkgraph.ui.SourceNavigationState,
    ): SourceNavigationStateDto = com.charmnight.linkgraph.ui.sourceNavigationStateToDto(state)

    /** 为 diff 项解析可读标题。 */
    internal fun resolveDiffTitle(
        entry: com.charmnight.linkgraph.model.GraphDiffEntry,
        document: GraphDocument,
    ): String = com.charmnight.linkgraph.ui.resolveDiffTitle(entry, document)

    /** 把节点转换为前端 DTO：详见 top-level fun nodeToDto。 */
    private fun nodeToDto(
        node: GraphNode,
        layoutState: GraphLayoutState? = null,
    ): GraphNodeDto = com.charmnight.linkgraph.ui.nodeToDto(node, layoutState)

    /** 把边转换为前端 DTO。 */
    private fun edgeToDto(edge: GraphEdge): GraphEdgeDto =
        com.charmnight.linkgraph.ui.edgeToDto(edge)

    /** 把图文档转换为前端 DTO：详见 top-level fun graphDocumentToDto。 */
    internal fun documentToDto(
        document: GraphDocument,
        includeFullContent: Boolean,
        layoutState: GraphLayoutState? = null,
    ): GraphDocumentDto = com.charmnight.linkgraph.ui.graphDocumentToDto(
        document = document,
        includeFullContent = includeFullContent,
        layoutState = layoutState,
        maxSecondaryNodes = MAX_SECONDARY_LAYER_SERIALIZED_NODES,
        maxSecondaryEdges = MAX_SECONDARY_LAYER_SERIALIZED_EDGES,
    )

    /** 把事实链路视图文档转换为前端 DTO。 */
    internal fun factGraphViewToDto(
        document: FactGraphViewDocument,
        layoutState: GraphLayoutState? = null,
    ): ViewDocumentDto = viewDocumentToDto(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = FactGraphViewSummaryDto(
            anchorTitle = document.summary.anchorTitle,
            visibleNodeCount = document.summary.visibleNodeCount,
            fullNodeCount = document.summary.fullNodeCount,
            hiddenNodeCount = document.summary.hiddenNodeCount,
            hiddenEdgeCount = document.summary.hiddenEdgeCount,
            truncated = document.summary.truncated,
        ),
        layoutState = layoutState,
        presentation = document.presentation,
    )

    /** 把流程图视图文档转换为前端 DTO。 */
    internal fun flowchartViewToDto(
        document: FlowchartViewDocument,
        layoutState: GraphLayoutState? = null,
    ): ViewDocumentDto = viewDocumentToDto(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = FlowchartViewSummaryDto(
            nodeCount = document.summary.nodeCount,
            branchCount = document.summary.branchCount,
            exceptionPathCount = document.summary.exceptionPathCount,
            fullNodeCount = document.summary.fullNodeCount,
            fullEdgeCount = document.summary.fullEdgeCount,
            incompleteNodeCount = document.summary.incompleteNodeCount,
            incompleteEdgeCount = document.summary.incompleteEdgeCount,
            semanticallyIncomplete = document.summary.semanticallyIncomplete,
            syntheticEdgeCount = document.summary.syntheticEdgeCount,
            syntheticEntryEdgeCount = document.summary.syntheticEntryEdgeCount,
        ),
        layoutState = layoutState,
    )

    /** 把资源关系视图文档转换为前端 DTO。 */
    internal fun resourceRelationViewToDto(
        document: ResourceRelationViewDocument,
        layoutState: GraphLayoutState? = null,
    ): ViewDocumentDto = viewDocumentToDto(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = ResourceRelationViewSummaryDto(
            visibleNodeCount = document.summary.visibleNodeCount,
            relationCount = document.summary.relationCount,
            resourceCount = document.summary.resourceCount,
            fallbackReason = document.summary.fallbackReason,
            laneCounts = document.summary.laneCounts,
        ),
        layoutState = layoutState,
    )

    /** 把架构图视图结果（含丰富的项目结构摘要）转换为前端 DTO。 */
    internal fun architectureGraphViewToDto(
        document: ArchitectureGraphResult,
        layoutState: GraphLayoutState? = null,
    ): ViewDocumentDto = viewDocumentToDto(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = ArchitectureGraphViewSummaryDto(
            moduleCount = document.summary.moduleCount,
            packageCount = document.summary.packageCount,
            serviceCount = document.summary.serviceCount,
            componentCount = document.summary.componentCount,
            resourceCount = document.summary.resourceCount,
            layerCount = document.summary.layerCount,
            libraryCount = document.summary.libraryCount,
            jdkCount = document.summary.jdkCount,
            relationCount = document.summary.relationCount,
            classCount = document.summary.classCount,
            relationshipNodeCount = document.summary.relationshipNodeCount,
            inventoryOnlyNodeCount = document.summary.inventoryOnlyNodeCount,
            unconnectedPackageCount = document.summary.unconnectedPackageCount,
            truncated = document.summary.truncated,
            hiddenNodeCount = document.summary.hiddenNodeCount,
            hiddenEdgeCount = document.summary.hiddenEdgeCount,
            unconnectedComponentCount = document.summary.unconnectedComponentCount,
            unconnectedServiceBoundaryCount = document.summary.unconnectedServiceBoundaryCount,
            unconnectedResourceCount = document.summary.unconnectedResourceCount,
            externalDependencyGroupCount = document.summary.externalDependencyGroupCount,
            jdkGroupCount = document.summary.jdkGroupCount,
            indexed = document.summary.indexed?.toDto(),
            projectStructureRelationGroups = document.summary.projectStructureRelationGroups.map { group ->
                ProjectStructureRelationGroupDto(
                    id = group.id,
                    fromNodeId = group.fromNodeId,
                    toNodeId = group.toNodeId,
                    displayRelationKind = group.displayRelationKind,
                    displayRelation = group.displayRelation,
                    relationKinds = group.relationKinds,
                    count = group.count,
                    confidence = group.confidence,
                    sourceRelationIds = group.sourceRelationIds,
                    sampleEvidenceRefs = group.sampleEvidenceRefs,
                    defaultVisible = group.defaultVisible,
                    hiddenReason = group.hiddenReason,
                )
            },
        ),
        layoutState = layoutState,
        presentation = document.presentation,
    )

    /** 把类图视图结果（类型统计、作用域基础、邻域限制等）转换为前端 DTO。 */
    internal fun classDiagramViewToDto(
        document: ClassDiagramResult,
        layoutState: GraphLayoutState? = null,
    ): ViewDocumentDto =
        viewDocumentToDto(
            visibleGraph = document.visibleGraph,
            fullGraph = document.fullGraph,
            anchorNodeId = document.anchorNodeId,
            projectionIndex = document.projectionIndex,
            summary = ClassDiagramViewSummaryDto(
                classCount = document.summary.classCount,
                fieldCount = document.summary.fieldCount,
                interfaceCount = document.summary.interfaceCount,
                enumCount = document.summary.enumCount,
                annotationCount = document.summary.annotationCount,
                recordCount = document.summary.recordCount,
                objectCount = document.summary.objectCount,
                relationCount = document.summary.relationCount,
                spiProviderCount = document.summary.spiProviderCount,
                reflectionRelationCount = document.summary.reflectionRelationCount,
                relationCompleteness = document.summary.relationCompleteness,
                scopeTypeCount = document.summary.scopeTypeCount,
                projectTypeCount = document.summary.projectTypeCount,
                projectClassCount = document.summary.projectClassCount,
                scopeBasis = document.summary.scopeBasis,
                anchorTypeNodeId = document.summary.anchorTypeNodeId,
                anchorTypeTitle = document.summary.anchorTypeTitle,
                anchorTypeQualifiedName = document.summary.anchorTypeQualifiedName,
                neighborhoodLimit = document.summary.neighborhoodLimit,
                memberLimit = document.summary.memberLimit,
                neighborhoodCandidateTypeCount = document.summary.neighborhoodCandidateTypeCount,
                neighborhoodTruncated = document.summary.neighborhoodTruncated,
                truncated = document.summary.truncated,
                hiddenNodeCount = document.summary.hiddenNodeCount,
                hiddenEdgeCount = document.summary.hiddenEdgeCount,
                indexed = document.summary.indexed?.toDto(),
            ),
            layoutState = layoutState,
            presentation = document.presentation,
        ).copy(usage = document.usage?.toDto())

    /** 把影响面审查图结果转换为前端 ReviewGraphViewDto。 */
    internal fun reviewGraphViewToDto(
        document: com.charmnight.linkgraph.review.ReviewGraphResult,
        layoutState: GraphLayoutState? = null,
    ): ReviewGraphViewDto {
        val base = viewDocumentToDto(
            visibleGraph = document.visibleGraph,
            fullGraph = document.fullGraph,
            anchorNodeId = document.anchorNodeId,
            projectionIndex = document.projectionIndex,
            summary = ReviewGraphViewSummaryDto(
                changedSymbolCount = document.summary.changedSymbolCount,
                upstreamCount = document.summary.upstreamCount,
                downstreamCount = document.summary.downstreamCount,
                relatedTestCount = document.summary.relatedTestCount,
                affectedPackageCount = document.summary.affectedPackageCount,
                affectedModuleCount = document.summary.affectedModuleCount,
                evidenceRefCount = document.summary.evidenceRefCount,
                truncated = document.summary.truncated,
                hiddenNodeCount = document.summary.hiddenNodeCount,
                hiddenEdgeCount = document.summary.hiddenEdgeCount,
                selectedDiffItemIds = document.summary.selectedDiffItemIds,
                maxChangedNodes = document.summary.maxChangedNodes,
                maxUpstreamNodes = document.summary.maxUpstreamNodes,
                maxDownstreamNodes = document.summary.maxDownstreamNodes,
                maxRelatedTestNodes = document.summary.maxRelatedTestNodes,
                indexed = document.summary.indexed?.toDto(),
            ),
            layoutState = layoutState,
        )
        return ReviewGraphViewDto(
            visibleGraph = base.visibleGraph,
            fullGraph = base.fullGraph,
            anchorNodeId = base.anchorNodeId,
            projectionIndex = base.projectionIndex,
            summary = base.summary as ReviewGraphViewSummaryDto,
            changedFiles = document.changedFiles.map { file ->
                ReviewChangedFileDto(
                    oldPath = file.oldPath,
                    newPath = file.newPath,
                    changeKind = file.changeKind,
                    hunkCount = file.hunkCount,
                    similarity = file.similarity,
                )
            },
            changedHunks = document.changedHunks.map(::reviewHunkToDto),
            unmatchedHunks = document.unmatchedHunks.map(::reviewHunkToDto),
            baselineOnlySymbols = document.baselineOnlySymbols.map { symbol ->
                ReviewBaselineSymbolDto(
                    symbolId = symbol.symbolId,
                    qualifiedName = symbol.qualifiedName,
                    filePath = symbol.filePath,
                    startLine = symbol.startLine,
                    endLine = symbol.endLine,
                    changeKind = symbol.changeKind,
                    blastRadiusIncomplete = symbol.blastRadiusIncomplete,
                    unavailableReason = symbol.unavailableReason,
                    reason = symbol.reason,
                )
            },
            relatedTests = document.relatedTests.map { test ->
                ReviewRelatedTestDto(
                    symbolId = test.symbolId,
                    qualifiedName = test.qualifiedName,
                    reason = test.reason,
                    filePath = test.filePath,
                    startLine = test.startLine,
                )
            },
            affectedPackages = document.affectedPackages,
            affectedModules = document.affectedModules,
            evidenceSnippets = document.evidenceSnippets.map { evidence ->
                ReviewEvidenceSnippetDto(
                    title = evidence.title,
                    kind = evidence.kind,
                    filePath = evidence.filePath,
                    startLine = evidence.startLine,
                    endLine = evidence.endLine,
                    snippet = evidence.snippet,
                    unavailableReason = evidence.unavailableReason,
                )
            },
        )
    }

    /** 把代码审查中的变更 hunk（含匹配到的符号 ID 和原因）转换为前端 DTO。 */
    private fun reviewHunkToDto(
        hunk: com.charmnight.linkgraph.review.ReviewGraphChangedHunk,
    ): ReviewHunkDto = ReviewHunkDto(
        filePath = hunk.filePath,
        oldFilePath = hunk.oldFilePath,
        newFilePath = hunk.newFilePath,
        changeKind = hunk.changeKind,
        header = hunk.header,
        oldStartLine = hunk.oldStartLine,
        oldLineCount = hunk.oldLineCount,
        newStartLine = hunk.newStartLine,
        newLineCount = hunk.newLineCount,
        matchedSymbolIds = hunk.matchedSymbolIds,
        reason = hunk.reason,
    )

    /** 把三视图通用视图文档转换为前端 DTO。 */
    private fun viewDocumentToDto(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
        projectionIndex: com.charmnight.linkgraph.application.model.GraphProjectionIndex,
        summary: Any,
        layoutState: GraphLayoutState? = null,
        presentation: GraphViewPresentation? = null,
    ): ViewDocumentDto = ViewDocumentDto(
        visibleGraph = documentToDto(visibleGraph, includeFullContent = true, layoutState = layoutState),
        fullGraph = documentToDto(fullGraph, includeFullContent = false, layoutState = layoutState),
        anchorNodeId = anchorNodeId,
        projectionIndex = com.charmnight.linkgraph.ui.graphProjectionIndexToDto(projectionIndex),
        summary = summary,
        presentation = presentation,
    )

    /** 把投影索引转换为前端 DTO：详见 top-level fun graphProjectionIndexToDto。 */
    private fun projectionIndexToDto(
        projectionIndex: com.charmnight.linkgraph.application.model.GraphProjectionIndex,
    ): GraphProjectionIndexDto = com.charmnight.linkgraph.ui.graphProjectionIndexToDto(projectionIndex)

    /** 把图补丁转换为前端使用的 Map 结构。 */
    /** 把补丁整体转换为前端 DTO：详见 top-level fun patchToDto。 */
    internal fun patchToDto(patch: GraphPatch): GraphPatchDto =
        com.charmnight.linkgraph.ui.patchToDto(patch)

    /** 把单条补丁操作转换为前端 DTO：详见 top-level fun patchOperationToDto。 */
    private fun patchOperationToDto(operation: GraphPatchOperation): GraphPatchOperationDto =
        com.charmnight.linkgraph.ui.patchOperationToDto(operation)

    /** 把补丁类结果转换为前端 DTO：详见 top-level fun patchResultToDto。 */
    internal fun patchResultToDto(
        result: GraphPatchResult,
        promptPreviewArtifactId: String?,
    ): PatchResultDto = com.charmnight.linkgraph.ui.patchResultToDto(result, promptPreviewArtifactId)

    /** 把链路讲解结果转换为前端 DTO：详见 top-level fun beautificationResultToDto。 */
    internal fun beautificationResultToDto(
        result: GraphBeautificationResult,
        promptPreviewArtifactId: String?,
    ): BeautificationResultDto = com.charmnight.linkgraph.ui.beautificationResultToDto(result, promptPreviewArtifactId)

    /** 把证据发现项转换为前端使用的 Map 结构。 */
    /** 把单条证据结论转换为前端 DTO：详见 top-level fun resultEvidenceFindingToDto。 */
    private fun resultEvidenceFindingToDto(finding: com.charmnight.linkgraph.llm.ResultEvidenceFinding): ResultEvidenceFindingDto =
        com.charmnight.linkgraph.ui.resultEvidenceFindingToDto(finding)

    /** 把草稿补丁应用结果转换为前端 DTO：详见 top-level fun draftPatchApplyResultToDto。 */
    internal fun draftPatchApplyResultToDto(result: DraftPatchApplyResult): DraftPatchApplyResultDto =
        com.charmnight.linkgraph.ui.draftPatchApplyResultToDto(result)

    /** 把草稿工作台状态转换为前端 DTO：详见 top-level fun draftWorkbenchStateToDto。 */
    internal fun draftWorkbenchStateToDto(
        state: com.charmnight.linkgraph.workbench.DraftWorkbenchState,
    ): DraftWorkbenchStateDto = com.charmnight.linkgraph.ui.draftWorkbenchStateToDto(state)

    /** 把单条草稿工作台条目转换为前端 DTO：详见 top-level fun draftWorkbenchEntryToDto。 */
    private fun draftWorkbenchEntryToDto(
        entry: com.charmnight.linkgraph.workbench.DraftWorkbenchEntry,
    ): DraftWorkbenchEntryDto = com.charmnight.linkgraph.ui.draftWorkbenchEntryToDto(entry)

    /** 把候选草稿变更转换为前端 DTO：详见 top-level fun candidateDraftChangeToDto。 */
    private fun candidateDraftChangeToDto(
        change: com.charmnight.linkgraph.workbench.CandidateDraftChange,
    ): CandidateDraftChangeDto = com.charmnight.linkgraph.ui.candidateDraftChangeToDto(change)

    /** 把候选补丁意图转换为前端 DTO：详见 top-level fun candidatePatchIntentToDto。 */
    private fun candidatePatchIntentToDto(
        intent: com.charmnight.linkgraph.workbench.CandidatePatchIntent,
    ): CandidatePatchIntentDto = com.charmnight.linkgraph.ui.candidatePatchIntentToDto(intent)

    /** 把 QA 多轮对话会话转换为前端 DTO：详见 top-level fun qaConversationSessionToDto。 */
    private fun qaConversationSessionToDto(
        session: com.charmnight.linkgraph.workbench.QaConversationSession,
    ): QaConversationSessionDto = com.charmnight.linkgraph.ui.qaConversationSessionToDto(session)

    /** 把生成计划讨论会话转换为前端 DTO：详见 top-level fun generationPlanDiscussionSessionToDto。 */
    internal fun generationPlanDiscussionSessionToDto(
        session: com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession,
        promptPreviewArtifactId: String?,
    ): GenerationPlanDiscussionSessionDto =
        com.charmnight.linkgraph.ui.generationPlanDiscussionSessionToDto(session, promptPreviewArtifactId)

    /** 判断是否拥有可展示的 prompt 预览：文本或工件 ID 至少有一个非空即可。 */
    internal fun hasPromptPreview(promptPreview: String?, promptPreviewArtifactId: String?): Boolean {
        return !promptPreview.isNullOrBlank() || !promptPreviewArtifactId.isNullOrBlank()
    }

    /** 把草稿校验状态转换为前端 DTO：详见 top-level fun draftValidationStateToDto。 */
    internal fun draftValidationStateToDto(
        state: com.charmnight.linkgraph.workbench.DraftValidationState,
    ): DraftValidationStateDto = com.charmnight.linkgraph.ui.draftValidationStateToDto(state)

    /** 把单条调查线程转换为前端 DTO：详见 top-level fun investigationThreadToDto。 */
    private fun investigationThreadToDto(
        thread: com.charmnight.linkgraph.workbench.InvestigationThread,
    ): InvestigationThreadDto = com.charmnight.linkgraph.ui.investigationThreadToDto(thread)

    /** 把风险线程的解决结果转换为前端 DTO：详见 top-level fun riskResolutionToDto。 */
    private fun riskResolutionToDto(
        resolution: com.charmnight.linkgraph.workbench.RiskResolution,
    ): RiskResolutionDto = com.charmnight.linkgraph.ui.riskResolutionToDto(resolution)

    /** 把单轮调查结果转换为前端 DTO：详见 top-level fun investigationTurnOutcomeToDto。 */
    private fun investigationTurnOutcomeToDto(
        outcome: com.charmnight.linkgraph.workbench.InvestigationTurnOutcome,
    ): InvestigationTurnOutcomeDto = com.charmnight.linkgraph.ui.investigationTurnOutcomeToDto(outcome)

    /** 把 QA 多轮对话中的单条消息转换为前端 DTO：详见 top-level fun qaConversationMessageToDto。 */
    private fun qaConversationMessageToDto(
        message: com.charmnight.linkgraph.workbench.QaConversationMessage,
    ): QaConversationMessageDto = com.charmnight.linkgraph.ui.qaConversationMessageToDto(message)

    /** 把源码片段上下文转换为前端 DTO：详见 top-level fun sourceSnippetContextToDto。 */
    private fun sourceSnippetContextToDto(
        snippet: com.charmnight.linkgraph.llm.SourceSnippetContext,
    ): SourceSnippetContextDto = com.charmnight.linkgraph.ui.sourceSnippetContextToDto(snippet)

    /** 把证据追踪条目转换为前端 DTO：详见 top-level fun evidenceTraceEntryToDto。 */
    private fun evidenceTraceEntryToDto(
        trace: com.charmnight.linkgraph.llm.EvidenceTraceEntry,
    ): EvidenceTraceEntryDto = com.charmnight.linkgraph.ui.evidenceTraceEntryToDto(trace)

    /** 把代码编辑作用域转换为前端 DTO：详见 top-level fun editScopeToDto。 */
    private fun editScopeToDto(
        scope: com.charmnight.linkgraph.llm.EditScope,
    ): EditScopeDto = com.charmnight.linkgraph.ui.editScopeToDto(scope)

    /** 把单条代码编辑操作转换为前端 DTO：详见 top-level fun codeEditOperationToDto。 */
    private fun codeEditOperationToDto(
        operation: com.charmnight.linkgraph.codegen.CodeEditOperation,
    ): CodeEditOperationDto = com.charmnight.linkgraph.ui.codeEditOperationToDto(operation)

    /** 把准备好的代码编辑转换为前端 DTO：详见 top-level fun preparedCodeEditToDto。 */
    private fun preparedCodeEditToDto(
        edit: com.charmnight.linkgraph.codegen.PreparedCodeEdit,
    ): PreparedCodeEditDto = com.charmnight.linkgraph.ui.preparedCodeEditToDto(edit)

    /** 从节点元数据中提取 UI 坐标。 */
    /** uiPosition / semanticMetadata 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

}
