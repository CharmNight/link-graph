package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.ui.bridge.BridgeCommandParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery

internal class GraphBrowserBridgeRegistrar(
    browser: JBCefBrowser,
    private val bridge: GraphEditorBridge,
    private val logger: Logger,
    private val debugTracingEnabled: Boolean,
    private val interactionProbeEnabled: Boolean,
    private val dispatchArtifactSlice: (List<String>) -> Unit,
    private val dispatchBridgeAsync: (String, () -> GraphEditorMessage) -> Unit,
    private val shouldLogFrontendTrace: (String) -> Boolean,
    private val runtimeTrace: ((String) -> Unit)?,
) {
    private val bridgeCommandQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val debugTraceQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    fun registerHandlers() {
        bridgeCommandQuery.addHandler { payload ->
            safeBridgeResponse("bridge command") {
                val parsed = BridgeCommandParser.parse(payload)
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "收到前端 bridge command: type=${parsed.type}, async=${parsed.async}"
                }
                if (parsed.artifactIds.isNotEmpty()) {
                    dispatchArtifactSlice(parsed.artifactIds)
                }
                val message = parsed.message
                if (message != null) {
                    if (parsed.async) {
                        dispatchBridgeAsync(parsed.actionLabel) { message }
                    } else {
                        bridge.dispatch(message)
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
        debugTraceQuery.addHandler { payload ->
            safeBridgeResponse("前端 trace") {
                if (shouldLogFrontendTrace(payload)) {
                    GraphBrowserPayloadParser.validatePayloadSize(payload, GraphBrowserPayloadKind.DEBUG_TRACE)
                    runtimeTrace?.invoke("前端 trace: $payload")
                    debugLazy(logger.isDebugEnabled, logger::debug) { "前端 trace: $payload" }
                }
                JBCefJSQuery.Response("ok")
            }
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
            {
              const sendCommand = (type, payload = {}) => {
                const command = { schemaVersion: 1, type: type, payload: payload };
                ${bridgeCommandQuery.inject("JSON.stringify(command || {})")}
              };
              window.linkGraphBridge = {
                sendCommand: (command) => { ${bridgeCommandQuery.inject("JSON.stringify(command || {})")} },
                importMermaid: (mermaid) => sendCommand("importMermaid", { mermaid }),
                exportMermaid: () => sendCommand("exportMermaid"),
                showDiffMode: () => sendCommand("showDiffMode"),
                requestSyncPreview: () => sendCommand("requestSyncPreview"),
                requestQa: (question, selectedNodeIds, sourceThreadId, mode) => sendCommand("requestQa", {
                  question: question || "",
                  selectedNodeIds: Array.isArray(selectedNodeIds) ? selectedNodeIds : [],
                  sourceThreadId: sourceThreadId || null,
                  mode: mode || "AUTO"
                }),
                requestAssistantTask: (request) => sendCommand("requestAssistantTask", {
                  intent: request && request.intent ? request.intent : "EXPLAIN_CODE",
                  prompt: request && request.prompt ? request.prompt : "",
                  selectedNodeIds: request && Array.isArray(request.selectedNodeIds) ? request.selectedNodeIds : [],
                  selectedDiffItemIds: request && Array.isArray(request.selectedDiffItemIds) ? request.selectedDiffItemIds : []
                }),
                retryLastQaRequest: () => sendCommand("retryLastQaRequest"),
                confirmQaCandidateChange: (changeId) => sendCommand("confirmQaCandidateChange", { changeId }),
                unconfirmQaCandidateChange: (changeId) => sendCommand("unconfirmQaCandidateChange", { changeId }),
                resolveInvestigationThread: (threadId, resolutionStatus, note) => sendCommand("resolveInvestigationThread", {
                  threadId,
                  resolutionStatus,
                  note: note || ""
                }),
                requestDiffReview: (question, selectedDiffItemIds) => sendCommand("requestDiffReview", {
                  question: question || "",
                  selectedDiffItemIds: Array.isArray(selectedDiffItemIds) ? selectedDiffItemIds : []
                }),
                requestGraphBeautification: (goal, preferredStyle, explanationFocus, granularity, followUpStepId, followUpStepTitle, followUpQuestion, focusNodeId) => sendCommand("requestGraphBeautification", {
                  goal: goal || "",
                  preferredStyle: preferredStyle || null,
                  explanationFocus: explanationFocus || null,
                  focusNodeId: focusNodeId || null,
                  granularity: granularity || "BUSINESS",
                  followUp: followUpStepId && followUpStepTitle && followUpQuestion
                    ? { stepId: followUpStepId, stepTitle: followUpStepTitle, question: followUpQuestion }
                    : null
                }),
                applyDraftPatchPreview: (operationIds) => sendCommand("applyDraftPatchPreview", {
                  operationIds: Array.isArray(operationIds) ? operationIds : []
                }),
                clearDraftPatchPreview: () => sendCommand("clearDraftPatchPreview"),
                restoreDraftPatchPreview: (source) => sendCommand("restoreDraftPatchPreview", { source }),
                undoLastDraftPatchApply: () => sendCommand("undoLastDraftPatchApply"),
                requestGenerationPlan: () => sendCommand("requestGenerationPlan"),
                requestGenerationPlanDiscussion: (question, focusItemId) => sendCommand("requestGenerationPlanDiscussion", {
                  question: question || "",
                  focusItemId: focusItemId || null
                }),
                requestCodeDrafts: () => sendCommand("requestCodeDrafts"),
                requestCurrentEditorContextGraph: () => sendCommand("requestCurrentEditorContextGraph"),
                requestAnalysisDisplayMode: (displayMode) => sendCommand("requestAnalysisDisplayMode", { displayMode }),
                requestIndexedGraph: (request) => sendCommand("requestIndexedGraph", request || {}),
                updateWorkbenchSectionPreference: (sectionId, expanded) => sendCommand("updateWorkbenchSectionPreference", {
                  sectionId: sectionId || "",
                  expanded: !!expanded
                }),
                requestOpenSettings: () => sendCommand("requestOpenSettings"),
                applyCodeDrafts: () => sendCommand("applyCodeDrafts"),
                applySingleCodeDraft: (draftId) => sendCommand("applySingleCodeDraft", { draftId }),
                openCodeDraftNativeDiff: (draftId) => sendCommand("openCodeDraftNativeDiff", { draftId }),
                requestDraftNavigation: (targetPath) => sendCommand("requestDraftNavigation", { targetPath }),
                requestArtifact: (artifactIds) => sendCommand("requestArtifact", {
                  artifactIds: Array.isArray(artifactIds) ? artifactIds : []
                }),
                frontendReady: (payload) => sendCommand("frontendReady", payload || {}),
                snapshotAck: (payload) => sendCommand("snapshotAck", payload || {}),
                nodeSelected: (nodeId) => sendCommand("nodeSelected", { nodeId }),
                layoutChanged: (payload) => sendCommand("layoutChanged", payload || { positions: [] }),
                requestSourceNavigation: (nodeId) => sendCommand("requestSourceNavigation", { nodeId }),
                requestExpandOverflowNode: (nodeId) => sendCommand("requestExpandOverflowNode", { nodeId }),
                requestExpandInvocation: (nodeId) => sendCommand("requestExpandInvocation", { nodeId }),
                requestRemoveInvocationExpansion: (expansionId) => sendCommand("requestRemoveInvocationExpansion", { expansionId }),
                applyGraphEditScript: (payload) => sendCommand("applyGraphEditScript", payload || {})
              };
            }
            window.dispatchEvent(new Event("link-graph-bridge-ready"));
            ${if (debugTracingEnabled) """console.log("link-graph bridge 注入完成");""" else ""}
        """.trimIndent()
    }

    private fun safeBridgeResponse(
        actionLabel: String,
        action: () -> JBCefJSQuery.Response,
    ): JBCefJSQuery.Response {
        return runCatching(action).getOrElse { error ->
            logger.warn("Graph browser bridge handler failed: $actionLabel", error)
            JBCefJSQuery.Response(null, 1, error.message ?: "$actionLabel 失败")
        }
    }
}
