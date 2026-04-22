package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.model.GraphJson
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.services.currentVisibleGraph
import com.charmnight.linkgraph.services.currentWorkingGraph
import com.charmnight.linkgraph.services.debugLazy
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * IDEA 工具窗口里的 JCEF 外壳。
 * 负责把前端产物装进浏览器、注入 bridge，并在项目状态变化时重新下发 bootstrap 数据。
 */
class GraphBrowserPanel private constructor(
    project: Project,
    private val frontendAssetLoader: FrontendAssetLoader,
) : JPanel(BorderLayout()), Disposable {
    constructor(project: Project) : this(project, ClasspathFrontendAssetLoader())
    private val transportState = GraphBrowserTransportState()
    private val bridge: GraphEditorBridge = GraphEditorBridge(
        project = project,
        onFrontendReady = ::handleFrontendReady,
        onSnapshotAck = ::handleSnapshotAck,
    )
    private val pageRenderer = GraphEditorPageRenderer()
    private val sliceRenderer = GraphEditorTransportSliceRenderer(pageRenderer)
    private val pendingTransportSnapshots = mutableMapOf<Long, GraphEditorStateService.Snapshot>()
    private val entryUrl: String = INLINE_ENTRY_URL
    private val frontendHtml: String = resolveFrontendHtml()
    private val browser: JBCefBrowser? = createBrowser()
    private val debugTracingEnabled: Boolean =
        System.getenv(DEBUG_TRACE_ENV)?.trim()?.equals("true", ignoreCase = true) == true
    private val interactionProbeEnabled: Boolean =
        debugTracingEnabled &&
            System.getenv(DEBUG_INTERACTION_PROBE_ENV)?.trim()?.equals("true", ignoreCase = true) == true
    @Volatile
    private var browserLoaded: Boolean = false
    @Volatile
    private var disposed: Boolean = false
    private var lastDispatchedSnapshot: GraphEditorStateService.Snapshot = bridge.currentState()
    private val importMermaidQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val exportMermaidQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val showDiffModeQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestSyncPreviewQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestAuditQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val retryLastAuditRequestQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val confirmAuditCandidateChangeQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val unconfirmAuditCandidateChangeQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val resolveInvestigationThreadQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestDiffReviewQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestGraphBeautificationQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val applyDraftPatchPreviewQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val clearDraftPatchPreviewQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val restoreDraftPatchPreviewQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val undoLastDraftPatchApplyQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestGenerationPlanQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestGenerationPlanDiscussionQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestCodeDraftsQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestCurrentEditorContextGraphQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestAnalysisDisplayModeQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val updateWorkbenchSectionPreferenceQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestOpenSettingsQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val applyCodeDraftsQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val applySingleCodeDraftQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val openCodeDraftNativeDiffQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestDraftNavigationQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestArtifactQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val graphChangedQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val frontendReadyQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val snapshotAckQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val layoutChangedQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val nodeSelectedQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestSourceNavigationQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val requestExpandOverflowNodeQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val debugTraceQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    init {
        browser?.let(::configureBrowser)
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        debugLazy(logger.isDebugEnabled, logger::debug) { "准备加载链路图前端入口: $entryUrl" }
        bridge.onFrontendLoaded(entryUrl)
        // 首次 loadHTML 时就内嵌 bootstrap，避免前端先渲染一版演示态再切到真实项目状态。
        val initialSnapshot = lastDispatchedSnapshot
        sliceRenderer.renderBootstrapInitScript(
            sessionId = transportState.sessionId,
            snapshot = initialSnapshot,
        )
        val initialHtml = pageRenderer.render(
            frontendHtml,
            transportState.sessionId,
            initialSnapshot,
            artifactRefs = sliceRenderer.currentArtifactRefs(),
        )
        browser?.loadHTML(initialHtml, entryUrl)
    }

    fun currentEntryUrl(): String = entryUrl

    fun bridge(): GraphEditorBridge = bridge

    fun syncFromProjectState() {
        if (disposed) {
            return
        }
        val currentBrowser = browser ?: return
        val snapshot = bridge.currentState()
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始向前端同步链路图状态: ${snapshotSummary(snapshot)}"
        }
        runtimeTrace {
            "开始向前端同步链路图状态: ${snapshotSummary(snapshot)}, delta=${snapshotDeltaSummary(lastDispatchedSnapshot, snapshot)}"
        }
        val transportScript = sliceRenderer.renderIncrementalScript(
            sessionId = transportState.sessionId,
            previousSnapshot = lastDispatchedSnapshot,
            snapshot = snapshot,
        ) ?: return
        val transportToExecute = transportState.onSnapshotAvailable(
            revision = snapshot.snapshotRevision,
            script = transportScript,
        )
        pendingTransportSnapshots[snapshot.snapshotRevision] = snapshot
        executeSnapshotScript(
            browser = currentBrowser,
            transport = transportToExecute,
            reason = "sync:${snapshot.lastMessageType ?: "unknown"}",
        )
    }

    override fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        browserLoaded = false
        removeAll()
        browser?.dispose()
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }
        return runCatching {
            JBCefBrowser()
        }.getOrNull()
    }

    private fun configureBrowser(browser: JBCefBrowser) {
        importMermaidQuery?.addHandler { mermaid ->
            bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
            JBCefJSQuery.Response("ok")
        }
        exportMermaidQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.ExportMermaid)
            JBCefJSQuery.Response("ok")
        }
        showDiffModeQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.ShowDiffMode)
            JBCefJSQuery.Response("ok")
        }
        requestSyncPreviewQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.RequestSyncPreview)
            JBCefJSQuery.Response("ok")
        }
        requestAuditQuery?.addHandler { payload ->
            val request = parseAuditRequestPayload(payload)
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
        retryLastAuditRequestQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.RetryLastAuditRequest)
            JBCefJSQuery.Response("ok")
        }
        confirmAuditCandidateChangeQuery?.addHandler { payload ->
            bridge.dispatch(GraphEditorMessage.ConfirmAuditCandidateChange(changeId = payload))
            JBCefJSQuery.Response("ok")
        }
        unconfirmAuditCandidateChangeQuery?.addHandler { payload ->
            bridge.dispatch(GraphEditorMessage.UnconfirmAuditCandidateChange(changeId = payload))
            JBCefJSQuery.Response("ok")
        }
        resolveInvestigationThreadQuery?.addHandler { payload ->
            runCatching {
                val request = parseResolveInvestigationThreadPayload(payload)
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
        requestDiffReviewQuery?.addHandler { payload ->
            val (question, selectedDiffItemIds) = parseQuestionWithIds(payload)
            bridge.dispatch(
                GraphEditorMessage.RequestDiffReview(
                    question = question,
                    selectedDiffItemIds = selectedDiffItemIds,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        requestGraphBeautificationQuery?.addHandler { payload ->
            val request = parseBeautificationPayload(payload)
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
        applyDraftPatchPreviewQuery?.addHandler { payload ->
            val operationIds = parseEncodedList(payload).toSet().takeIf { it.isNotEmpty() }
            bridge.dispatch(GraphEditorMessage.ApplyDraftPatchPreview(operationIds))
            JBCefJSQuery.Response("ok")
        }
        clearDraftPatchPreviewQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.ClearDraftPatchPreview)
            JBCefJSQuery.Response("ok")
        }
        restoreDraftPatchPreviewQuery?.addHandler { payload ->
            bridge.dispatch(
                GraphEditorMessage.RestoreDraftPatchPreview(
                    GraphEditorMessage.DraftPatchPreviewSource.valueOf(payload),
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        undoLastDraftPatchApplyQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.UndoLastDraftPatchApply)
            JBCefJSQuery.Response("ok")
        }
        requestGenerationPlanQuery?.addHandler {
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：生成实现计划, 当前快照=${snapshotSummary(bridge.currentState())}"
            }
            bridge.dispatch(GraphEditorMessage.RequestGenerationPlan)
            JBCefJSQuery.Response("ok")
        }
        requestGenerationPlanDiscussionQuery?.addHandler { payload ->
            val request = parseGenerationPlanDiscussionPayload(payload)
            bridge.dispatch(
                GraphEditorMessage.RequestGenerationPlanDiscussion(
                    question = request.question,
                    focusItemId = request.focusItemId,
                ),
            )
            JBCefJSQuery.Response("ok")
        }
        requestCodeDraftsQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.RequestCodeDrafts)
            JBCefJSQuery.Response("ok")
        }
        requestCurrentEditorContextGraphQuery?.addHandler {
            debugLazy(logger.isDebugEnabled, logger::debug) {
                "收到前端请求：加载当前编辑器上下文链路, 当前快照=${snapshotSummary(bridge.currentState())}"
            }
            bridge.dispatch(GraphEditorMessage.RequestCurrentEditorContextGraph)
            JBCefJSQuery.Response("ok")
        }
        requestAnalysisDisplayModeQuery?.addHandler { payload ->
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
        updateWorkbenchSectionPreferenceQuery?.addHandler { payload ->
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
        requestOpenSettingsQuery?.addHandler {
            bridge.dispatch(GraphEditorMessage.OpenSettings)
            JBCefJSQuery.Response("ok")
        }
        applyCodeDraftsQuery?.addHandler {
            dispatchBridgeAsync("写入全部代码草稿") {
                GraphEditorMessage.ApplyCodeDrafts
            }
            JBCefJSQuery.Response("ok")
        }
        applySingleCodeDraftQuery?.addHandler { draftId ->
            dispatchBridgeAsync("写入单个代码草稿") {
                GraphEditorMessage.ApplySingleCodeDraft(draftId)
            }
            JBCefJSQuery.Response("ok")
        }
        openCodeDraftNativeDiffQuery?.addHandler { draftId ->
            dispatchBridgeAsync("打开代码草稿原生 Diff") {
                GraphEditorMessage.OpenCodeDraftNativeDiff(draftId)
            }
            JBCefJSQuery.Response("ok")
        }
        requestDraftNavigationQuery?.addHandler { targetPath ->
            bridge.dispatch(GraphEditorMessage.RequestDraftNavigation(targetPath))
            JBCefJSQuery.Response("ok")
        }
        requestArtifactQuery?.addHandler { payload ->
            runCatching {
                dispatchArtifactSlice(parseEncodedList(payload))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "artifact 请求失败")
            }
        }
        nodeSelectedQuery?.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.NodeSelected(nodeId))
            JBCefJSQuery.Response("ok")
        }
        requestSourceNavigationQuery?.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.RequestSourceNavigation(nodeId))
            JBCefJSQuery.Response("ok")
        }
        requestExpandOverflowNodeQuery?.addHandler { nodeId ->
            bridge.dispatch(GraphEditorMessage.RequestExpandOverflowNode(nodeId))
            JBCefJSQuery.Response("ok")
        }
        graphChangedQuery?.addHandler { payload ->
            runCatching {
                val graph = GraphJson.fromJson(payload)
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "收到前端 graphChanged: nodes=${graph.nodes.size}, edges=${graph.edges.size}, sampleNodeIds=${graph.nodes.take(6).map { it.id }}"
                }
                bridge.dispatch(GraphEditorMessage.GraphChanged(graph))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "链路图变更同步失败")
            }
        }
        frontendReadyQuery?.addHandler { payload ->
            runCatching {
                bridge.dispatch(
                    GraphEditorMessage.FrontendReady(
                        lastAppliedRevision = parseNullableRevision(payload),
                    ),
                )
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "前端 ready 握手失败")
            }
        }
        snapshotAckQuery?.addHandler { payload ->
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
        layoutChangedQuery?.addHandler { payload ->
            runCatching {
                bridge.dispatch(GraphEditorMessage.LayoutChanged(parseLayoutPositions(payload)))
                JBCefJSQuery.Response("ok")
            }.getOrElse { error ->
                JBCefJSQuery.Response(null, 1, error.message ?: "链路图布局同步失败")
            }
        }
        debugTraceQuery?.addHandler { payload ->
            if (shouldLogFrontendTrace(payload)) {
                runtimeTrace { "前端 trace: $payload" }
                debugLazy(logger.isDebugEnabled, logger::debug) { "前端 trace: $payload" }
            }
            JBCefJSQuery.Response("ok")
        }
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    transitionType: org.cef.network.CefRequest.TransitionType?,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    debugLazy(logger.isDebugEnabled, logger::debug) { "JCEF 开始加载页面: ${cefBrowser.url}" }
                    browserLoaded = false
                    transportState.onMainFrameLoadStarted()
                }

                override fun onLoadEnd(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    debugLazy(logger.isDebugEnabled, logger::debug) {
                        "JCEF 页面加载完成: url=${cefBrowser.url}, status=$httpStatusCode"
                    }
                    browserLoaded = true
                    debugLazy(logger.isDebugEnabled, logger::debug) { "开始注入链路图 bridge 脚本" }
                    cefBrowser.executeJavaScript(buildBridgeScript(), cefBrowser.url, 0)
                    executeSnapshotScript(
                        browser = browser,
                        transport = transportState.onMainFrameLoadEnded(),
                        reason = "loadEnd",
                    )
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String,
                ) {
                    if (!frame.isMain) {
                        return
                    }
                    logger.warn("JCEF 页面加载失败: url=$failedUrl, errorCode=$errorCode, errorText=$errorText")
                }
            },
            browser.cefBrowser,
        )
        browser.jbCefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: org.cef.CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int,
                ): Boolean {
                    logger.warn("JCEF console[$level] $message ($source:$line)")
                    return false
                }
            },
            browser.cefBrowser,
        )
    }

    private fun buildBridgeScript(): String {
        val debugBridgeScript = if (debugTracingEnabled) {
            """
            window.__linkGraphDebugEnabled = true;
            window.__linkGraphInteractionProbe = ${if (interactionProbeEnabled) "true" else "false"};
            window.__linkGraphTraceBuffer = Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer : [];
            window.linkGraphDebugTrace = (payload) => { ${debugTraceQuery?.inject("payload") ?: ""} };
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
              importMermaid: (mermaid) => { ${importMermaidQuery?.inject("mermaid") ?: ""} },
              exportMermaid: () => { ${exportMermaidQuery?.inject("'exportMermaid'") ?: ""} },
              showDiffMode: () => { ${showDiffModeQuery?.inject("'showDiffMode'") ?: ""} },
              requestSyncPreview: () => { ${requestSyncPreviewQuery?.inject("'requestSyncPreview'") ?: ""} },
              requestAudit: (question, selectedNodeIds, sourceThreadId) => { ${requestAuditQuery?.inject("[(question ? encodeURIComponent(question) : ''), ((selectedNodeIds || []).map((value) => encodeURIComponent(value)).join(',')), (sourceThreadId ? encodeURIComponent(sourceThreadId) : '')].join('\\u001f')") ?: ""} },
              retryLastAuditRequest: () => { ${retryLastAuditRequestQuery?.inject("'retryLastAuditRequest'") ?: ""} },
              confirmAuditCandidateChange: (changeId) => { ${confirmAuditCandidateChangeQuery?.inject("changeId") ?: ""} },
              unconfirmAuditCandidateChange: (changeId) => { ${unconfirmAuditCandidateChangeQuery?.inject("changeId") ?: ""} },
              resolveInvestigationThread: (threadId, resolutionStatus, note) => { ${resolveInvestigationThreadQuery?.inject("[(threadId ? encodeURIComponent(threadId) : ''), (resolutionStatus ? encodeURIComponent(resolutionStatus) : ''), (note ? encodeURIComponent(note) : '')].join('\\u001f')") ?: ""} },
              requestDiffReview: (question, selectedDiffItemIds) => { ${requestDiffReviewQuery?.inject("[(question ? encodeURIComponent(question) : ''), ((selectedDiffItemIds || []).map((value) => encodeURIComponent(value)).join(','))].join('\\u001f')") ?: ""} },
              requestGraphBeautification: (goal, preferredStyle, explanationFocus, granularity, followUpStepId, followUpStepTitle, followUpQuestion) => { ${requestGraphBeautificationQuery?.inject("[(goal ? encodeURIComponent(goal) : ''), (preferredStyle ? encodeURIComponent(preferredStyle) : ''), (explanationFocus ? encodeURIComponent(explanationFocus) : ''), (granularity ? encodeURIComponent(granularity) : ''), (followUpStepId ? encodeURIComponent(followUpStepId) : ''), (followUpStepTitle ? encodeURIComponent(followUpStepTitle) : ''), (followUpQuestion ? encodeURIComponent(followUpQuestion) : '')].join('\\u001f')") ?: ""} },
              applyDraftPatchPreview: (operationIds) => { ${applyDraftPatchPreviewQuery?.inject("((operationIds || []).map((value) => encodeURIComponent(value)).join(','))") ?: ""} },
              clearDraftPatchPreview: () => { ${clearDraftPatchPreviewQuery?.inject("'clearDraftPatchPreview'") ?: ""} },
              restoreDraftPatchPreview: (source) => { ${restoreDraftPatchPreviewQuery?.inject("source") ?: ""} },
              undoLastDraftPatchApply: () => { ${undoLastDraftPatchApplyQuery?.inject("'undoLastDraftPatchApply'") ?: ""} },
              requestGenerationPlan: () => { ${requestGenerationPlanQuery?.inject("'requestGenerationPlan'") ?: ""} },
              requestGenerationPlanDiscussion: (question, focusItemId) => { ${requestGenerationPlanDiscussionQuery?.inject("[(question ? encodeURIComponent(question) : ''), (focusItemId ? encodeURIComponent(focusItemId) : '')].join('\\u001f')") ?: ""} },
              requestCodeDrafts: () => { ${requestCodeDraftsQuery?.inject("'requestCodeDrafts'") ?: ""} },
              requestCurrentEditorContextGraph: () => { ${requestCurrentEditorContextGraphQuery?.inject("'requestCurrentEditorContextGraph'") ?: ""} },
              requestAnalysisDisplayMode: (displayMode) => { ${requestAnalysisDisplayModeQuery?.inject("displayMode") ?: ""} },
              updateWorkbenchSectionPreference: (sectionId, expanded) => { ${updateWorkbenchSectionPreferenceQuery?.inject("[(sectionId ? encodeURIComponent(sectionId) : ''), (expanded ? '1' : '0')].join('\\u001f')") ?: ""} },
              requestOpenSettings: () => { ${requestOpenSettingsQuery?.inject("'requestOpenSettings'") ?: ""} },
              applyCodeDrafts: () => { ${applyCodeDraftsQuery?.inject("'applyCodeDrafts'") ?: ""} },
              applySingleCodeDraft: (draftId) => { ${applySingleCodeDraftQuery?.inject("draftId") ?: ""} },
              openCodeDraftNativeDiff: (draftId) => { ${openCodeDraftNativeDiffQuery?.inject("draftId") ?: ""} },
              requestDraftNavigation: (targetPath) => { ${requestDraftNavigationQuery?.inject("targetPath") ?: ""} },
              requestArtifact: (artifactIds) => { ${requestArtifactQuery?.inject("((artifactIds || []).map((value) => encodeURIComponent(value)).join(','))") ?: ""} },
              frontendReady: (payload) => { ${frontendReadyQuery?.inject("payload && Number.isFinite(payload.lastAppliedRevision) ? String(payload.lastAppliedRevision) : ''") ?: ""} },
              snapshotAck: (payload) => { ${snapshotAckQuery?.inject("payload && Number.isFinite(payload.revision) ? String(payload.revision) : ''") ?: ""} },
              nodeSelected: (nodeId) => { ${nodeSelectedQuery?.inject("nodeId") ?: ""} },
              layoutChanged: (payload) => { ${layoutChangedQuery?.inject("((payload && Array.isArray(payload.positions) ? payload.positions : []).map((item) => [encodeURIComponent(item.nodeId), item.x, item.y].join('\\u001f')).join('\\u001e'))") ?: ""} },
              requestSourceNavigation: (nodeId) => { ${requestSourceNavigationQuery?.inject("nodeId") ?: ""} },
              requestExpandOverflowNode: (nodeId) => { ${requestExpandOverflowNodeQuery?.inject("nodeId") ?: ""} },
              graphChanged: (payload) => { ${graphChangedQuery?.inject("JSON.stringify(payload)") ?: ""} }
            };
            window.dispatchEvent(new Event("link-graph-bridge-ready"));
            ${if (debugTracingEnabled) """console.log("link-graph bridge 注入完成");""" else ""}
        """.trimIndent()
    }

    private fun handleFrontendReady(lastAppliedRevision: Long?) {
        val currentBrowser = browser ?: return
        executeSnapshotScript(
            browser = currentBrowser,
            transport = transportState.onFrontendReady(lastAppliedRevision),
            reason = "frontendReady",
        )
    }

    private fun handleSnapshotAck(revision: Long) {
        val currentBrowser = browser ?: return
        executeSnapshotScript(
            browser = currentBrowser,
            transport = transportState.onSnapshotAcknowledged(revision),
            reason = "snapshotAck:$revision",
        )
    }

    private fun executeSnapshotScript(
        browser: JBCefBrowser,
        transport: GraphBrowserTransportState.DispatchedTransport?,
        reason: String,
    ) {
        val dispatchedTransport = transport ?: return
        if (dispatchedTransport.script.isBlank()) {
            return
        }
        pendingTransportSnapshots.remove(dispatchedTransport.revision)?.let { dispatchedSnapshot ->
            lastDispatchedSnapshot = dispatchedSnapshot
        }
        browser.cefBrowser.executeJavaScript(
            dispatchedTransport.script,
            browser.cefBrowser.url,
            0,
        )
        scheduleRuntimeProbe(reason)
    }

    private fun dispatchArtifactSlice(artifactIds: List<String>) {
        val currentBrowser = browser ?: return
        if (artifactIds.isEmpty()) {
            return
        }
        val artifactContents = sliceRenderer.artifactContents(artifactIds)
        if (artifactContents.isEmpty()) {
            return
        }
        val currentSnapshot = bridge.currentState()
        val script = sliceRenderer.renderScript(
            listOf(
                GraphEditorTransportEnvelope.ArtifactSlice(
                    sessionId = transportState.sessionId,
                    revision = currentSnapshot.snapshotRevision,
                    state = linkedMapOf(
                        "artifactContents" to artifactContents,
                        "snapshotRevision" to currentSnapshot.snapshotRevision,
                        "lastMessageType" to "artifactSlice",
                    ),
                ),
            ),
        )
        currentBrowser.cefBrowser.executeJavaScript(script, currentBrowser.cefBrowser.url, 0)
    }

    private fun scheduleRuntimeProbe(reason: String) {
        if (!debugTracingEnabled) {
            return
        }
        val currentBrowser = browser ?: return
        if (!browserLoaded) {
            return
        }
        currentBrowser.cefBrowser.executeJavaScript(
            buildRuntimeProbeScript(reason),
            currentBrowser.cefBrowser.url,
            0,
        )
    }

    private fun buildRuntimeProbeScript(reason: String): String {
        val escapedReason = escapeJsString(reason)
        return """
            (function() {
              const reason = "$escapedReason";
              const delays = [0, 120, 480, 1200, 2500];
              const round = (value) => Number.isFinite(value) ? Math.round(value) : null;
              const summarizeRect = (element) => {
                if (!element || !element.getBoundingClientRect) {
                  return null;
                }
                const rect = element.getBoundingClientRect();
                return {
                  width: Math.round(rect.width),
                  height: Math.round(rect.height),
                  top: Math.round(rect.top),
                  left: Math.round(rect.left),
                  right: Math.round(rect.right),
                  bottom: Math.round(rect.bottom)
                };
              };
              const summarizeBoxMetrics = (element) => {
                if (!element) {
                  return null;
                }
                return {
                  clientWidth: Number.isFinite(element.clientWidth) ? Math.round(element.clientWidth) : null,
                  clientHeight: Number.isFinite(element.clientHeight) ? Math.round(element.clientHeight) : null,
                  scrollWidth: Number.isFinite(element.scrollWidth) ? Math.round(element.scrollWidth) : null,
                  scrollHeight: Number.isFinite(element.scrollHeight) ? Math.round(element.scrollHeight) : null,
                  offsetWidth: Number.isFinite(element.offsetWidth) ? Math.round(element.offsetWidth) : null,
                  offsetHeight: Number.isFinite(element.offsetHeight) ? Math.round(element.offsetHeight) : null
                };
              };
              const summarizeComputedStyle = (element, properties) => {
                if (!element || typeof window.getComputedStyle !== "function") {
                  return null;
                }
                try {
                  const computed = window.getComputedStyle(element);
                  return properties.reduce((result, propertyName) => {
                    result[propertyName] = computed.getPropertyValue(propertyName) || null;
                    return result;
                  }, {});
                } catch (error) {
                  return { error: String(error) };
                }
              };
              const summarizeSvgRect = (rect) => {
                if (!rect) {
                  return null;
                }
                return {
                  x: round(rect.x),
                  y: round(rect.y),
                  width: round(rect.width),
                  height: round(rect.height)
                };
              };
              const summarizePoint = (point) => {
                if (!point) {
                  return null;
                }
                return {
                  x: round(point.x),
                  y: round(point.y)
                };
              };
              const summarizeFlowPoint = (point, flowHostRect, viewportState) => {
                if (!point || !flowHostRect || !viewportState || !Number.isFinite(viewportState.scale) || Math.abs(viewportState.scale) < 0.0001) {
                  return null;
                }
                return {
                  x: round((point.x - flowHostRect.left - viewportState.translateX) / viewportState.scale),
                  y: round((point.y - flowHostRect.top - viewportState.translateY) / viewportState.scale)
                };
              };
              const summarizeRectCenter = (element) => {
                if (!element || !element.getBoundingClientRect) {
                  return null;
                }
                const rect = element.getBoundingClientRect();
                return {
                  x: round((rect.left + rect.right) / 2),
                  y: round((rect.top + rect.bottom) / 2)
                };
              };
              const parseViewportState = (viewport) => {
                const style = viewport?.getAttribute ? (viewport.getAttribute("style") ?? "") : "";
                const matched = /translate\(([-\d.]+)px,\s*([-\d.]+)px\)\s*scale\(([-\d.]+)\)/.exec(style);
                if (!matched) {
                  return null;
                }
                const translateX = Number.parseFloat(matched[1] ?? "");
                const translateY = Number.parseFloat(matched[2] ?? "");
                const scale = Number.parseFloat(matched[3] ?? "");
                if (!Number.isFinite(translateX) || !Number.isFinite(translateY) || !Number.isFinite(scale)) {
                  return null;
                }
                return {
                  translateX,
                  translateY,
                  scale
                };
              };
              const summarizeIntersection = (left, right) => {
                if (!left || !right) {
                  return null;
                }
                const width = Math.max(0, Math.min(left.right, right.right) - Math.max(left.left, right.left));
                const height = Math.max(0, Math.min(left.bottom, right.bottom) - Math.max(left.top, right.top));
                return {
                  width: round(width),
                  height: round(height),
                  area: round(width * height)
                };
              };
              const summarizeVisualViewport = () => {
                if (!window.visualViewport) {
                  return null;
                }
                return {
                  width: round(window.visualViewport.width),
                  height: round(window.visualViewport.height),
                  offsetLeft: round(window.visualViewport.offsetLeft),
                  offsetTop: round(window.visualViewport.offsetTop),
                  pageLeft: round(window.visualViewport.pageLeft),
                  pageTop: round(window.visualViewport.pageTop),
                  scale: round(window.visualViewport.scale)
                };
              };
              const emitPayload = (payload) => {
                if (typeof window.linkGraphDebugTrace === "function") {
                  window.linkGraphDebugTrace(payload);
                  return;
                }
                window.__linkGraphTraceBuffer = Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer : [];
                window.__linkGraphTraceBuffer.push(payload);
              };
              const emitEvent = (event, payload) => {
                emitPayload(JSON.stringify({
                  time: new Date().toISOString(),
                  event,
                  payload: payload ?? null
                }));
              };
              const intersects = (left, right) => {
                if (!left || !right) {
                  return false;
                }
                return !(left.right < right.left || left.left > right.right || left.bottom < right.top || left.top > right.bottom);
              };
              const summarizeHandle = (element, flowHostRect, viewportState) => {
                const screenCenter = summarizeRectCenter(element);
                return {
                  handleId: element?.dataset?.handleid ?? null,
                  nodeId: element?.dataset?.nodeid ?? null,
                  handlePosition: element?.dataset?.handlepos ?? null,
                  rect: summarizeRect(element),
                  screenCenter,
                  flowPoint: summarizeFlowPoint(screenCenter, flowHostRect, viewportState)
                };
              };
              const summarizeFlowNode = (element, flowHostRect, viewportState) => {
                const shell = element?.querySelector(".flowchart-react-node, .fact-graph-react-node, .resource-relation-react-node");
                const card = shell?.querySelector(".flow-node-card");
                const handles = shell
                  ? Array.from(shell.querySelectorAll(".react-flow__handle")).map((handle) => summarizeHandle(handle, flowHostRect, viewportState))
                  : [];
                return {
                  nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                  selected: element?.classList?.contains("selected") === true,
                  wrapperRect: summarizeRect(element),
                  wrapperCenter: summarizeRectCenter(element),
                  wrapperCenterFlowPoint: summarizeFlowPoint(summarizeRectCenter(element), flowHostRect, viewportState),
                  shellRect: summarizeRect(shell),
                  cardRect: summarizeRect(card),
                  handleRects: handles
                };
              };
              const summarizeDecisionNode = (element, flowHostRect, viewportState) => {
                const summary = summarizeFlowNode(element, flowHostRect, viewportState);
                return {
                  ...summary,
                  shellRect: summary.shellRect,
                  cardRect: summary.cardRect
                };
              };
              const summarizeFlowNodeGeometry = (element, flowHostRect, viewportState) => ({
                nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                selected: element?.classList?.contains("selected") === true,
                className: element?.className ?? null,
                ...summarizeFlowNode(element, flowHostRect, viewportState)
              });
              const summarizeEdge = (element) => {
                const path = element?.querySelector(".react-flow__edge-path");
                const length = path?.getTotalLength ? path.getTotalLength() : null;
                const safeLength = typeof length === "number" && Number.isFinite(length) ? length : null;
                return {
                  edgeId: element?.dataset?.id ?? null,
                  pathD: path?.getAttribute("d") ?? null,
                  pathBox: (() => {
                    try {
                      return path?.getBBox ? summarizeSvgRect(path.getBBox()) : null;
                    } catch (error) {
                      return { error: String(error) };
                    }
                  })(),
                  startPoint: safeLength !== null && path?.getPointAtLength ? summarizePoint(path.getPointAtLength(0)) : null,
                  endPoint: safeLength !== null && path?.getPointAtLength
                    ? summarizePoint(path.getPointAtLength(Math.max(safeLength - 0.01, 0)))
                    : null
                };
              };
              const summarizeEdgeGeometry = (element) => ({
                edgeId: element?.dataset?.id ?? null,
                className: element?.className ?? null,
                ...summarizeEdge(element)
              });
              const summarizeNodeVisual = (element) => {
                if (!element) {
                  return null;
                }
                const shell = element.querySelector(".flowchart-react-node, .fact-graph-react-node, .resource-relation-react-node");
                const card = shell?.querySelector(".flow-node-card");
                const title = shell?.querySelector(".flowchart-node-title, .flow-node-owner");
                return {
                  nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                  wrapperClassName: element?.className ?? null,
                  shellClassName: shell?.className ?? null,
                  cardClassName: card?.className ?? null,
                  titleText: title?.textContent?.trim() ?? null,
                  wrapperRect: summarizeRect(element),
                  shellRect: summarizeRect(shell),
                  cardRect: summarizeRect(card),
                  wrapperStyle: summarizeComputedStyle(element, [
                    "opacity",
                    "transform",
                    "filter",
                    "outline",
                    "outline-offset",
                    "box-shadow",
                    "pointer-events"
                  ]),
                  shellStyle: summarizeComputedStyle(shell, [
                    "opacity",
                    "transform",
                    "filter",
                    "background-color",
                    "border",
                    "border-color",
                    "border-width",
                    "border-style",
                    "border-radius",
                    "box-shadow",
                    "outline",
                    "outline-offset"
                  ]),
                  cardStyle: summarizeComputedStyle(card, [
                    "opacity",
                    "transform",
                    "filter",
                    "background-color",
                    "border",
                    "border-color",
                    "border-width",
                    "border-style",
                    "border-radius",
                    "box-shadow",
                    "outline",
                    "outline-offset"
                  ]),
                  titleStyle: summarizeComputedStyle(title, [
                    "color",
                    "opacity",
                    "font-size",
                    "font-weight",
                    "line-height",
                    "text-decoration"
                  ])
                };
              };
              const summarizeEdgeVisual = (element) => {
                if (!element) {
                  return null;
                }
                const path = element.querySelector(".react-flow__edge-path");
                return {
                  edgeId: element?.dataset?.id ?? null,
                  className: element?.className ?? null,
                  pathClassName: path?.className?.baseVal ?? path?.className ?? null,
                  pathStyleAttribute: path?.getAttribute?.("style") ?? null,
                  markerStart: path?.getAttribute?.("marker-start") ?? null,
                  markerEnd: path?.getAttribute?.("marker-end") ?? null,
                  pathStyle: summarizeComputedStyle(path, [
                    "opacity",
                    "stroke",
                    "stroke-width",
                    "stroke-dasharray",
                    "filter",
                    "marker-start",
                    "marker-end"
                  ])
                };
              };
              const summarizeProbeNode = (element, flowHostRect, viewportState) => ({
                nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                rect: summarizeRect(element),
                center: summarizeRectCenter(element),
                centerFlowPoint: summarizeFlowPoint(summarizeRectCenter(element), flowHostRect, viewportState),
                transform: element?.style?.transform ?? null,
                handles: Array.from(element?.querySelectorAll?.(".react-flow__handle") ?? []).map((handle) =>
                  summarizeHandle(handle, flowHostRect, viewportState),
                )
              });
              const summarizeVisibleEdges = () =>
                Array.from(document.querySelectorAll(".react-flow__edge")).slice(0, 24).map((edge) => summarizeEdge(edge));
              const edgeSignature = (edge) => JSON.stringify({
                pathD: edge?.pathD ?? null,
                startPoint: edge?.startPoint ?? null,
                endPoint: edge?.endPoint ?? null
              });
              const countChangedEdges = (beforeEdges, afterEdges) => {
                const beforeIndex = new Map((beforeEdges ?? []).map((edge) => [edge.edgeId, edgeSignature(edge)]));
                return (afterEdges ?? []).reduce((count, edge) => {
                  if (!edge?.edgeId) {
                    return count;
                  }
                  return beforeIndex.get(edge.edgeId) === edgeSignature(edge) ? count : count + 1;
                }, 0);
              };
              const dispatchMouseEvent = (targets, type, x, y, buttons) => {
                targets.forEach((target) => {
                  if (!target?.dispatchEvent) {
                    return;
                  }
                  target.dispatchEvent(new MouseEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    composed: true,
                    button: 0,
                    buttons,
                    which: buttons === 0 ? 0 : 1,
                    detail: 1,
                    clientX: x,
                    clientY: y,
                    screenX: x,
                    screenY: y,
                    view: window
                  }));
                });
              };
              const dispatchPointerEvent = (targets, type, x, y, buttons) => {
                if (typeof window.PointerEvent !== "function") {
                  return;
                }
                targets.forEach((target) => {
                  if (!target?.dispatchEvent) {
                    return;
                  }
                  target.dispatchEvent(new PointerEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    composed: true,
                    button: 0,
                    buttons,
                    clientX: x,
                    clientY: y,
                    screenX: x,
                    screenY: y,
                    pointerId: 1,
                    pointerType: "mouse",
                    isPrimary: true,
                    view: window
                  }));
                });
              };
              const dispatchDragGesture = (targets, phase, x, y, buttons) => {
                if (phase === "down") {
                  dispatchPointerEvent(targets, "pointerdown", x, y, buttons);
                  dispatchMouseEvent(targets, "mousedown", x, y, buttons);
                  return;
                }
                if (phase === "move") {
                  dispatchPointerEvent(targets, "pointermove", x, y, buttons);
                  dispatchMouseEvent(targets, "mousemove", x, y, buttons);
                  return;
                }
                dispatchPointerEvent(targets, "pointerup", x, y, buttons);
                dispatchMouseEvent(targets, "mouseup", x, y, buttons);
              };
              const selectDragDelta = (forwardSpace, backwardSpace, preferred) => {
                if (forwardSpace >= preferred) {
                  return preferred;
                }
                if (backwardSpace >= preferred) {
                  return -preferred;
                }
                if (forwardSpace >= 16) {
                  return Math.max(Math.min(forwardSpace - 8, preferred), 8);
                }
                if (backwardSpace >= 16) {
                  return -Math.max(Math.min(backwardSpace - 8, preferred), 8);
                }
                return 0;
              };
              const summarizeDragSnapshot = (nodeId) => {
                const node = Array.from(document.querySelectorAll(".react-flow__node")).find((element) =>
                  (element?.dataset?.id ?? element?.dataset?.nodeid ?? null) === nodeId,
                );
                const flowHost = document.querySelector(".react-flow");
                const viewport = document.querySelector(".react-flow__viewport");
                const viewportState = parseViewportState(viewport);
                return {
                  node: summarizeProbeNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState),
                  edges: summarizeVisibleEdges(),
                  viewportStyle: document.querySelector(".react-flow__viewport")?.getAttribute("style") ?? null
                };
              };
              const parseTraceMessage = (serialized) => {
                if (typeof serialized !== "string" || serialized.length === 0) {
                  return null;
                }
                try {
                  return JSON.parse(serialized);
                } catch (error) {
                  return null;
                }
              };
              const summarizeTraceDiagnostics = () => {
                const history = Array.isArray(window.__linkGraphTraceHistory) ? window.__linkGraphTraceHistory : [];
                const parsedHistory = history
                  .slice(-12)
                  .map((entry) => parseTraceMessage(entry))
                  .filter((entry) => entry && typeof entry === "object");
                const reverseHistory = [...parsedHistory].reverse();
                const lastTrace = parseTraceMessage(window.__linkGraphLastTrace);
                const lastViewportTrace = reverseHistory.find((entry) => {
                  const eventName = String(entry?.event ?? "");
                  return eventName === "graphFlowSurface.scheduleViewport"
                    || eventName.startsWith("graphFlowSurface.viewport");
                }) ?? null;
                const lastBootstrapTrace = reverseHistory.find((entry) =>
                  String(entry?.event ?? "").startsWith("app.applyBootstrapState"),
                ) ?? null;
                return {
                  debugEnabled: window.__linkGraphDebugEnabled === true,
                  interactionProbeEnabled: window.__linkGraphInteractionProbe === true,
                  hasTraceSink: typeof window.linkGraphDebugTrace === "function",
                  traceBufferLength: Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer.length : 0,
                  traceHistoryLength: history.length,
                  lastTraceEvent: lastTrace?.event ?? null,
                  lastTraceTime: lastTrace?.time ?? null,
                  recentTraceEvents: parsedHistory.map((entry) => entry?.event ?? null),
                  lastViewportTrace: lastViewportTrace
                    ? {
                        event: lastViewportTrace.event ?? null,
                        time: lastViewportTrace.time ?? null,
                        payload: lastViewportTrace.payload ?? null
                      }
                    : null,
                  lastBootstrapTrace: lastBootstrapTrace
                    ? {
                        event: lastBootstrapTrace.event ?? null,
                        time: lastBootstrapTrace.time ?? null,
                        payload: lastBootstrapTrace.payload ?? null
                      }
                    : null
                };
              };
              const runInteractionProbe = () => {
                if (window.__linkGraphInteractionProbe !== true || String(reason).indexOf("loadAnalysisOutcome") === -1) {
                  return;
                }
                window.__linkGraphInteractionState = window.__linkGraphInteractionState ?? {};
                const interactionState = window.__linkGraphInteractionState;
                const runKey = String(reason);
                if (interactionState.lastDragRunKey === runKey) {
                  return;
                }
                interactionState.lastDragRunKey = runKey;
                window.setTimeout(() => {
                  const shell = document.querySelector("[data-testid='graph-canvas-shell']");
                  const shellRect = shell?.getBoundingClientRect ? shell.getBoundingClientRect() : null;
                  const visibleNodes = Array.from(document.querySelectorAll(".react-flow__node")).filter((node) =>
                    intersects(node?.getBoundingClientRect ? node.getBoundingClientRect() : null, shellRect),
                  );
                  const targetNode = visibleNodes[0];
                  if (!targetNode || !shellRect) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "no-visible-node",
                      visibleNodeCount: visibleNodes.length
                    });
                    return;
                  }
                  const targetNodeId = targetNode?.dataset?.id ?? targetNode?.dataset?.nodeid ?? null;
                  const targetRect = targetNode.getBoundingClientRect ? targetNode.getBoundingClientRect() : null;
                  if (!targetRect || !targetNodeId) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "missing-node-geometry"
                    });
                    return;
                  }
                  const deltaX = selectDragDelta(shellRect.right - targetRect.right, targetRect.left - shellRect.left, 56);
                  const deltaY = selectDragDelta(shellRect.bottom - targetRect.bottom, targetRect.top - shellRect.top, 28);
                  if (Math.abs(deltaX) < 8 && Math.abs(deltaY) < 8) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "insufficient-drag-room",
                      nodeId: targetNodeId
                    });
                    return;
                  }
                  const startX = Math.round((targetRect.left + targetRect.right) / 2);
                  const startY = Math.round((targetRect.top + targetRect.bottom) / 2);
                  const endX = startX + deltaX;
                  const endY = startY + deltaY;
                  const beforeSnapshot = summarizeDragSnapshot(targetNodeId);
                  emitEvent("jcef.interactionProbe.drag.started", {
                    reason,
                    nodeId: targetNodeId,
                    start: { x: startX, y: startY },
                    delta: { x: deltaX, y: deltaY },
                    before: beforeSnapshot
                  });
                  dispatchDragGesture([targetNode], "down", startX, startY, 1);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", startX + Math.round(deltaX * 0.2), startY + Math.round(deltaY * 0.2), 1);
                  }, 24);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", startX + Math.round(deltaX * 0.65), startY + Math.round(deltaY * 0.65), 1);
                  }, 56);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", endX, endY, 1);
                    emitEvent("jcef.interactionProbe.drag.progress", {
                      reason,
                      nodeId: targetNodeId,
                      snapshot: summarizeDragSnapshot(targetNodeId)
                    });
                  }, 96);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "up", endX, endY, 0);
                    window.setTimeout(() => {
                      const afterSnapshot = summarizeDragSnapshot(targetNodeId);
                      const beforeRect = beforeSnapshot?.node?.rect ?? null;
                      const afterRect = afterSnapshot?.node?.rect ?? null;
                      const movedDistance = beforeRect && afterRect
                        ? round(Math.hypot(afterRect.left - beforeRect.left, afterRect.top - beforeRect.top))
                        : null;
                      emitEvent("jcef.interactionProbe.drag.completed", {
                        reason,
                        nodeId: targetNodeId,
                        movedDistance,
                        changedEdgeCount: countChangedEdges(beforeSnapshot?.edges ?? [], afterSnapshot?.edges ?? []),
                        after: afterSnapshot
                      });
                    }, 96);
                  }, 168);
                }, 900);
              };
              const emit = (stage) => {
                try {
                  const root = document.getElementById("root");
                  const html = document.documentElement;
                  const body = document.body;
                  const shell = document.querySelector("[data-testid='graph-canvas-shell']");
                  const flowHost = document.querySelector(".react-flow");
                  const flowPane = document.querySelector(".react-flow__pane");
                  const flowNodesLayer = document.querySelector(".react-flow__nodes");
                  const flowEdgesLayer = document.querySelector(".react-flow__edges");
                  const flowEdgesSvg = flowEdgesLayer?.querySelector("svg") ?? null;
                  const workbenchShell = document.querySelector(".workbench-shell");
                  const workbenchPanel = document.querySelector(".workbench-panel-body");
                  const activeWorkbenchTab = document.querySelector(".workbench-tab-button.active");
                  const requestBanner = document.querySelector(".async-request-banner");
                  const viewport = document.querySelector(".react-flow__viewport");
                  const viewportState = parseViewportState(viewport);
                  const flowNodes = Array.from(document.querySelectorAll(".react-flow__node"));
                  const selectedNodes = flowNodes.filter((node) => node.classList?.contains("selected"));
                  const explanationFocusNodes = flowNodes.filter((node) => node.classList?.contains("is-explanation-focus"));
                  const draftChangedNodes = flowNodes.filter((node) => node.classList?.contains("is-draft-change"));
                  const highlightedNodes = flowNodes.filter((node) =>
                    node.classList?.contains("selected") ||
                      node.classList?.contains("is-explanation-focus") ||
                      node.classList?.contains("is-draft-change"),
                  );
                  const decisionNodes = Array.from(document.querySelectorAll(".react-flow__node")).filter((node) =>
                    node.querySelector(".flowchart-react-node.kind-decision"),
                  );
                  const flowEdges = Array.from(document.querySelectorAll(".react-flow__edge"));
                  const allFlowNodeRects = flowNodes
                    .map((node) =>
                      summarizeFlowNodeGeometry(
                        node,
                        flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null,
                        viewportState,
                      ),
                    )
                    .sort((left, right) => String(left?.nodeId ?? "").localeCompare(String(right?.nodeId ?? "")));
                  const allFlowEdgeEndpoints = flowEdges
                    .map((edge) => summarizeEdgeGeometry(edge))
                    .sort((left, right) => String(left?.edgeId ?? "").localeCompare(String(right?.edgeId ?? "")));
                  const shellRectRaw = shell && shell.getBoundingClientRect ? shell.getBoundingClientRect() : null;
                  const firstNodeRectRaw = flowNodes[0] && flowNodes[0].getBoundingClientRect ? flowNodes[0].getBoundingClientRect() : null;
                  const selectedNodeRectRaw = selectedNodes[0] && selectedNodes[0].getBoundingClientRect ? selectedNodes[0].getBoundingClientRect() : null;
                  const flowHostRectRaw = flowHost && flowHost.getBoundingClientRect ? flowHost.getBoundingClientRect() : null;
                  const visibleNodeCountInShell = shellRectRaw
                    ? flowNodes.reduce((count, node) => {
                        const rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                        return intersects(rect, shellRectRaw) ? count + 1 : count;
                      }, 0)
                    : null;
                  const payload = JSON.stringify({
                    time: new Date().toISOString(),
                    event: "jcef.runtimeProbe",
                    payload: {
                      reason,
                      stage,
                      readyState: document.readyState,
                      title: document.title,
                      devicePixelRatio: Number.isFinite(window.devicePixelRatio) ? window.devicePixelRatio : null,
                      visualViewport: summarizeVisualViewport(),
                      windowSize: {
                        innerWidth: round(window.innerWidth),
                        innerHeight: round(window.innerHeight),
                        outerWidth: round(window.outerWidth),
                        outerHeight: round(window.outerHeight),
                        scrollX: round(window.scrollX),
                        scrollY: round(window.scrollY)
                      },
                      screenSize: {
                        width: round(window.screen?.width),
                        height: round(window.screen?.height),
                        availWidth: round(window.screen?.availWidth),
                        availHeight: round(window.screen?.availHeight)
                      },
                      rootChildren: root ? root.childElementCount : null,
                      rootTextSample: root ? (root.textContent || "").trim().slice(0, 120) : null,
                      flowNodeCount: flowNodes.length,
                      flowEdgeCount: document.querySelectorAll(".react-flow__edge").length,
                      flowCardCount: document.querySelectorAll(".flow-node-card").length,
                      selectedNodeCount: selectedNodes.length,
                      explanationFocusNodeCount: explanationFocusNodes.length,
                      draftChangedNodeCount: draftChangedNodes.length,
                      visibleNodeCountInShell,
                      htmlRect: summarizeRect(html),
                      bodyRect: summarizeRect(body),
                      shellRect: summarizeRect(shell),
                      flowHostRect: summarizeRect(flowHost),
                      flowPaneRect: summarizeRect(flowPane),
                      flowNodesLayerRect: summarizeRect(flowNodesLayer),
                      flowEdgesLayerRect: summarizeRect(flowEdgesLayer),
                      flowEdgesSvgRect: summarizeRect(flowEdgesSvg),
                      workbenchShellRect: summarizeRect(workbenchShell),
                      workbenchPanelRect: summarizeRect(workbenchPanel),
                      htmlBox: summarizeBoxMetrics(html),
                      bodyBox: summarizeBoxMetrics(body),
                      rootBox: summarizeBoxMetrics(root),
                      shellBox: summarizeBoxMetrics(shell),
                      flowHostBox: summarizeBoxMetrics(flowHost),
                      flowPaneBox: summarizeBoxMetrics(flowPane),
                      flowEdgesSvgBox: summarizeBoxMetrics(flowEdgesSvg),
                      activeWorkbenchTab: activeWorkbenchTab?.textContent?.trim() ?? null,
                      activeWorkbenchTabId: activeWorkbenchTab?.id ?? null,
                      requestBannerText: requestBanner?.textContent?.trim()?.slice(0, 200) ?? null,
                      debugTraceState: summarizeTraceDiagnostics(),
                      anchorTitle: document.querySelector(".canvas-reading-card.is-anchor .canvas-reading-title")?.textContent?.trim() ?? null,
                      selectedSummaryTitle: document.querySelectorAll(".canvas-reading-card .canvas-reading-title")[1]?.textContent?.trim() ?? null,
                      selectedCanvasTitle:
                        document.querySelector(".react-flow__node.selected .flowchart-node-title, .react-flow__node.selected .flow-node-owner")
                          ?.textContent
                          ?.trim() ?? null,
                      viewportState,
                      selectedNodeIntersectionWithShell: summarizeIntersection(selectedNodeRectRaw, shellRectRaw),
                      selectedNodeIntersectionWithFlowHost: summarizeIntersection(selectedNodeRectRaw, flowHostRectRaw),
                      firstNodeRect: firstNodeRectRaw ? {
                        width: Math.round(firstNodeRectRaw.width),
                        height: Math.round(firstNodeRectRaw.height),
                        top: Math.round(firstNodeRectRaw.top),
                        left: Math.round(firstNodeRectRaw.left)
                      } : null,
                      viewportStyle: viewport ? (viewport.getAttribute("style") || null) : null,
                      htmlStyle: summarizeComputedStyle(html, [
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      bodyStyle: summarizeComputedStyle(body, [
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      rootStyle: summarizeComputedStyle(root, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      shellStyle: summarizeComputedStyle(shell, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "isolation",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter"
                      ]),
                      flowHostStyle: summarizeComputedStyle(flowHost, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "isolation",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter"
                      ]),
                      flowPaneStyle: summarizeComputedStyle(flowPane, [
                        "display",
                        "position",
                        "overflow",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "pointer-events"
                      ]),
                      flowNodesWithHandles: flowNodes
                        .filter((node) => node.querySelector(".react-flow__handle"))
                        .slice(0, 12)
                        .map((node) => summarizeFlowNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState)),
                      allFlowNodeRects,
                      highlightedNodes: highlightedNodes.slice(0, 12).map((node) => ({
                        nodeId: node?.dataset?.id ?? node?.dataset?.nodeid ?? null,
                        className: node?.className ?? null,
                        title:
                          node.querySelector(".flowchart-node-title, .flow-node-owner")
                            ?.textContent
                            ?.trim() ?? null,
                      })),
                      decisionNodes: decisionNodes.slice(0, 6).map((node) =>
                        summarizeDecisionNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState),
                      ),
                      flowEdgesLayerStyle: summarizeComputedStyle(flowEdgesLayer, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "filter",
                        "pointer-events"
                      ]),
                      flowEdgesSvgStyle: summarizeComputedStyle(flowEdgesSvg, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "filter",
                        "pointer-events"
                      ]),
                      viewportComputedStyle: summarizeComputedStyle(viewport, [
                        "display",
                        "position",
                        "overflow",
                        "clip-path",
                        "contain",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter",
                        "opacity"
                      ]),
                      flowEdges: flowEdges.slice(0, 24).map((edge) => summarizeEdge(edge)),
                      allFlowEdgeEndpoints,
                      selectedNodeVisual: summarizeNodeVisual(selectedNodes[0] ?? null),
                      highlightedNodeVisuals: highlightedNodes.slice(0, 4).map((node) => summarizeNodeVisual(node)),
                      edgeVisualSample: flowEdges.slice(0, 8).map((edge) => summarizeEdgeVisual(edge))
                    }
                  });
                  emitPayload(payload);
                } catch (error) {
                  const payload = JSON.stringify({
                    time: new Date().toISOString(),
                    event: "jcef.runtimeProbeFailed",
                    payload: {
                      reason,
                      stage,
                      error: String(error)
                    }
                  });
                  emitPayload(payload);
                }
              };
              delays.forEach((delay) => window.setTimeout(() => emit("t" + delay), delay));
              runInteractionProbe();
            })();
        """.trimIndent()
    }

    private fun escapeJsString(value: String): String = buildString(value.length + 8) {
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

    private fun parseQuestionWithIds(payload: String): Pair<String, List<String>> {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        val question = decodePayloadValue(parts.firstOrNull().orEmpty())
        val selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty())
        return question to selectedNodeIds
    }

    private data class GenerationPlanDiscussionPayload(
        val question: String,
        val focusItemId: String?,
    )

    private fun parseGenerationPlanDiscussionPayload(payload: String): GenerationPlanDiscussionPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        return GenerationPlanDiscussionPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            focusItemId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
        )
    }

    private data class AuditRequestPayload(
        val question: String,
        val selectedNodeIds: List<String>,
        val sourceThreadId: String?,
    )

    private fun parseAuditRequestPayload(payload: String): AuditRequestPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        return AuditRequestPayload(
            question = decodePayloadValue(parts.firstOrNull().orEmpty()),
            selectedNodeIds = parseEncodedList(parts.getOrNull(1).orEmpty()),
            sourceThreadId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue),
        )
    }

    private data class ResolveInvestigationThreadPayload(
        val threadId: String,
        val resolutionStatus: RiskResolutionStatus,
        val note: String,
    )

    private fun parseResolveInvestigationThreadPayload(payload: String): ResolveInvestigationThreadPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        val threadId = decodePayloadValue(parts.firstOrNull().orEmpty()).ifBlank {
            error("风险线程标识不能为空")
        }
        val resolutionStatus = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let(RiskResolutionStatus::valueOf)
            ?: error("风险决策状态不能为空")
        val note = parts.getOrNull(2)?.let(::decodePayloadValue).orEmpty()
        return ResolveInvestigationThreadPayload(
            threadId = threadId,
            resolutionStatus = resolutionStatus,
            note = note,
        )
    }

    private data class BeautificationPayload(
        val goal: String,
        val preferredStyle: String?,
        val explanationFocus: String?,
        val followUp: GraphBeautificationFollowUpContext?,
        val granularity: StepGranularity,
    )

    private fun parseBeautificationPayload(payload: String): BeautificationPayload {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 7)
        val goal = decodePayloadValue(parts.getOrNull(0).orEmpty())
        val preferredStyle = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val explanationFocus = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val granularity = parts.getOrNull(3)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodePayloadValue)
            ?.let { raw -> runCatching { StepGranularity.valueOf(raw) }.getOrDefault(StepGranularity.BUSINESS) }
            ?: StepGranularity.BUSINESS
        val followUpStepId = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpStepTitle = parts.getOrNull(5)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUpQuestion = parts.getOrNull(6)?.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
        val followUp = if (
            followUpStepId != null &&
            followUpStepTitle != null &&
            followUpQuestion != null
        ) {
            GraphBeautificationFollowUpContext(
                stepId = followUpStepId,
                stepTitle = followUpStepTitle,
                question = followUpQuestion,
            )
        } else {
            null
        }
        return BeautificationPayload(goal, preferredStyle, explanationFocus, followUp, granularity)
    }

    private fun parseNullableRevision(payload: String): Long? = payload.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

    private fun parseEncodedList(payload: String): List<String> {
        if (payload.isBlank()) {
            return emptyList()
        }
        return payload
            .split(',')
            .mapNotNull { raw ->
                raw.takeIf { it.isNotBlank() }?.let(::decodePayloadValue)
            }
    }

    private fun parseLayoutPositions(payload: String): Map<String, GraphLayoutPosition> {
        if (payload.isBlank()) {
            return emptyMap()
        }
        return payload
            .split('\u001e')
            .mapNotNull { entry ->
                val parts = entry.split(PAYLOAD_SEPARATOR)
                if (parts.size != 3) {
                    return@mapNotNull null
                }
                val nodeId = decodePayloadValue(parts[0])
                val x = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val y = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                nodeId to GraphLayoutPosition(x = x, y = y)
            }
            .toMap()
    }

    private fun decodePayloadValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun shouldLogFrontendTrace(payload: String): Boolean {
        if (payload.isBlank()) {
            return false
        }
        val event = FRONTEND_TRACE_EVENT_REGEX.find(payload)?.groupValues?.getOrNull(1) ?: return true
        return event !in NOISY_FRONTEND_TRACE_EVENTS
    }

    private fun dispatchBridgeAsync(
        actionLabel: String,
        messageProvider: () -> GraphEditorMessage,
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching {
                bridge.dispatch(messageProvider())
            }.onFailure { error ->
                logger.warn("异步处理前端请求失败: $actionLabel", error)
            }
        }
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

    private fun createFallbackView(entryUrl: String): JComponent {
        return JEditorPane(
            "text/html",
            """
            <html>
              <body>
                <h2>链路图编辑器外壳</h2>
                <p>前端入口：$entryUrl</p>
              </body>
            </html>
            """.trimIndent(),
        ).apply {
            isEditable = false
        }
    }

    private fun resolveFrontendHtml(): String {
        return runCatching {
            frontendAssetLoader.loadInlineEntryHtml()
        }.onFailure { error ->
            logger.warn("构建链路图前端页面失败，回退到占位页", error)
        }.getOrElse {
            createFallbackHtml(FALLBACK_ENTRY_URL)
        }
    }

    private fun createFallbackHtml(entryUrl: String): String {
        return """
        <html>
          <body>
            <h2>链路图编辑器外壳</h2>
            <p>前端入口：$entryUrl</p>
          </body>
        </html>
        """.trimIndent()
    }

    companion object {
        private const val PAYLOAD_SEPARATOR: String = "\u001F"
        private const val DEBUG_TRACE_ENV: String = "LINKGRAPH_DEBUG_TRACE"
        private const val DEBUG_INTERACTION_PROBE_ENV: String = "LINKGRAPH_DEBUG_INTERACTION_PROBE"
        const val FALLBACK_ENTRY_URL: String = "linkgraph://shell/index.html"
        const val INLINE_ENTRY_URL: String = "https://linkgraph.local/index.html"
        private val FRONTEND_TRACE_EVENT_REGEX: Regex = Regex(""""event":"([^"]+)"""")
        private val NOISY_FRONTEND_TRACE_EVENTS: Set<String> = setOf(
            "routedEdge.render",
            "jcef.runtimeProbe",
        )
        private val logger = Logger.getInstance(GraphBrowserPanel::class.java)
    }

    private fun snapshotSummary(snapshot: GraphEditorStateService.Snapshot): String {
        fun graphSummary(document: com.charmnight.linkgraph.model.GraphDocument?): String {
            if (document == null) {
                return "0/0"
            }
            return "${document.nodes.size}/${document.edges.size} sample=${document.nodes.take(6).map { it.id }}"
        }

        fun requestStateSummary(state: GraphEditorStateService.AsyncRequestState): String {
            return buildString {
                append("phase=").append(state.phase)
                append(", requestId=").append(state.requestId)
                append(", streaming=").append(state.streaming)
                append(", status=").append(state.statusMessage)
                append(", detail=").append(state.detailMessage)
                append(", error=").append(state.errorMessage)
                append(", provider=").append(state.providerLabel)
                append(", model=").append(state.model)
            }
        }

        fun generationPlanSummary(plan: com.charmnight.linkgraph.llm.GenerationPlan?): String {
            if (plan == null) {
                return "null"
            }
            return "source=${plan.source}, items=${plan.items.size}, warnings=${plan.warnings.size}, summary=${summarizePayloadText(plan.summary)}"
        }

        return buildString {
            val effectiveVisibleGraph = currentVisibleGraph(snapshot)
            val effectiveWorkingGraph = currentWorkingGraph(snapshot)
            append("lastMessageType=").append(snapshot.lastMessageType)
            append(", lastGraphSource=").append(snapshot.lastGraphSource)
            append(", analysisDisplayMode=").append(snapshot.analysisDisplayMode)
            append(", semanticRevision=").append(snapshot.semanticRevision)
            append(", layoutRevision=").append(snapshot.layoutRevision)
            append(", snapshotRevision=").append(snapshot.snapshotRevision)
            append(", selectedNodeId=").append(snapshot.selectedNodeId)
            append(", visibleGraph=").append(graphSummary(effectiveVisibleGraph))
            append(", workingGraph=").append(graphSummary(effectiveWorkingGraph))
            append(", referenceFactGraph=").append(graphSummary(snapshot.referenceFactGraph))
            append(", generationPlan=").append(generationPlanSummary(snapshot.generationPlan))
            append(", generationPlanRequestState=").append(requestStateSummary(snapshot.generationPlanRequestState))
            append(", feedback=").append(snapshot.operationFeedback?.message)
        }
    }

    private fun snapshotDeltaSummary(
        previous: GraphEditorStateService.Snapshot,
        next: GraphEditorStateService.Snapshot,
    ): String {
        return buildString {
            append("visible{").append(graphDeltaSummary(currentVisibleGraph(previous), currentVisibleGraph(next))).append("}")
            append(", working{").append(graphDeltaSummary(currentWorkingGraph(previous), currentWorkingGraph(next))).append("}")
        }
    }

    private fun graphDeltaSummary(
        previous: com.charmnight.linkgraph.model.GraphDocument?,
        next: com.charmnight.linkgraph.model.GraphDocument?,
    ): String {
        val previousNodes = previous?.nodes?.associateBy { it.id }.orEmpty()
        val nextNodes = next?.nodes?.associateBy { it.id }.orEmpty()
        val added = nextNodes.keys.subtract(previousNodes.keys)
        val removed = previousNodes.keys.subtract(nextNodes.keys)
        val retitled = nextNodes.keys.intersect(previousNodes.keys)
            .mapNotNull { nodeId ->
                val before = previousNodes[nodeId] ?: return@mapNotNull null
                val after = nextNodes[nodeId] ?: return@mapNotNull null
                if (before.title == after.title) {
                    null
                } else {
                    "$nodeId:${summarizePayloadText(before.title)} -> ${summarizePayloadText(after.title)}"
                }
            }
        return buildString {
            append("added=").append(added.take(4))
            append(", removed=").append(removed.take(4))
            append(", retitled=").append(retitled.take(4))
        }
    }

    private fun runtimeTrace(message: () -> String) {
        if (debugTracingEnabled) {
            logger.warn(message())
        }
    }
}
