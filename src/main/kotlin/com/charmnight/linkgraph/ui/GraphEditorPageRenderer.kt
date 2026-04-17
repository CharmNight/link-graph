package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument

/**
 * 把项目状态序列化成前端可直接消费的 bootstrap JSON，并注入到入口 HTML。
 */
class GraphEditorPageRenderer {
    companion object {
        /** 允许完整内联的次级图层最大节点数。 */
        private const val MAX_SECONDARY_LAYER_SERIALIZED_NODES = 96
        /** 允许完整内联的次级图层最大边数。 */
        private const val MAX_SECONDARY_LAYER_SERIALIZED_EDGES = 144
        /** 兼容旧版前端事件协议时使用的默认会话 ID。 */
        private const val LEGACY_SESSION_ID = "legacy"
        /** 节点 x 坐标的元数据键。 */
        private const val UI_X_KEY = "ui.x"
        /** 节点 y 坐标的元数据键。 */
        private const val UI_Y_KEY = "ui.y"
        /** 需要从语义元数据中过滤掉的 UI 前缀。 */
        private const val UI_PREFIX = "ui."
        /** 需要从语义元数据中过滤掉的布局前缀。 */
        private const val LAYOUT_PREFIX = "layout."
    }

    /** 为旧版前端入口生成 bootstrap 脚本。 */
    fun bootstrapScript(snapshot: GraphEditorStateService.Snapshot): String {
        return bootstrapScript(
            sessionId = LEGACY_SESSION_ID,
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 为指定会话生成 bootstrap 脚本和自定义事件。 */
    fun bootstrapScript(
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
    ): String {
        return bootstrapScript(
            sessionId = sessionId,
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 为指定会话生成 bootstrap 脚本和自定义事件。 */
    fun bootstrapScript(
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): String {
        /** 当前快照序列化后的状态 JSON。 */
        val stateJson = bootstrapJson(snapshot, artifactRefs)
        /** 包含会话信息的外层事件载荷。 */
        val envelopeJson = encodeSnapshotEnvelopeJson(
            sessionId = sessionId,
            snapshot = snapshot,
            stateJson = stateJson,
        )
        return """
            window.linkGraphBootstrap = $stateJson;
            window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));
        """.trimIndent()
    }

    /** 用旧版会话协议把 bootstrap 脚本注入入口 HTML。 */
    fun render(
        entryHtml: String,
        snapshot: GraphEditorStateService.Snapshot,
    ): String {
        return render(
            entryHtml = entryHtml,
            sessionId = LEGACY_SESSION_ID,
            snapshot = snapshot,
        )
    }

    /** 把 bootstrap 脚本注入入口 HTML 的 `<head>` 中。 */
    fun render(
        entryHtml: String,
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
    ): String {
        return render(
            entryHtml = entryHtml,
            sessionId = sessionId,
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 把 bootstrap 脚本注入入口 HTML 的 `<head>` 中。 */
    fun render(
        entryHtml: String,
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): String {
        /** 注入页面的脚本标签内容。 */
        val bootstrapScript = """
            <script>
              ${bootstrapScript(sessionId = sessionId, snapshot = snapshot, artifactRefs = artifactRefs)}
            </script>
        """.trimIndent()
        return if (entryHtml.contains("</head>", ignoreCase = true)) {
            entryHtml.replace("</head>", "$bootstrapScript\n</head>", ignoreCase = true)
        } else {
            "$bootstrapScript\n$entryHtml"
        }
    }

    /** 直接返回前端所需的 bootstrap JSON。 */
    fun bootstrapJson(snapshot: GraphEditorStateService.Snapshot): String {
        return bootstrapJson(
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 直接返回前端所需的 bootstrap JSON。 */
    fun bootstrapJson(
        snapshot: GraphEditorStateService.Snapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): String {
        return encodeBootstrapJson(snapshot, artifactRefs)
    }

    /** 生成携带会话信息的外层 envelope JSON。 */
    private fun encodeSnapshotEnvelopeJson(
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
        stateJson: String,
    ): String {
        /** 发往前端事件的外层载荷。 */
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "revision" to snapshot.snapshotRevision,
            "state" to JsonRawValue(stateJson),
        )
        return toJson(payload)
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
    }

    /** 将完整编辑器快照编码成前端 bootstrap JSON。 */
    private fun encodeBootstrapJson(
        snapshot: GraphEditorStateService.Snapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
    ): String {
        return sanitizeJson(toJson(bootstrapPayload(snapshot, artifactRefs)))
    }

    /** 构建完整 bootstrap 状态载荷，供 init 与增量 slice 复用。 */
    internal fun bootstrapPayload(snapshot: GraphEditorStateService.Snapshot): LinkedHashMap<String, Any?> {
        return bootstrapPayload(
            snapshot = snapshot,
            artifactRefs = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
        )
    }

    /** 构建完整 bootstrap 状态载荷，供 init 与增量 slice 复用。 */
    internal fun bootstrapPayload(
        snapshot: GraphEditorStateService.Snapshot,
        artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts = GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY,
    ): LinkedHashMap<String, Any?> {
        /** 从状态服务快照中整理出的编辑器快照。 */
        val editorSnapshot = snapshot.editorSnapshot()
        /** 当前前端主视图展示的图。 */
        val visibleGraph = editorSnapshot.visibleGraph
        /** 当前工作图。 */
        val workingGraph = editorSnapshot.workingGraph
        /** 事实图基线。 */
        val referenceFactGraph = editorSnapshot.referenceFactGraph
        /** 设计基线图。 */
        val designBaselineGraph = editorSnapshot.designBaselineGraph
        /** 整体 bootstrap 载荷。 */
        val payload = linkedMapOf<String, Any?>(
            "analysisDisplayMode" to snapshot.analysisDisplayMode.name,
            "visibleGraph" to documentToMap(visibleGraph, includeFullContent = true, layoutState = snapshot.layoutState),
            "workingGraph" to documentToMap(workingGraph, includeFullContent = true, layoutState = editorSnapshot.layoutState),
            "referenceFactGraph" to referenceFactGraph?.let {
                documentToMap(it, includeFullContent = false, layoutState = editorSnapshot.layoutState)
            },
            "designBaselineGraph" to designBaselineGraph?.let {
                documentToMap(it, includeFullContent = false, layoutState = editorSnapshot.layoutState)
            },
            "factGraphView" to editorSnapshot.factGraphView?.let {
                factGraphViewToMap(it, editorSnapshot.layoutState)
            },
            "flowchartView" to editorSnapshot.flowchartView?.let {
                flowchartViewToMap(it, editorSnapshot.layoutState)
            },
            "resourceRelationView" to editorSnapshot.resourceRelationView?.let {
                resourceRelationViewToMap(it, editorSnapshot.layoutState)
            },
            "draftPatchPreview" to snapshot.draftPatchPreview?.let(::patchToMap),
            "draftWorkbenchState" to draftWorkbenchStateToMap(snapshot.draftWorkbenchState),
            "canUndoDraftPatchApply" to (snapshot.draftPatchUndoState != null),
            "lastAppliedDraftPatchSummary" to snapshot.draftPatchUndoState?.patchPreview?.summary,
            "lastDraftPatchApplyResult" to snapshot.lastDraftPatchApplyResult?.let(::draftPatchApplyResultToMap),
            "auditResult" to snapshot.auditResult?.let {
                patchResultToMap(it, artifactRefs.auditPromptPreviewArtifactId)
            },
            "auditRequestState" to requestStateToMap(snapshot.auditRequestState),
            "runtimeArtifactSummaries" to snapshot.runtimeArtifactSummaries.mapValues { (_, summaries) ->
                summaries.map { summary ->
                    linkedMapOf(
                        "artifactId" to summary.artifactId,
                        "artifactType" to summary.artifactType,
                        "title" to summary.title,
                        "description" to summary.description,
                    )
                }
            },
            "diffReviewResult" to snapshot.diffReviewResult?.let {
                patchResultToMap(it, artifactRefs.diffReviewPromptPreviewArtifactId)
            },
            "diffReviewRequestState" to requestStateToMap(snapshot.diffReviewRequestState),
            "graphBeautificationResult" to snapshot.graphBeautificationResult?.let {
                beautificationResultToMap(it, artifactRefs.beautificationPromptPreviewArtifactId)
            },
            "graphBeautificationRequestState" to requestStateToMap(snapshot.graphBeautificationRequestState),
            "mermaidIssues" to snapshot.mermaidIssues.map { issue ->
                linkedMapOf(
                    "category" to issue.category.name,
                    "code" to issue.code,
                    "message" to issue.message,
                    "line" to issue.line,
                    "nodeId" to issue.nodeId,
                    "edgeId" to issue.edgeId,
                )
            },
            "diffItems" to snapshot.diff?.entries.orEmpty().map { entry ->
                linkedMapOf(
                    "id" to entry.elementId,
                    "title" to resolveDiffTitle(entry, workingGraph),
                    "status" to entry.status.name,
                    "description" to (entry.message ?: entry.fields.joinToString()),
                )
            },
            "syncPreviewItems" to snapshot.syncPreviewItems.map { item ->
                linkedMapOf(
                    "id" to item.id,
                    "title" to item.title,
                    "description" to item.description,
                    "risk" to item.risk.name,
                )
            },
            "generationPlan" to snapshot.generationPlan?.let { plan ->
                linkedMapOf(
                    "source" to plan.source.name,
                    "summary" to plan.summary,
                    "warnings" to plan.warnings,
                    "promptPreviewArtifactId" to artifactRefs.generationPlanPromptPreviewArtifactId,
                    "items" to plan.items.map { item ->
                        linkedMapOf(
                            "id" to item.id,
                            "title" to item.title,
                            "description" to item.description,
                            "risk" to item.risk.name,
                            "targetPath" to item.targetPath,
                            "editScopes" to item.editScopes.map(::editScopeToMap),
                        )
                    },
                )
            },
            "generationPlanRequestState" to requestStateToMap(snapshot.generationPlanRequestState),
            "generatedCodeDrafts" to snapshot.generatedCodeDrafts.map { draft ->
                val contentArtifactId = artifactRefs.generatedCodeDraftContentArtifactIds[draft.id]
                linkedMapOf<String, Any?>(
                    "id" to draft.id,
                    "sourceNodeId" to draft.sourceNodeId,
                    "title" to draft.title,
                    "targetPath" to draft.targetPath,
                    "contentArtifactId" to contentArtifactId,
                    "editOperations" to draft.editOperations.map(::codeEditOperationToMap),
                    "editScopes" to draft.editScopes.map(::editScopeToMap),
                    "warnings" to draft.warnings,
                ).apply {
                    if (contentArtifactId == null && draft.content != null) {
                        put("content", draft.content)
                    }
                }
            },
            "generatedCodeDraftWarnings" to snapshot.generatedCodeDraftWarnings,
            "generatedCodeDraftSource" to snapshot.generatedCodeDraftSource?.name,
            "generatedCodeDraftPromptPreviewArtifactId" to artifactRefs.generatedCodeDraftPromptPreviewArtifactId,
            "codeDraftRequestState" to requestStateToMap(snapshot.codeDraftRequestState),
            "generatedCodeDraftWriteReport" to snapshot.generatedCodeDraftWriteReport?.let { report ->
                linkedMapOf(
                    "writtenFiles" to report.writtenFiles,
                    "skippedFiles" to report.skippedFiles,
                    "warnings" to report.warnings,
                )
            },
            "layoutState" to linkedMapOf(
                "positions" to editorSnapshot.layoutState.positions.mapValues { (_, position) ->
                    linkedMapOf(
                        "x" to position.x,
                        "y" to position.y,
                    )
                },
            ),
            "semanticRevision" to editorSnapshot.semanticRevision,
            "layoutRevision" to editorSnapshot.layoutRevision,
            "snapshotRevision" to editorSnapshot.snapshotRevision,
            "selectedNodeId" to (snapshot.selectedNodeId ?: visibleGraph.nodes.firstOrNull()?.id),
            "sourceNavigationState" to sourceNavigationStateToMap(snapshot.sourceNavigationState),
            "workbenchSectionPreferences" to LinkedHashMap(snapshot.workbenchSectionPreferences),
            "lastMessageType" to snapshot.lastMessageType,
            "lastGraphSource" to snapshot.lastGraphSource,
            "operationFeedback" to snapshot.operationFeedback?.let { feedback ->
                linkedMapOf(
                    "level" to feedback.level.name,
                    "message" to feedback.message,
                )
            },
        )
        return payload
    }

    /** 把异步请求状态转换成前端可消费的映射。 */
    private fun requestStateToMap(state: GraphEditorStateService.AsyncRequestState): Map<String, Any?> = linkedMapOf(
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
        "promptPreviewAvailable" to state.promptPreviewAvailable,
    )

    /** 把源码跳转状态转换成前端可消费的映射。 */
    private fun sourceNavigationStateToMap(
        state: GraphEditorStateService.SourceNavigationState,
    ): Map<String, Any?> = linkedMapOf(
        "nodeId" to state.nodeId,
        "phase" to state.phase.name,
        "result" to state.result?.name,
        "targetPath" to state.targetPath,
        "line" to state.line,
        "column" to state.column,
        "errorMessage" to state.errorMessage,
    )

    /** 为 diff 项解析可读标题。 */
    private fun resolveDiffTitle(
        entry: com.charmnight.linkgraph.model.GraphDiffEntry,
        document: GraphDocument,
    ): String {
        return when (entry.elementKind) {
            GraphDiffElementKind.NODE -> document.nodes.firstOrNull { it.id == entry.elementId }?.title ?: entry.elementId
            GraphDiffElementKind.EDGE -> entry.elementId
        }
    }

    /** 把节点转换为前端使用的 Map 结构。 */
    private fun nodeToMap(
        node: GraphNode,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = linkedMapOf(
        "id" to node.id,
        "type" to node.type.name,
        "title" to node.title,
        "location" to node.location,
        "signature" to node.signature,
        "inputs" to node.inputs,
        "outputs" to node.outputs,
        "doc" to node.doc,
        "certainty" to node.certainty.name,
        "bindingStatus" to node.bindingStatus.name,
        "diffStatus" to node.diff.status.takeUnless { it.name == "MATCHED" }?.name,
        "sourceTag" to node.sourceTag.name,
        "metadata" to node.metadata.semanticMetadata(),
        "position" to (layoutState?.positions?.get(node.id)?.let { it.x to it.y } ?: node.metadata.uiPosition())?.let { position ->
            linkedMapOf(
                "x" to position.first,
                "y" to position.second,
            )
        },
    )

    /** 把边转换为前端使用的 Map 结构。 */
    private fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
        "id" to edge.id,
        "type" to edge.type.name,
        "source" to edge.fromNodeId,
        "target" to edge.toNodeId,
        "label" to edge.label,
        "metadata" to edge.metadata,
        "sourceTag" to edge.sourceTag.name,
    )

    /** 把图文档转换为前端使用的 Map，并按规模决定是否裁剪内容。 */
    private fun documentToMap(
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
    private fun factGraphViewToMap(
        document: FactGraphViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        summary = linkedMapOf(
            "anchorTitle" to document.summary.anchorTitle,
            "visibleNodeCount" to document.summary.visibleNodeCount,
            "fullNodeCount" to document.summary.fullNodeCount,
        ),
        layoutState = layoutState,
    )

    /** 把流程图视图文档转换为前端使用的 Map。 */
    private fun flowchartViewToMap(
        document: FlowchartViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
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
    private fun resourceRelationViewToMap(
        document: ResourceRelationViewDocument,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = viewDocumentToMap(
        visibleGraph = document.visibleGraph,
        fullGraph = document.fullGraph,
        anchorNodeId = document.anchorNodeId,
        summary = linkedMapOf(
            "visibleNodeCount" to document.summary.visibleNodeCount,
            "laneCounts" to document.summary.laneCounts,
        ),
        layoutState = layoutState,
    )

    /** 把三视图通用视图文档转换为前端使用的 Map。 */
    private fun viewDocumentToMap(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
        summary: Map<String, Any?>,
        layoutState: GraphLayoutState? = null,
    ): Map<String, Any?> = linkedMapOf(
        "visibleGraph" to documentToMap(visibleGraph, includeFullContent = true, layoutState = layoutState),
        "fullGraph" to documentToMap(fullGraph, includeFullContent = false, layoutState = layoutState),
        "anchorNodeId" to anchorNodeId,
        "summary" to summary,
    )

    /** 把图补丁转换为前端使用的 Map 结构。 */
    private fun patchToMap(patch: GraphPatch): Map<String, Any?> = linkedMapOf(
        "summary" to patch.summary,
        "operations" to patch.operations.map(::patchOperationToMap),
        "addedNodeIds" to patch.addedNodeIds,
        "removedNodeIds" to patch.removedNodeIds,
        "addedEdgeIds" to patch.addedEdgeIds,
        "removedEdgeIds" to patch.removedEdgeIds,
    )

    /** 把单条补丁操作转换为前端使用的 Map 结构。 */
    private fun patchOperationToMap(operation: GraphPatchOperation): Map<String, Any?> = linkedMapOf(
        "id" to operation.id,
        "action" to operation.action.name,
        "elementKind" to operation.elementKind.name,
        "elementId" to operation.elementId,
        "title" to operation.title,
        "summary" to operation.summary,
        "node" to operation.node?.let(::nodeToMap),
        "edge" to operation.edge?.let(::edgeToMap),
        "metadata" to operation.metadata,
    )

    /** 把补丁类结果转换为前端使用的 Map 结构。 */
    private fun patchResultToMap(
        result: GraphPatchResult,
        promptPreviewArtifactId: String?,
    ): Map<String, Any?> = linkedMapOf(
        "source" to result.source.name,
        "question" to result.question,
        "answer" to result.answer,
        "promptPreviewArtifactId" to promptPreviewArtifactId,
        "warnings" to result.warnings,
        "findings" to result.findings.map(::resultEvidenceFindingToMap),
        "candidateChanges" to result.candidateChanges.map(::candidateDraftChangeToMap),
        "newCandidateChanges" to result.newCandidateChanges.map(::candidateDraftChangeToMap),
        "investigationLeads" to result.investigationLeads.map(::auditInvestigationLeadToMap),
        "newInvestigationLeads" to result.newInvestigationLeads.map(::auditInvestigationLeadToMap),
        "sourceContext" to result.sourceContext.map(::sourceSnippetContextToMap),
        "evidenceTrace" to result.evidenceTrace.map(::evidenceTraceEntryToMap),
        "auditSession" to result.auditSession?.let(::auditConversationSessionToMap),
        "patch" to result.patch?.let(::patchToMap),
    )

    /** 把链路讲解结果转换为前端使用的 Map 结构。 */
    private fun beautificationResultToMap(
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
    private fun resultEvidenceFindingToMap(finding: com.charmnight.linkgraph.llm.ResultEvidenceFinding): Map<String, Any?> = linkedMapOf(
        "id" to finding.id,
        "claim" to finding.claim,
        "evidenceLevel" to finding.evidenceLevel.name,
        "references" to finding.references.map { reference ->
            linkedMapOf(
                "nodeId" to reference.nodeId,
                "filePath" to reference.filePath,
                "startLine" to reference.startLine,
                "endLine" to reference.endLine,
            )
        },
    )

    /** 把草稿补丁应用结果转换为前端使用的 Map 结构。 */
    private fun draftPatchApplyResultToMap(result: DraftPatchApplyResult): Map<String, Any?> = linkedMapOf(
        "summary" to result.summary,
        "appliedOperationCount" to result.appliedOperationCount,
        "appliedNodeIds" to result.appliedNodeIds,
        "appliedEdgeIds" to result.appliedEdgeIds,
        "focusNodeId" to result.focusNodeId,
        "appliedTargets" to result.appliedTargets,
    )

    private fun draftWorkbenchStateToMap(
        state: com.charmnight.linkgraph.workbench.DraftWorkbenchState,
    ): Map<String, Any?> = linkedMapOf(
        "draftChanges" to state.draftChanges.map(::draftWorkbenchEntryToMap),
        "draftNotes" to state.draftNotes.map(::draftWorkbenchEntryToMap),
    )

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
    )

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
    )

    private fun auditConversationSessionToMap(
        session: com.charmnight.linkgraph.workbench.AuditConversationSession,
    ): Map<String, Any?> = linkedMapOf(
        "sessionId" to session.sessionId,
        "scopeKey" to session.scopeKey,
        "messages" to session.messages.map(::auditConversationMessageToMap),
        "candidateChanges" to session.candidateChanges.map(::candidateDraftChangeToMap),
        "investigationLeads" to session.investigationLeads.map(::auditInvestigationLeadToMap),
        "focusTargetId" to session.focusTargetId,
    )

    private fun auditInvestigationLeadToMap(
        lead: com.charmnight.linkgraph.workbench.AuditInvestigationLead,
    ): Map<String, Any?> = linkedMapOf(
        "leadId" to lead.leadId,
        "status" to lead.status.name,
        "title" to lead.title,
        "targetStepIds" to lead.targetStepIds,
        "targetNodeIds" to lead.targetNodeIds,
        "summary" to lead.summary,
        "evidenceGap" to lead.evidenceGap,
        "recommendedQuestion" to lead.recommendedQuestion,
        "claimType" to lead.claimType,
        "evidence" to lead.evidence.map(::resultEvidenceFindingToMap),
    )

    private fun auditConversationMessageToMap(
        message: com.charmnight.linkgraph.workbench.AuditConversationMessage,
    ): Map<String, Any?> = linkedMapOf(
        "messageId" to message.messageId,
        "role" to message.role.name,
        "content" to message.content,
        "focusTargetId" to message.focusTargetId,
    )

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
    )

    private fun evidenceTraceEntryToMap(
        trace: com.charmnight.linkgraph.llm.EvidenceTraceEntry,
    ): Map<String, Any?> = linkedMapOf(
        "nodeId" to trace.nodeId,
        "filePath" to trace.filePath,
        "reason" to trace.reason,
        "startLine" to trace.startLine,
        "endLine" to trace.endLine,
        "includedInPrompt" to trace.includedInPrompt,
    )

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

    /** 表示已经编码好的原始 JSON 片段，写出时不再做字符串转义。 */
    private data class JsonRawValue(
        /** 已经编码好的 JSON 文本。 */
        val encoded: String,
    )

    /** 将任意支持的数据结构编码成 JSON 文本。 */
    internal fun toJson(value: Any?): String {
        return buildString {
            appendJsonValue(this, value)
        }
    }

    internal fun sanitizeJson(encoded: String): String {
        return encoded
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
    }

    /** 递归把单个值写入 JSON 构建器。 */
    private fun appendJsonValue(
        builder: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> builder.append("null")
            is JsonRawValue -> builder.append(value.encoded)
            is String -> builder.append('"').append(escape(value)).append('"')
            is Boolean, is Int, is Long -> builder.append(value.toString())
            is Float -> builder.append(formatNumber(value.toDouble()))
            is Double -> builder.append(formatNumber(value))
            is Number -> builder.append(formatNumber(value.toDouble()))
            is Map<*, *> -> {
                builder.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    builder.append('"').append(escape(entry.key.toString())).append('"').append(':')
                    appendJsonValue(builder, entry.value)
                }
                builder.append('}')
            }

            is Iterable<*> -> {
                builder.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    appendJsonValue(builder, item)
                }
                builder.append(']')
            }

            else -> builder.append('"').append(escape(value.toString())).append('"')
        }
    }

    /** 对字符串做 JSON 转义。 */
    private fun escape(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    /** 以尽量紧凑的形式格式化数值。 */
    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            return "null"
        }
        if (value % 1.0 == 0.0) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    /** 从节点元数据中提取 UI 坐标。 */
    private fun Map<String, String>.uiPosition(): Pair<Double, Double>? {
        /** 节点 x 坐标。 */
        val x = this[UI_X_KEY]?.toDoubleOrNull() ?: return null
        /** 节点 y 坐标。 */
        val y = this[UI_Y_KEY]?.toDoubleOrNull() ?: return null
        return x to y
    }

    /** 过滤掉纯 UI 布局相关元数据，只保留语义元数据。 */
    private fun Map<String, String>?.semanticMetadata(): Map<String, String>? {
        if (this == null) {
            return null
        }
        /** 排除 UI 与布局键后的剩余元数据。 */
        val filtered = this.filterKeys { key ->
            !key.startsWith(UI_PREFIX) && !key.startsWith(LAYOUT_PREFIX)
        }
        return filtered.ifEmpty { null }
    }
}
