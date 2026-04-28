package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphJson
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.services.debugLazy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class GraphBrowserBridgeRegistrar(
    browser: JBCefBrowser,
    private val bridge: GraphEditorBridge,
    private val logger: Logger,
    private val debugTracingEnabled: Boolean,
    private val interactionProbeEnabled: Boolean,
    private val dispatchArtifactSlice: (List<String>) -> Unit,
    private val dispatchBridgeAsync: (String, () -> GraphEditorMessage) -> Unit,
    private val shouldLogFrontendTrace: (String) -> Boolean,
    private val runtimeTrace: (String) -> Unit,
) {
    private val importMermaidQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val exportMermaidQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val showDiffModeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestSyncPreviewQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestAuditQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val retryLastAuditRequestQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val confirmAuditCandidateChangeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val unconfirmAuditCandidateChangeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val resolveInvestigationThreadQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestDiffReviewQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestGraphBeautificationQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val applyDraftPatchPreviewQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val clearDraftPatchPreviewQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val restoreDraftPatchPreviewQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val undoLastDraftPatchApplyQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestGenerationPlanQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestGenerationPlanDiscussionQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestCodeDraftsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestCurrentEditorContextGraphQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestAnalysisDisplayModeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val updateWorkbenchSectionPreferenceQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestOpenSettingsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val applyCodeDraftsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val applySingleCodeDraftQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openCodeDraftNativeDiffQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestDraftNavigationQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestArtifactQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val applyGraphEditScriptQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val frontendReadyQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val snapshotAckQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val layoutChangedQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val nodeSelectedQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestSourceNavigationQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val requestExpandOverflowNodeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val debugTraceQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    fun registerHandlers() {
        importMermaidQuery.addHandler { mermaid ->
            bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
            JBCefJSQuery.Response("ok")
        }
        exportMermaidQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.ExportMermaid)
            JBCefJSQuery.Response("ok")
        }
        showDiffModeQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.ShowDiffMode)
            JBCefJSQuery.Response("ok")
        }
        requestSyncPreviewQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.RequestSyncPreview)
            JBCefJSQuery.Response("ok")
        }
        requestAuditQuery.addHandler { payload ->
            val request = GraphBrowserPayloadParser.parseAuditRequestPayload(payload)
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：问答, question=${summarizePayloadText(request.question)}, selectedNodeIds=${request.selectedNodeIds}, sourceThreadId=${request.sourceThreadId}"
            }
            bridge.dispatch(
                GraphEditorMessage.RequestAudit(
                    question = request.question,
                    selectedNodeIds = request.selectedNodeIds,
                    sourceThreadId = request.sourceThreadId,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        retryLastAuditRequestQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.RetryLastAuditRequest)
            JBCefJSQuery.Response("ok")
        }
        confirmAuditCandidateChangeQuery.addHandler { payload ->
            bridge.dispatch(GraphEditorMessage.ConfirmAuditCandidateChange(changeId = payload))
            JBCefJSQuery.Response("ok")
        }
        unconfirmAuditCandidateChangeQuery.addHandler { payload ->
            bridge.dispatch(GraphEditorMessage.UnconfirmAuditCandidateChange(changeId = payload))
            JBCefJSQuery.Response("ok")
        }
        resolveInvestigationThreadQuery.addHandler { payload ->
            runCatching {
                val request = GraphBrowserPayloadParser.parseResolveInvestigationThreadPayload(payload)
                bridge.dispatch(
                    GraphEditorMessage.ResolveInvestigationThread(
                        threadId = request.threadId,
                        resolutionStatus = request.resolutionStatus,
                        note = request.note,
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "风险决策提交失败")
            }
        }
        requestDiffReviewQuery.addHandler { payload ->
            val (question, selectedDiffItemIds) = GraphBrowserPayloadParser.parseQuestionWithIds(payload)
            bridge.dispatch(
                GraphEditorMessage.RequestDiffReview(
                    question = question,
                    selectedDiffItemIds = selectedDiffItemIds,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        requestGraphBeautificationQuery.addHandler { payload ->
            val request = GraphBrowserPayloadParser.parseBeautificationPayload(payload)
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：链路讲解, goal=${summarizePayloadText(request.goal)}, preferredStyle=${summarizePayloadText(request.preferredStyle)}, " +
                    "explanationFocus=${summarizePayloadText(request.explanationFocus)}, followUpStepId=${summarizePayloadText(request.followUp?.stepId)}, granularity=${request.granularity}"
            }
            bridge.dispatch(
                GraphEditorMessage.RequestGraphBeautification(
                    goal = request.goal,
                    preferredStyle = request.preferredStyle,
                    explanationFocus = request.explanationFocus,
                    followUp = request.followUp,
                    granularity = request.granularity,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        applyDraftPatchPreviewQuery.addHandler { payload ->
            val operationIds = GraphBrowserPayloadParser.parseEncodedList(payload).toSet().takeIf { it.isNotEmpty() }
            bridge.dispatch(GraphEditorMessage.ApplyDraftPatchPreview(operationIds))
            JBCefJSQuery.Response("ok")
        }
        clearDraftPatchPreviewQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.ClearDraftPatchPreview)
            JBCefJSQuery.Response("ok")
        }
        restoreDraftPatchPreviewQuery.addHandler { payload ->
            bridge.dispatch(
                GraphEditorMessage.RestoreDraftPatchPreview(
                    GraphEditorMessage.DraftPatchPreviewSource.valueOf(payload),
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        undoLastDraftPatchApplyQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.UndoLastDraftPatchApply)
            JBCefJSQuery.Response("ok")
        }
        requestGenerationPlanQuery.addHandler {
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：生成实现计划, 当前快照=${snapshotSummary(bridge.currentState())}"
            }
            bridge.dispatch(GraphEditorMessage.RequestGenerationPlan)
            JBCefJSQuery.Response("ok")
        }
        requestGenerationPlanDiscussionQuery.addHandler { payload ->
            val request = GraphBrowserPayloadParser.parseGenerationPlanDiscussionPayload(payload)
            bridge.dispatch(
                GraphEditorMessage.RequestGenerationPlanDiscussion(
                    question = request.question,
                    focusItemId = request.focusItemId,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        requestCodeDraftsQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
            JBCefJSQuery.Response("ok")
        }
        requestCurrentEditorContextGraphQuery.addHandler {
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：加载当前编辑器上下文链路, 当前快照=${snapshotSummary(bridge.currentState())}"
            }
            bridge.dispatch(GraphEditorMessage.RequestCurrentEditorContextGraph)
            JBCefJSQuery.Response("ok")
        }
        requestAnalysisDisplayModeQuery.addHandler { payload ->
            runCatching {
                bridge.dispatch(
                    GraphEditorMessage.RequestAnalysisDisplayMode(
                        AnalysisDisplayMode.valueOf(payload),
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "切换展示模式失败")
            }
        }
        updateWorkbenchSectionPreferenceQuery.addHandler { payload ->
            runCatching {
                val parts = payload.split('\u001f')
                val sectionId = URLDecoder.decode(parts.getOrNull(0).orEmpty(), StandardCharsets.UTF_8)
                val expanded = parts.getOrNull(1) == "1"
                bridge.dispatch(
                    GraphEditorMessage.UpdateWorkbenchSectionPreference(
                        sectionId = sectionId,
                        expanded = expanded,
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "更新工作台偏好失败")
            }
        }
        requestOpenSettingsQuery.addHandler {
            bridge.dispatch(GraphEditorMessage.OpenSettings)
            JBCefJSQuery.Response("ok")
        }
        applyCodeDraftsQuery.addHandler {
            dispatchBridgeAsync("写入全部代码草稿") {
                GraphEditorMessage.ApplyCodeDrafts
            }
            JBCefJSQuery.Response("ok")
        }
        applySingleCodeDraftQuery.addHandler { draftId ->
            dispatchBridgeAsync("写入单个代码草稿") {
                GraphEditorMessage.ApplySingleCodeDraft(draftId)
            }
            JBCefJSQuery.Response("ok")
        }
        openCodeDraftNativeDiffQuery.addHandler { draftId ->
            dispatchBridgeAsync("打开代码草稿原生 Diff") {
                GraphEditorMessage.OpenCodeDraftNativeDiff(draftId)
            }
            JBCefJSQuery.Response("ok")
        }
        requestDraftNavigationQuery.addHandler { targetPath ->
            bridge.dispatch(GraphEditorMessage.RequestDraftNavigation(targetPath))
            JBCefJSQuery.Response("ok")
        }
        requestArtifactQuery.addHandler { payload ->
            runCatching {
                dispatchArtifactSlice(GraphBrowserPayloadParser.parseEncodedList(payload))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "artifact 请求失败")
            }
        }
        nodeSelectedQuery.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.NodeSelected(nodeId))
            JBCefJSQuery.Response("ok")
        }
        requestSourceNavigationQuery.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.RequestSourceNavigation(nodeId))
            JBCefJSQuery.Response("ok")
        }
        requestExpandOverflowNodeQuery.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.RequestExpandOverflowNode(nodeId))
            JBCefJSQuery.Response("ok")
        }
        applyGraphEditScriptQuery.addHandler { payload ->
            runCatching {
                val script = GraphBrowserPayloadParser.parseGraphEditScript(payload)
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "收到前端 applyGraphEditScript: sceneId=${script.sceneId}, baseWorkspaceRevision=${script.baseWorkspaceRevision}, operationCount=${script.operations.size}"
                }
                bridge.dispatch(GraphEditorMessage.ApplyGraphEditScript(script))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "链路图编辑脚本同步失败")
            }
        }
        frontendReadyQuery.addHandler { payload ->
            runCatching {
                bridge.dispatch(
                    GraphEditorMessage.FrontendReady(
                        lastAppliedRevision = GraphBrowserPayloadParser.parseNullableRevision(payload),
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "前端 ready 握手失败")
            }
        }
        snapshotAckQuery.addHandler { payload ->
            runCatching {
                bridge.dispatch(
                    GraphEditorMessage.SnapshotAck(
                        revision = payload.toLongOrNull() ?: error("无效的 snapshot revision: $payload"),
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "快照确认失败")
            }
        }
        layoutChangedQuery.addHandler { payload ->
            runCatching {
                bridge.dispatch(GraphEditorMessage.LayoutChanged(GraphBrowserPayloadParser.parseLayoutPositions(payload)))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "链路图布局同步失败")
            }
        }
        debugTraceQuery.addHandler { payload ->
            if (shouldLogFrontendTrace(payload)) {
                runtimeTrace("前端 trace: $payload")
                debugLazy(logger.isDebugEnabled, logger::debug) { "前端 trace: $payload" }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    fun buildBridgeScript(): String {
        val debugBridgeScript = if (debugTracingEnabled) {
            """
            window.__linkGraphDebugEnabled = true;
            window.__linkGraphInteractionProbe = ${if (interactionProbeEnabled) "true" else "false"};
            window.__linkGraphTraceBuffer = Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer : [];
            window.linkGraphDebugTrace = (payload) => { ${debugTraceQuery.inject("payload")} };
            if (Array.isArray(window.__linkGraphTraceBuffer) && window.__linkGraphTraceBuffer.length > 0) {
              window.__linkGraphTraceBuffer.forEach((payload) => window.linkGraphDebugTrace(payload));
              window.__linkGraphTraceBuffer = [];
            }
            """.trimIndent()
        } else {
            """
            window.__linkGraphDebugEnabled = false;
            window.__linkGraphInteractionProbe = false;
            window.__linkGraphTraceBuffer = [];
            """.trimIndent()
        }
        return """
            ${if (debugTracingEnabled) """console.log("link-graph bridge 注入开始");""" else ""}
            $debugBridgeScript
            window.linkGraphBridge = {
              importMermaid: (mermaid) => { ${importMermaidQuery.inject("mermaid")} },
              exportMermaid: () => { ${exportMermaidQuery.inject("'exportMermaid'")} },
              showDiffMode: () => { ${showDiffModeQuery.inject("'showDiffMode'")} },
              requestSyncPreview: () => { ${requestSyncPreviewQuery.inject("'requestSyncPreview'")} },
              requestAudit: (question, selectedNodeIds, sourceThreadId) => { ${requestAuditQuery.inject("[(question ? encodeURIComponent(question) : ''), ((selectedNodeIds || []).map((value) => encodeURIComponent(value)).join(',')), (sourceThreadId ? encodeURIComponent(sourceThreadId) : '')].join('\\u001f')")} },
              retryLastAuditRequest: () => { ${retryLastAuditRequestQuery.inject("'retryLastAuditRequest'")} },
              confirmAuditCandidateChange: (changeId) => { ${confirmAuditCandidateChangeQuery.inject("changeId")} },
              unconfirmAuditCandidateChange: (changeId) => { ${unconfirmAuditCandidateChangeQuery.inject("changeId")} },
              resolveInvestigationThread: (threadId, resolutionStatus, note) => { ${resolveInvestigationThreadQuery.inject("[(threadId ? encodeURIComponent(threadId) : ''), (resolutionStatus ? encodeURIComponent(resolutionStatus) : ''), (note ? encodeURIComponent(note) : '')].join('\\u001f')")} },
              requestDiffReview: (question, selectedDiffItemIds) => { ${requestDiffReviewQuery.inject("[(question ? encodeURIComponent(question) : ''), ((selectedDiffItemIds || []).map((value) => encodeURIComponent(value)).join(','))].join('\\u001f')")} },
              requestGraphBeautification: (goal, preferredStyle, explanationFocus, granularity, followUpStepId, followUpStepTitle, followUpQuestion) => { ${requestGraphBeautificationQuery.inject("[(goal ? encodeURIComponent(goal) : ''), (preferredStyle ? encodeURIComponent(preferredStyle) : ''), (explanationFocus ? encodeURIComponent(explanationFocus) : ''), (granularity ? encodeURIComponent(granularity) : ''), (followUpStepId ? encodeURIComponent(followUpStepId) : ''), (followUpStepTitle ? encodeURIComponent(followUpStepTitle) : ''), (followUpQuestion ? encodeURIComponent(followUpQuestion) : '')].join('\\u001f')")} },
              applyDraftPatchPreview: (operationIds) => { ${applyDraftPatchPreviewQuery.inject("((operationIds || []).map((value) => encodeURIComponent(value)).join(','))")} },
              clearDraftPatchPreview: () => { ${clearDraftPatchPreviewQuery.inject("'clearDraftPatchPreview'")} },
              restoreDraftPatchPreview: (source) => { ${restoreDraftPatchPreviewQuery.inject("source")} },
              undoLastDraftPatchApply: () => { ${undoLastDraftPatchApplyQuery.inject("'undoLastDraftPatchApply'")} },
              requestGenerationPlan: () => { ${requestGenerationPlanQuery.inject("'requestGenerationPlan'")} },
              requestGenerationPlanDiscussion: (question, focusItemId) => { ${requestGenerationPlanDiscussionQuery.inject("[(question ? encodeURIComponent(question) : ''), (focusItemId ? encodeURIComponent(focusItemId) : '')].join('\\u001f')")} },
              requestCodeDrafts: () => { ${requestCodeDraftsQuery.inject("'requestCodeDrafts'")} },
              requestCurrentEditorContextGraph: () => { ${requestCurrentEditorContextGraphQuery.inject("'requestCurrentEditorContextGraph'")} },
              requestAnalysisDisplayMode: (displayMode) => { ${requestAnalysisDisplayModeQuery.inject("displayMode")} },
              updateWorkbenchSectionPreference: (sectionId, expanded) => { ${updateWorkbenchSectionPreferenceQuery.inject("[(sectionId ? encodeURIComponent(sectionId) : ''), (expanded ? '1' : '0')].join('\\u001f')")} },
              requestOpenSettings: () => { ${requestOpenSettingsQuery.inject("'requestOpenSettings'")} },
              applyCodeDrafts: () => { ${applyCodeDraftsQuery.inject("'applyCodeDrafts'")} },
              applySingleCodeDraft: (draftId) => { ${applySingleCodeDraftQuery.inject("draftId")} },
              openCodeDraftNativeDiff: (draftId) => { ${openCodeDraftNativeDiffQuery.inject("draftId")} },
              requestDraftNavigation: (targetPath) => { ${requestDraftNavigationQuery.inject("targetPath")} },
              requestArtifact: (artifactIds) => { ${requestArtifactQuery.inject("((artifactIds || []).map((value) => encodeURIComponent(value)).join(','))")} },
              frontendReady: (payload) => { ${frontendReadyQuery.inject("payload && Number.isFinite(payload.lastAppliedRevision) ? String(payload.lastAppliedRevision) : ''")} },
              snapshotAck: (payload) => { ${snapshotAckQuery.inject("payload && Number.isFinite(payload.revision) ? String(payload.revision) : ''")} },
              nodeSelected: (nodeId) => { ${nodeSelectedQuery.inject("nodeId")} },
              layoutChanged: (payload) => { ${layoutChangedQuery.inject("((payload && Array.isArray(payload.positions) ? payload.positions : []).map((item) => [encodeURIComponent(item.nodeId), item.x, item.y].join('\\u001f')).join('\\u001e'))")} },
              requestSourceNavigation: (nodeId) => { ${requestSourceNavigationQuery.inject("nodeId")} },
              requestExpandOverflowNode: (nodeId) => { ${requestExpandOverflowNodeQuery.inject("nodeId")} },
              applyGraphEditScript: (payload) => { ${applyGraphEditScriptQuery.inject("JSON.stringify(payload)")} }
            };
            window.dispatchEvent(new Event("link-graph-bridge-ready"));
            ${if (debugTracingEnabled) """console.log("link-graph bridge 注入完成");""" else ""}
        """.trimIndent()
    }

    private fun summarizePayloadText(
        value: String?,
        maxLength: Int = 160,
    ): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return "\"\""
        }
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength) + "...(trimmed)"
        }
    }

    private fun snapshotSummary(snapshot: GraphEditorStateSnapshot): String {
        return "revision=${snapshot.snapshotRevision}, lastMessageType=${snapshot.lastMessageType}"
    }
}
