package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.ui.bridge.BridgeCommandParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery

/**
 * 图谱浏览器桥注册器。
 *
 * 负责在 JCEF 浏览器实例上注册前后端通信所需的 JS 桥：
 * - 监听来自前端 JS 的命令（bridge command），解析并派发给后端 dispatcher。
 * - 监听前端调试 trace，按策略决定是否记录日志。
 * - 生成注入到前端页面的桥脚本，将后端能力暴露为 window.linkGraphBridge 等接口。
 *
 * 构造时传入一组策略回调与开关，避免直接耦合到具体的 dispatch/日志实现。
 */
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
    // 前端发送 bridge command 的 JS 通道
    private val bridgeCommandQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // 前端上报调试 trace 的 JS 通道
    private val debugTraceQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /**
     * 在两个 JS 通道上注册处理器。
     *
     * bridge command 处理器负责解析命令、派发 artifact 与消息（同步或异步），
     * trace 处理器负责在开启的情况下记录前端 trace。
     */
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

    /**
     * 构建要注入到前端页面的桥脚本。
     *
     * 根据调试开关决定是否暴露调试 trace 能力，并组装出
     * window.linkGraphBridge 上的各个命令方法，前端调用后会通过
     * bridgeCommandQuery 通道回传到后端处理器。
     */
    fun buildBridgeScript(): String {
        // 根据调试开关生成不同的桥脚本片段
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
                requestAssistantTask: (request) => sendCommand("requestAssistantTask", {
                  actionId: request ? request.actionId : null,
                  sceneId: request && request.sceneId ? request.sceneId : null,
                  intent: request ? request.intent : null,
                  prompt: request && request.prompt ? request.prompt : "",
                  selectedNodeIds: request && Array.isArray(request.selectedNodeIds) ? request.selectedNodeIds : [],
                  selectedDiffItemIds: request && Array.isArray(request.selectedDiffItemIds) ? request.selectedDiffItemIds : [],
                  target: request && request.target ? request.target : { kind: "NewTask" },
                  explanationGranularity: request && request.explanationGranularity ? request.explanationGranularity : null,
                  mode: request && request.mode ? request.mode : null,
                }),
                retryLastQaRequest: () => sendCommand("retryLastQaRequest"),
                confirmQaCandidateChange: (changeId) => sendCommand("confirmQaCandidateChange", { changeId }),
                unconfirmQaCandidateChange: (changeId) => sendCommand("unconfirmQaCandidateChange", { changeId }),
                resolveInvestigationThread: (threadId, resolutionStatus, note) => sendCommand("resolveInvestigationThread", {
                  threadId,
                  resolutionStatus,
                  note: note || ""
                }),
                applyDraftPatchPreview: (operationIds) => sendCommand("applyDraftPatchPreview", {
                  operationIds: Array.isArray(operationIds) ? operationIds : []
                }),
                clearDraftPatchPreview: () => sendCommand("clearDraftPatchPreview"),
                restoreDraftPatchPreview: (source) => sendCommand("restoreDraftPatchPreview", { source }),
                undoLastDraftPatchApply: () => sendCommand("undoLastDraftPatchApply"),
                requestCodeDrafts: () => sendCommand("requestCodeDrafts"),
                requestCurrentEditorContextGraph: () => sendCommand("requestCurrentEditorContextGraph"),
                requestAnalysisDisplayMode: (displayMode) => sendCommand("requestAnalysisDisplayMode", { displayMode }),
                requestIndexedGraph: (request) => sendCommand("requestIndexedGraph", request || {}),
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

    /**
     * 在安全包裹下执行 bridge 回调。
     *
     * 任何抛出的异常都会被捕获并转为一个带错误码的响应，
     * 同时通过 logger 记录告警，避免异常冒泡影响 JCEF 通道。
     *
     * @param actionLabel 用于日志识别的动作名称
     * @param action 真正要执行的动作，返回 JS 端的响应
     */
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
