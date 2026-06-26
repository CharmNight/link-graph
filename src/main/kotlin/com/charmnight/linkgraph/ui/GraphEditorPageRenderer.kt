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

    /** 构建完整 bootstrap 状态载荷，供 init 与增量 slice 复用。 */
    internal fun bootstrapPayload(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): LinkedHashMap<String, Any?> {
        return bootstrapPayload(
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 构建完整 bootstrap 状态载荷，供 init 与增量 slice 复用。委托给 [payloadAssembler]。 */
    internal fun bootstrapPayload(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): LinkedHashMap<String, Any?> = payloadAssembler.assemble(snapshot, artifactRefs)

    /** 把多个场景的运行时状态映射为前端使用的字典结构。 */
    /** sceneStatesToMap / graphSceneStateToMap 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

    /** 把异步请求状态转换成前端可消费的映射。 */
    internal fun requestStateToMap(
        state: com.charmnight.linkgraph.ui.AsyncRequestState,
        hasPromptPreview: Boolean = state.promptPreviewAvailable,
    ): Map<String, Any?> = linkedMapOf(
        "phase" to state.phase.name,
        "requestId" to state.requestId,
        "scene" to state.scene,
        "executionMode" to state.executionMode?.name,
        "statusMessage" to state.statusMessage,
        "errorMessage" to state.errorMessage,
        "detailMessage" to state.detailMessage,
        "startedAtEpochMillis" to state.startedAtEpochMillis,
        "finishedAtEpochMillis" to state.finishedAtEpochMillis,
        "streaming" to state.streaming,
        "fallbackUsed" to state.fallbackUsed,
        "streamPhase" to state.streamPhase,
        "previewText" to state.previewText,
        "previewUpdatedAtEpochMillis" to state.previewUpdatedAtEpochMillis,
        "finalizingStructuredResult" to state.finalizingStructuredResult,
        "providerLabel" to state.providerLabel,
        "model" to state.model,
        "endpointSummary" to state.endpointSummary,
        "promptPreviewAvailable" to hasPromptPreview,
        "requestedMode" to state.requestedMode?.name,
        "effectiveMode" to state.effectiveMode?.name,
    )

    /** 把 QA 请求恢复状态（最近成功/失败的请求）转换为前端可重放的载荷。 */
    internal fun qaRequestRecoveryStateToMap(
        state: com.charmnight.linkgraph.workbench.QaRequestRecoveryState,
    ): Map<String, Any?> = com.charmnight.linkgraph.ui.qaRequestRecoveryStateToMap(state)

    /** 把可重放的 QA 请求结构（用于失败后重试或回放）展开为前端字段：详见 top-level fun replayableQaRequestToMap。 */
    private fun replayableQaRequestToMap(
        request: com.charmnight.linkgraph.workbench.ReplayableQaRequest,
    ): Map<String, Any?> = com.charmnight.linkgraph.ui.replayableQaRequestToMap(request)

    /** 把助手结果存储（按结果 ID 索引的多种轮次结果）展开为前端可消费的嵌套结构。 */
    internal fun assistantResultStoreToMap(
        store: com.charmnight.linkgraph.workbench.AssistantResultStore,
        artifactRefs: Map<String, GraphEditorArtifactRegistry.AssistantResultArtifacts>,
    ): Map<String, Any?> = store.results.mapValues { (resultId, entry) ->
        val resultArtifacts = artifactRefs[resultId] ?: GraphEditorArtifactRegistry.AssistantResultArtifacts()
        linkedMapOf<String, Any?>(
            "kind" to entry.kind.name,
            "failure" to entry.failure?.let(::assistantFailureResultToMap),
        ).apply {
            when (entry.kind) {
                com.charmnight.linkgraph.workbench.AssistantTurnKind.EXPLANATION -> {
                    put("explanation", entry.explanation?.let { result ->
                        beautificationResultToMap(
                            result,
                            promptPreviewArtifactId = resultArtifacts.explanationPromptPreviewArtifactId,
                        )
                    })
                }
                com.charmnight.linkgraph.workbench.AssistantTurnKind.QA -> {
                    put("qa", entry.qa?.let { result ->
                        patchResultToMap(
                            result,
                            promptPreviewArtifactId = resultArtifacts.qaPromptPreviewArtifactId,
                        )
                    })
                }
                com.charmnight.linkgraph.workbench.AssistantTurnKind.GENERATION_PLAN -> {
                    put("generationPlan", entry.generationPlan?.let { plan ->
                        generationPlanToMap(
                            plan,
                            promptPreviewArtifactId = resultArtifacts.generationPlanPromptPreviewArtifactId,
                        )
                    })
                    put("generationDiscussionSession", entry.generationDiscussionSession?.let { session ->
                        generationPlanDiscussionSessionToMap(
                            session,
                            promptPreviewArtifactId = resultArtifacts.generationDiscussionPromptPreviewArtifactId,
                        )
                    })
                }
                com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT -> {
                    put("generationPlan", entry.generationPlan?.let { plan ->
                        generationPlanToMap(
                            plan,
                            promptPreviewArtifactId = resultArtifacts.generationPlanPromptPreviewArtifactId,
                        )
                    })
                    put("generationDiscussionSession", entry.generationDiscussionSession?.let { session ->
                        generationPlanDiscussionSessionToMap(
                            session,
                            promptPreviewArtifactId = resultArtifacts.generationDiscussionPromptPreviewArtifactId,
                        )
                    })
                    put("codeDraftWarnings", entry.codeDraftWarnings)
                    put("codeDrafts", entry.codeDrafts.map { draft ->
                        generatedCodeDraftToMap(
                            draft,
                            contentArtifactId = resultArtifacts.codeDraftContentArtifactIds[draft.id],
                        )
                    })
                }
                com.charmnight.linkgraph.workbench.AssistantTurnKind.CHECK_RESULT -> {
                    put("check", entry.check?.let { result ->
                        patchResultToMap(
                            result,
                            promptPreviewArtifactId = resultArtifacts.checkPromptPreviewArtifactId,
                        )
                    })
                }
            }
        }
    }

    /** 把助手调用失败结果转换为前端字段，携带错误消息、阶段与时间戳。 */
    /** assistantFailureResultToMap / generationPlanToMap 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

    /** 把生成的代码草稿（编辑操作、范围、准备好的编辑等）转换为前端结构。 */
    internal fun generatedCodeDraftToMap(
        draft: com.charmnight.linkgraph.codegen.GeneratedCodeDraft,
        contentArtifactId: String?,
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "id" to draft.id,
        "sourceNodeId" to draft.sourceNodeId,
        "title" to draft.title,
        "targetPath" to draft.targetPath,
        "contentArtifactId" to contentArtifactId,
        "editOperations" to draft.editOperations.map(::codeEditOperationToMap),
        "editScopes" to draft.editScopes.map(::editScopeToMap),
        "preparedEdits" to draft.preparedEdits.map(::preparedCodeEditToMap),
        "warnings" to draft.warnings,
    ).apply {
        if (contentArtifactId == null && draft.content != null) {
            put("content", draft.content)
        }
    }

    /** 把阶段准入判定（是否允许进入下一阶段、阻塞原因等）转换为前端结构。 */
    internal fun stageEligibilityDecisionToMap(
        decision: com.charmnight.linkgraph.workbench.StageEligibilityDecision,
    ): Map<String, Any?> = com.charmnight.linkgraph.ui.stageEligibilityDecisionToMap(decision)

    /** 把源码跳转状态转换成前端可消费的映射：详见 top-level fun sourceNavigationStateToMap。 */
    internal fun sourceNavigationStateToMap(
        state: com.charmnight.linkgraph.ui.SourceNavigationState,
    ): Map<String, Any?> = com.charmnight.linkgraph.ui.sourceNavigationStateToMap(state)

    /** 为 diff 项解析可读标题。 */
    internal fun resolveDiffTitle(
        entry: com.charmnight.linkgraph.model.GraphDiffEntry,
        document: GraphDocument,
    ): String = com.charmnight.linkgraph.ui.resolveDiffTitle(entry, document)

    /** 把节点转换为前端使用的 Map 结构：详见 top-level fun nodeToMap。 */
    private fun nodeToMap(
        node: GraphNode,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = com.charmnight.linkgraph.ui.nodeToMap(node, layoutState)

    /** 把边转换为前端使用的 Map 结构。 */
    private fun edgeToMap(edge: GraphEdge): Map<String, Any?> =
        com.charmnight.linkgraph.ui.edgeToMap(edge)

    /** 把图文档转换为前端使用的 Map，并按规模决定是否裁剪内容。 */
    internal fun documentToMap(
        document: GraphDocument,
        includeFullContent: Boolean,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> {
        /** 是否允许把图内容完整内联到 bootstrap 中。 */
        val shouldInlineContent = includeFullContent ||
            (
                document.nodes.size <= MAX_SECONDARY_LAYER_SERIALIZED_NODES &&
                    document.edges.size <= MAX_SECONDARY_LAYER_SERIALIZED_EDGES
                )
        return linkedMapOf(
            "nodes" to if (shouldInlineContent) document.nodes.map { node -> nodeToMap(node, layoutState) } else emptyList<Map<String, Any?>>(),
            "edges" to if (shouldInlineContent) document.edges.map(::edgeToMap) else emptyList<Map<String, Any?>>(),
            "patch" to document.patch?.let(::patchToMap),
            "nodeCount" to document.nodes.size,
            "edgeCount" to document.edges.size,
            "truncated" to !shouldInlineContent,
        )
    }

    /** 把事实链路视图文档转换为前端使用的 Map。 */
    internal fun factGraphViewToMap(
        document: FactGraphViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = linkedMapOf(
            "anchorTitle" to document.summary.anchorTitle,
            "visibleNodeCount" to document.summary.visibleNodeCount,
            "fullNodeCount" to document.summary.fullNodeCount,
            "hiddenNodeCount" to document.summary.hiddenNodeCount,
            "hiddenEdgeCount" to document.summary.hiddenEdgeCount,
            "truncated" to document.summary.truncated,
        ),
        layoutState = layoutState,
        presentation = document.presentation,
    )

    /** 把流程图视图文档转换为前端使用的 Map。 */
    internal fun flowchartViewToMap(
        document: FlowchartViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = linkedMapOf(
            "nodeCount" to document.summary.nodeCount,
            "branchCount" to document.summary.branchCount,
            "exceptionPathCount" to document.summary.exceptionPathCount,
            "fullNodeCount" to document.summary.fullNodeCount,
            "fullEdgeCount" to document.summary.fullEdgeCount,
            "incompleteNodeCount" to document.summary.incompleteNodeCount,
            "incompleteEdgeCount" to document.summary.incompleteEdgeCount,
            "semanticallyIncomplete" to document.summary.semanticallyIncomplete,
            "syntheticEdgeCount" to document.summary.syntheticEdgeCount,
            "syntheticEntryEdgeCount" to document.summary.syntheticEntryEdgeCount,
        ),
        layoutState = layoutState,
    )

    /** 把资源关系视图文档转换为前端使用的 Map。 */
    internal fun resourceRelationViewToMap(
        document: ResourceRelationViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = linkedMapOf(
            "visibleNodeCount" to document.summary.visibleNodeCount,
            "relationCount" to document.summary.relationCount,
            "resourceCount" to document.summary.resourceCount,
            "fallbackReason" to document.summary.fallbackReason,
            "laneCounts" to document.summary.laneCounts,
        ),
        layoutState = layoutState,
    )

    /** 把架构图视图结果（含丰富的项目结构摘要）转换为前端结构。 */
    internal fun architectureGraphViewToMap(
        document: ArchitectureGraphResult,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        projectionIndex = document.projectionIndex,
        summary = linkedMapOf(
            "moduleCount" to document.summary.moduleCount,
            "packageCount" to document.summary.packageCount,
            "serviceCount" to document.summary.serviceCount,
            "componentCount" to document.summary.componentCount,
            "resourceCount" to document.summary.resourceCount,
            "layerCount" to document.summary.layerCount,
            "libraryCount" to document.summary.libraryCount,
            "jdkCount" to document.summary.jdkCount,
            "relationCount" to document.summary.relationCount,
            "classCount" to document.summary.classCount,
            "relationshipNodeCount" to document.summary.relationshipNodeCount,
            "inventoryOnlyNodeCount" to document.summary.inventoryOnlyNodeCount,
            "unconnectedPackageCount" to document.summary.unconnectedPackageCount,
            "truncated" to document.summary.truncated,
            "hiddenNodeCount" to document.summary.hiddenNodeCount,
            "hiddenEdgeCount" to document.summary.hiddenEdgeCount,
            "unconnectedComponentCount" to document.summary.unconnectedComponentCount,
            "unconnectedServiceBoundaryCount" to document.summary.unconnectedServiceBoundaryCount,
            "unconnectedResourceCount" to document.summary.unconnectedResourceCount,
            "externalDependencyGroupCount" to document.summary.externalDependencyGroupCount,
            "jdkGroupCount" to document.summary.jdkGroupCount,
            "indexed" to document.summary.indexed?.toMap(),
            "projectStructureRelationGroups" to document.summary.projectStructureRelationGroups.map { group ->
                linkedMapOf(
                    "id" to group.id,
                    "fromNodeId" to group.fromNodeId,
                    "toNodeId" to group.toNodeId,
                    "displayRelationKind" to group.displayRelationKind,
                    "displayRelation" to group.displayRelation,
                    "relationKinds" to group.relationKinds,
                    "count" to group.count,
                    "confidence" to group.confidence,
                    "sourceRelationIds" to group.sourceRelationIds,
                    "sampleEvidenceRefs" to group.sampleEvidenceRefs,
                    "defaultVisible" to group.defaultVisible,
                    "hiddenReason" to group.hiddenReason,
                )
            },
        ),
        layoutState = layoutState,
        presentation = document.presentation,
    )

    /** 把类图视图结果（类型统计、作用域基础、邻域限制等）转换为前端结构。 */
    internal fun classDiagramViewToMap(
        document: ClassDiagramResult,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> =
        viewDocumentToMap(
            visibleGraph = document.visibleGraph,
            fullGraph = document.fullGraph,
            anchorNodeId = document.anchorNodeId,
            projectionIndex = document.projectionIndex,
            summary = linkedMapOf(
                "classCount" to document.summary.classCount,
                "fieldCount" to document.summary.fieldCount,
                "interfaceCount" to document.summary.interfaceCount,
                "enumCount" to document.summary.enumCount,
                "annotationCount" to document.summary.annotationCount,
                "recordCount" to document.summary.recordCount,
                "objectCount" to document.summary.objectCount,
                "relationCount" to document.summary.relationCount,
                "spiProviderCount" to document.summary.spiProviderCount,
                "reflectionRelationCount" to document.summary.reflectionRelationCount,
                "relationCompleteness" to document.summary.relationCompleteness,
                "scopeTypeCount" to document.summary.scopeTypeCount,
                "projectTypeCount" to document.summary.projectTypeCount,
                "projectClassCount" to document.summary.projectClassCount,
                "scopeBasis" to document.summary.scopeBasis,
                "anchorTypeNodeId" to document.summary.anchorTypeNodeId,
                "anchorTypeTitle" to document.summary.anchorTypeTitle,
                "anchorTypeQualifiedName" to document.summary.anchorTypeQualifiedName,
                "neighborhoodLimit" to document.summary.neighborhoodLimit,
                "memberLimit" to document.summary.memberLimit,
                "neighborhoodCandidateTypeCount" to document.summary.neighborhoodCandidateTypeCount,
                "neighborhoodTruncated" to document.summary.neighborhoodTruncated,
                "truncated" to document.summary.truncated,
                "hiddenNodeCount" to document.summary.hiddenNodeCount,
                "hiddenEdgeCount" to document.summary.hiddenEdgeCount,
                "indexed" to document.summary.indexed?.toMap(),
            ),
            layoutState = layoutState,
            presentation = document.presentation,
        ).toMutableMap().apply {
            put("usage", document.usage?.toDto())
        }

    /** 把影响面审查图结果（变更符号、上下游、相关测试、证据片段等）转换为前端结构。 */
    internal fun reviewGraphViewToMap(
        document: com.charmnight.linkgraph.review.ReviewGraphResult,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> =
        viewDocumentToMap(
            visibleGraph = document.visibleGraph,
            fullGraph = document.fullGraph,
            anchorNodeId = document.anchorNodeId,
            projectionIndex = document.projectionIndex,
            summary = linkedMapOf(
                "changedSymbolCount" to document.summary.changedSymbolCount,
                "upstreamCount" to document.summary.upstreamCount,
                "downstreamCount" to document.summary.downstreamCount,
                "relatedTestCount" to document.summary.relatedTestCount,
                "affectedPackageCount" to document.summary.affectedPackageCount,
                "affectedModuleCount" to document.summary.affectedModuleCount,
                "evidenceRefCount" to document.summary.evidenceRefCount,
                "truncated" to document.summary.truncated,
                "hiddenNodeCount" to document.summary.hiddenNodeCount,
                "hiddenEdgeCount" to document.summary.hiddenEdgeCount,
                "selectedDiffItemIds" to document.summary.selectedDiffItemIds,
                "maxChangedNodes" to document.summary.maxChangedNodes,
                "maxUpstreamNodes" to document.summary.maxUpstreamNodes,
                "maxDownstreamNodes" to document.summary.maxDownstreamNodes,
                "maxRelatedTestNodes" to document.summary.maxRelatedTestNodes,
                "indexed" to document.summary.indexed?.toMap(),
            ),
            layoutState = layoutState,
        ).toMutableMap().apply {
            put("changedFiles", document.changedFiles.map { file ->
                linkedMapOf(
                    "oldPath" to file.oldPath,
                    "newPath" to file.newPath,
                    "changeKind" to file.changeKind,
                    "hunkCount" to file.hunkCount,
                    "similarity" to file.similarity,
                )
            })
            put("changedHunks", document.changedHunks.map { hunk -> reviewHunkToMap(hunk) })
            put("unmatchedHunks", document.unmatchedHunks.map { hunk -> reviewHunkToMap(hunk) })
            put("baselineOnlySymbols", document.baselineOnlySymbols.map { symbol ->
                linkedMapOf(
                    "symbolId" to symbol.symbolId,
                    "qualifiedName" to symbol.qualifiedName,
                    "filePath" to symbol.filePath,
                    "startLine" to symbol.startLine,
                    "endLine" to symbol.endLine,
                    "changeKind" to symbol.changeKind,
                    "blastRadiusIncomplete" to symbol.blastRadiusIncomplete,
                    "unavailableReason" to symbol.unavailableReason,
                    "reason" to symbol.reason,
                )
            })
            put("relatedTests", document.relatedTests.map { test ->
                linkedMapOf(
                    "symbolId" to test.symbolId,
                    "qualifiedName" to test.qualifiedName,
                    "reason" to test.reason,
                    "filePath" to test.filePath,
                    "startLine" to test.startLine,
                )
            })
            put("affectedPackages", document.affectedPackages)
            put("affectedModules", document.affectedModules)
            put("evidenceSnippets", document.evidenceSnippets.map { evidence ->
                linkedMapOf(
                    "title" to evidence.title,
                    "kind" to evidence.kind,
                    "filePath" to evidence.filePath,
                    "startLine" to evidence.startLine,
                    "endLine" to evidence.endLine,
                    "snippet" to evidence.snippet,
                    "unavailableReason" to evidence.unavailableReason,
                )
            })
        }

    /** 把代码审查中的变更 hunk（含匹配到的符号 ID 和原因）转换为前端结构。 */
    private fun reviewHunkToMap(
        hunk: com.charmnight.linkgraph.review.ReviewGraphChangedHunk,
    ): Map<String, Any?> = linkedMapOf(
        "filePath" to hunk.filePath,
        "oldFilePath" to hunk.oldFilePath,
        "newFilePath" to hunk.newFilePath,
        "changeKind" to hunk.changeKind,
        "header" to hunk.header,
        "oldStartLine" to hunk.oldStartLine,
        "oldLineCount" to hunk.oldLineCount,
            "newStartLine" to hunk.newStartLine,
            "newLineCount" to hunk.newLineCount,
            "matchedSymbolIds" to hunk.matchedSymbolIds,
            "reason" to hunk.reason,
        )

    /** 把三视图通用视图文档转换为前端使用的 Map。 */
    private fun viewDocumentToMap(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
        projectionIndex: com.charmnight.linkgraph.application.model.GraphProjectionIndex,
        summary: Map<String, Any?>,
        layoutState: GraphLayoutState? = null,
        presentation: GraphViewPresentation? = null,
    ): Map<String, Any?> =
        linkedMapOf(
            "visibleGraph" to documentToMap(visibleGraph, includeFullContent = true, layoutState = layoutState),
            "fullGraph" to documentToMap(fullGraph, includeFullContent = false, layoutState = layoutState),
            "anchorNodeId" to anchorNodeId,
            "projectionIndex" to projectionIndexToMap(projectionIndex),
            "summary" to summary,
        ).apply {
            if (presentation != null) {
                put("presentation", presentation.toMap())
            }
        }

    /** 把投影索引（节点/边的规范化映射）转换为前端结构。 */
    private fun projectionIndexToMap(
        projectionIndex: com.charmnight.linkgraph.application.model.GraphProjectionIndex,
    ): Map<String, Any?> = linkedMapOf(
        "nodeMappings" to projectionIndex.nodeMappings.mapValues { (_, mapping) ->
            linkedMapOf(
                "projectedNodeId" to mapping.projectedNodeId,
                "mappingKind" to mapping.mappingKind.name,
                "canonicalNodeIds" to mapping.canonicalNodeIds,
                "editableCommandKinds" to mapping.editableCommandKinds.map { it.name },
            )
        },
        "edgeMappings" to projectionIndex.edgeMappings.mapValues { (_, mapping) ->
            linkedMapOf(
                "projectedEdgeId" to mapping.projectedEdgeId,
                "mappingKind" to mapping.mappingKind.name,
                "canonicalEdgeIds" to mapping.canonicalEdgeIds,
                "canonicalPathNodeIds" to mapping.canonicalPathNodeIds,
                "editableCommandKinds" to mapping.editableCommandKinds.map { it.name },
            )
        },
    )

    /** 把图补丁转换为前端使用的 Map 结构。 */
    internal fun patchToMap(patch: GraphPatch): Map<String, Any?> =
        com.charmnight.linkgraph.ui.patchToMap(patch)

    /** 把单条补丁操作转换为前端使用的 Map 结构：详见 top-level fun patchOperationToMap。 */
    private fun patchOperationToMap(operation: GraphPatchOperation): Map<String, Any?> =
        com.charmnight.linkgraph.ui.patchOperationToMap(operation)

    /** 把补丁类结果转换为前端使用的 Map 结构。 */
    internal fun patchResultToMap(
        result: GraphPatchResult,
        promptPreviewArtifactId: String?,
    ): Map<String, Any?> = linkedMapOf(
        "source" to result.source.name,
        "question" to result.question,
        "requestedMode" to result.requestedMode.name,
        "effectiveMode" to result.effectiveMode.name,
        "answer" to result.answer,
        "promptPreviewArtifactId" to promptPreviewArtifactId,
        "warnings" to result.warnings,
        "findings" to result.findings.map(::resultEvidenceFindingToMap),
        "candidateChanges" to result.candidateChanges.map(::candidateDraftChangeToMap),
        "newCandidateChanges" to result.newCandidateChanges.map(::candidateDraftChangeToMap),
        "investigationThreads" to result.investigationThreads.map(::investigationThreadToMap),
        "latestTurnOutcome" to result.latestTurnOutcome?.let(::investigationTurnOutcomeToMap),
        "recentTurnOutcomes" to result.recentTurnOutcomes.map(::investigationTurnOutcomeToMap),
        "sourceContext" to result.sourceContext.map(::sourceSnippetContextToMap),
        "evidenceTrace" to result.evidenceTrace.map(::evidenceTraceEntryToMap),
        "qaSession" to result.qaSession?.let(::qaConversationSessionToMap),
        "patch" to result.patch?.let(::patchToMap),
    )

    /** 把链路讲解结果转换为前端使用的 Map 结构。 */
    internal fun beautificationResultToMap(
        result: GraphBeautificationResult,
        promptPreviewArtifactId: String?,
    ): Map<String, Any?> = linkedMapOf(
        "source" to result.source.name,
        "granularity" to result.granularity.name,
        "steps" to result.steps.map { step ->
            linkedMapOf(
                "stepId" to step.stepId,
                "title" to step.title,
                "granularity" to step.granularity.name,
                "kind" to step.kind.name,
                "description" to step.description,
                "primaryNodeId" to step.primaryNodeId,
                "codeSnippet" to step.codeSnippet,
                "evidence" to step.evidence.map(::resultEvidenceFindingToMap),
                "followUpQuestions" to step.followUpQuestions,
                "downstreamTargets" to step.downstreamTargets,
            )
        },
        "promptPreviewArtifactId" to promptPreviewArtifactId,
        "warnings" to result.warnings,
    )

    /** 把证据发现项转换为前端使用的 Map 结构。 */
    private fun resultEvidenceFindingToMap(finding: com.charmnight.linkgraph.llm.ResultEvidenceFinding): Map<String, Any?> =
        com.charmnight.linkgraph.ui.resultEvidenceFindingToMap(finding)

    /** 把草稿补丁应用结果转换为前端使用的 Map 结构。 */
    internal fun draftPatchApplyResultToMap(result: DraftPatchApplyResult): Map<String, Any?> = linkedMapOf(
        "summary" to result.summary,
        "appliedOperationCount" to result.appliedOperationCount,
        "appliedNodeIds" to result.appliedNodeIds,
        "appliedEdgeIds" to result.appliedEdgeIds,
        "focusNodeId" to result.focusNodeId,
        "appliedTargets" to result.appliedTargets,
    )

    /** 把草稿工作台状态（草稿变更与笔记条目）转换为前端结构。 */
    internal fun draftWorkbenchStateToMap(
        state: com.charmnight.linkgraph.workbench.DraftWorkbenchState,
    ): Map<String, Any?> = linkedMapOf(
        "draftChanges" to state.draftChanges.map(::draftWorkbenchEntryToMap),
        "draftNotes" to state.draftNotes.map(::draftWorkbenchEntryToMap),
    )

    /** 把单条草稿工作台条目（前后状态、影响摘要、证据等）转换为前端结构。 */
    private fun draftWorkbenchEntryToMap(
        entry: com.charmnight.linkgraph.workbench.DraftWorkbenchEntry,
    ): Map<String, Any?> = linkedMapOf(
        "entryId" to entry.entryId,
        "kind" to entry.kind.name,
        "title" to entry.title,
        "sourceChangeId" to entry.sourceChangeId,
        "targetStepIds" to entry.targetStepIds,
        "targetNodeIds" to entry.targetNodeIds,
        "beforeState" to entry.beforeState,
        "afterState" to entry.afterState,
        "reason" to entry.reason,
        "impactSummary" to entry.impactSummary,
        "claimType" to entry.claimType,
        "evidence" to entry.evidence.map(::resultEvidenceFindingToMap),
        "editScopes" to entry.editScopes.map(::editScopeToMap),
        "patchIntent" to entry.patchIntent?.let(::candidatePatchIntentToMap),
        "graphPatch" to entry.graphPatch?.let(::patchToMap),
    )

    /** 把候选草稿变更（含状态、证据、补丁意图等）转换为前端结构。 */
    private fun candidateDraftChangeToMap(
        change: com.charmnight.linkgraph.workbench.CandidateDraftChange,
    ): Map<String, Any?> = linkedMapOf(
        "changeId" to change.changeId,
        "status" to change.status.name,
        "title" to change.title,
        "targetStepIds" to change.targetStepIds,
        "targetNodeIds" to change.targetNodeIds,
        "beforeState" to change.beforeState,
        "afterState" to change.afterState,
        "reason" to change.reason,
        "impactSummary" to change.impactSummary,
        "claimType" to change.claimType,
        "evidence" to change.evidence.map(::resultEvidenceFindingToMap),
        "editScopes" to change.editScopes.map(::editScopeToMap),
        "patchIntent" to change.patchIntent?.let(::candidatePatchIntentToMap),
        "graphPatch" to change.graphPatch?.let(::patchToMap),
    )

    /** 把候选补丁意图（附加目标、真假分支节点）转换为前端结构。 */
    private fun candidatePatchIntentToMap(
        intent: com.charmnight.linkgraph.workbench.CandidatePatchIntent,
    ): Map<String, Any?> = linkedMapOf(
        "mode" to intent.mode.name,
        "targetNodeId" to intent.targetNodeId,
        "attachEdgeId" to intent.attachEdgeId,
        "falseBranchTargetNodeId" to intent.falseBranchTargetNodeId,
    )

    /** 把 QA 多轮对话会话（消息列表、候选变更、调查线程等）转换为前端结构。 */
    private fun qaConversationSessionToMap(
        session: com.charmnight.linkgraph.workbench.QaConversationSession,
    ): Map<String, Any?> = linkedMapOf(
        "sessionId" to session.sessionId,
        "scopeKey" to session.scopeKey,
        "messages" to session.messages.map(::qaConversationMessageToMap),
        "candidateChanges" to session.candidateChanges.map(::candidateDraftChangeToMap),
        "investigationThreads" to session.investigationThreads.map(::investigationThreadToMap),
        "turnOutcomes" to session.turnOutcomes.map(::investigationTurnOutcomeToMap),
        "focusTargetId" to session.focusTargetId,
    )

    /** 把生成计划讨论会话（消息列表与当前焦点项）转换为前端结构。 */
    internal fun generationPlanDiscussionSessionToMap(
        session: com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession,
        promptPreviewArtifactId: String?,
    ): Map<String, Any?> = linkedMapOf(
        "sessionId" to session.sessionId,
        "messages" to session.messages.map { message ->
            linkedMapOf(
                "messageId" to message.messageId,
                "role" to message.role.name,
                "content" to message.content,
                "focusItemId" to message.focusItemId,
            )
        },
        "focusItemId" to session.focusItemId,
        "promptPreviewArtifactId" to promptPreviewArtifactId,
    )

    /** 判断是否拥有可展示的 prompt 预览：文本或工件 ID 至少有一个非空即可。 */
    internal fun hasPromptPreview(promptPreview: String?, promptPreviewArtifactId: String?): Boolean {
        return !promptPreview.isNullOrBlank() || !promptPreviewArtifactId.isNullOrBlank()
    }

    /** 把草稿校验状态（状态、消息、未解决的调查线程）转换为前端结构。 */
    internal fun draftValidationStateToMap(
        state: com.charmnight.linkgraph.workbench.DraftValidationState,
    ): Map<String, Any?> = linkedMapOf(
        "status" to state.status.name,
        "message" to state.message,
        "detailMessage" to state.detailMessage,
        "unresolvedThreadIds" to state.unresolvedThreadIds,
        "unresolvedThreads" to state.unresolvedThreads.map(::investigationThreadToMap),
    )

    /** 把单条调查线程（含目标、证据缺口、推荐问题、解决状态）转换为前端结构。 */
    private fun investigationThreadToMap(
        thread: com.charmnight.linkgraph.workbench.InvestigationThread,
    ): Map<String, Any?> = linkedMapOf(
        "threadId" to thread.threadId,
        "status" to thread.status.name,
        "title" to thread.title,
        "targetStepIds" to thread.targetStepIds,
        "targetNodeIds" to thread.targetNodeIds,
        "summary" to thread.summary,
        "evidenceGap" to thread.evidenceGap,
        "recommendedQuestion" to thread.recommendedQuestion,
        "claimType" to thread.claimType,
        "evidence" to thread.evidence.map(::resultEvidenceFindingToMap),
        "latestTurnOutcomeId" to thread.latestTurnOutcomeId,
        "resolution" to thread.resolution?.let(::riskResolutionToMap),
    )

    /** 把风险线程的解决结果（状态与备注）转换为前端结构。 */
    private fun riskResolutionToMap(
        resolution: com.charmnight.linkgraph.workbench.RiskResolution,
    ): Map<String, Any?> = linkedMapOf(
        "threadId" to resolution.threadId,
        "status" to resolution.status.name,
        "note" to resolution.note,
    )

    /** 把单轮调查结果（含证据增量、观察到的节点/文件、阻塞原因）转换为前端结构。 */
    private fun investigationTurnOutcomeToMap(
        outcome: com.charmnight.linkgraph.workbench.InvestigationTurnOutcome,
    ): Map<String, Any?> = linkedMapOf(
        "outcomeId" to outcome.outcomeId,
        "threadId" to outcome.threadId,
        "status" to outcome.status.name,
        "summary" to outcome.summary,
        "detail" to outcome.detail,
        "candidateChangeId" to outcome.candidateChangeId,
        "blockedReason" to outcome.blockedReason,
        "evidenceDelta" to linkedMapOf(
            "addedNodeIds" to outcome.evidenceDelta.addedNodeIds,
            "addedFilePaths" to outcome.evidenceDelta.addedFilePaths,
            "previousStrongestEvidenceLevel" to outcome.evidenceDelta.previousStrongestEvidenceLevel?.name,
            "currentStrongestEvidenceLevel" to outcome.evidenceDelta.currentStrongestEvidenceLevel?.name,
            "hitRecommendedQuestion" to outcome.evidenceDelta.hitRecommendedQuestion,
        ),
        "observedNodeIds" to outcome.observedNodeIds,
        "observedFilePaths" to outcome.observedFilePaths,
        "strongestEvidenceLevel" to outcome.strongestEvidenceLevel?.name,
    )

    /** 把 QA 多轮对话中的单条消息转换为前端结构。 */
    private fun qaConversationMessageToMap(
        message: com.charmnight.linkgraph.workbench.QaConversationMessage,
    ): Map<String, Any?> = linkedMapOf(
        "messageId" to message.messageId,
        "role" to message.role.name,
        "content" to message.content,
        "focusTargetId" to message.focusTargetId,
        "turnOutcomeId" to message.turnOutcomeId,
    )

    /** 把源码片段上下文（行号区间、原始片段、反编译标记）转换为前端结构。 */
    private fun sourceSnippetContextToMap(
        snippet: com.charmnight.linkgraph.llm.SourceSnippetContext,
    ): Map<String, Any?> = linkedMapOf(
        "nodeId" to snippet.nodeId,
        "filePath" to snippet.filePath,
        "startOffset" to snippet.startOffset,
        "endOffset" to snippet.endOffset,
        "startLine" to snippet.startLine,
        "endLine" to snippet.endLine,
        "snippet" to snippet.snippet,
        "origin" to snippet.origin,
        "decompiled" to snippet.decompiled,
        "virtualFileUrl" to snippet.virtualFileUrl,
    )

    /** 把证据追踪条目（节点/文件/行号、是否纳入 prompt）转换为前端结构。 */
    private fun evidenceTraceEntryToMap(
        trace: com.charmnight.linkgraph.llm.EvidenceTraceEntry,
    ): Map<String, Any?> = linkedMapOf(
        "nodeId" to trace.nodeId,
        "resolvedNodeId" to trace.resolvedNodeId,
        "filePath" to trace.filePath,
        "reason" to trace.reason,
        "startLine" to trace.startLine,
        "endLine" to trace.endLine,
        "includedInPrompt" to trace.includedInPrompt,
        "mappingTrace" to trace.mappingTrace,
    )

    /** 把代码编辑作用域（目标符号、允许的变更类型）转换为前端结构。 */
    private fun editScopeToMap(
        scope: com.charmnight.linkgraph.llm.EditScope,
    ): Map<String, Any?> = linkedMapOf(
        "scopeId" to scope.scopeId,
        "targetNodeId" to scope.targetNodeId,
        "filePath" to scope.filePath,
        "language" to scope.language,
        "symbolKind" to scope.symbolKind,
        "symbolSignature" to scope.symbolSignature,
        "startOffset" to scope.startOffset,
        "endOffset" to scope.endOffset,
        "startLine" to scope.startLine,
        "endLine" to scope.endLine,
        "allowedChangeKinds" to scope.allowedChangeKinds,
        "supportingFindingIds" to scope.supportingFindingIds,
    )

    /** 把单条代码编辑操作（文件路径、作用域、操作类型、负载）转换为前端结构。 */
    private fun codeEditOperationToMap(
        operation: com.charmnight.linkgraph.codegen.CodeEditOperation,
    ): Map<String, Any?> = linkedMapOf(
        "operationId" to operation.operationId,
        "filePath" to operation.filePath,
        "scopeId" to operation.scopeId,
        "kind" to operation.kind.name,
        "payload" to operation.payload,
        "warnings" to operation.warnings,
    )

    /** 把准备好的代码编辑（带前后锚文本和符号签名）转换为前端结构。 */
    private fun preparedCodeEditToMap(
        edit: com.charmnight.linkgraph.codegen.PreparedCodeEdit,
    ): Map<String, Any?> = linkedMapOf(
        "operationId" to edit.operationId,
        "filePath" to edit.filePath,
        "scopeId" to edit.scopeId,
        "kind" to edit.kind.name,
        "targetSymbolSignature" to edit.targetSymbolSignature,
        "startOffset" to edit.startOffset,
        "endOffset" to edit.endOffset,
        "beforeText" to edit.beforeText,
        "afterText" to edit.afterText,
        "warnings" to edit.warnings,
    )

    /** 从节点元数据中提取 UI 坐标。 */
    /** uiPosition / semanticMetadata 已抽到 top-level（GraphEditorPageRendererHelpers.kt）。 */

}
