package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
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
    private val debugTracingEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled(DEBUG_TRACE_ENV)
    private val runtimeTraceSink: (((() -> String) -> Unit))? =
        if (debugTracingEnabled) {
            { message -> logger.warn(message()) }
        } else {
            null
        }
    private val pageRenderer = GraphEditorPageRenderer()
    private val sliceRenderer = GraphEditorTransportSliceRenderer(
        pageRenderer = pageRenderer,
        runtimeTrace = runtimeTraceSink,
    )
    private val pendingTransportSnapshots = mutableMapOf<Long, com.charmnight.linkgraph.ui.GraphEditorStateSnapshot>()
    private val entryUrl: String = INLINE_ENTRY_URL
    private val frontendHtml: String = resolveFrontendHtml()
    private val browser: JBCefBrowser? = createBrowser()
    private val interactionProbeEnabled: Boolean =
        debugTracingEnabled &&
            LinkGraphDebugEnvironment.isEnabled(DEBUG_INTERACTION_PROBE_ENV)
    private val bridgeRegistrar: GraphBrowserBridgeRegistrar? = browser?.let { currentBrowser ->
        GraphBrowserBridgeRegistrar(
            browser = currentBrowser,
            bridge = bridge,
            logger = logger,
            debugTracingEnabled = debugTracingEnabled,
            interactionProbeEnabled = interactionProbeEnabled,
            dispatchArtifactSlice = { artifactIds -> transportDispatcher.dispatchArtifactSlice(artifactIds) },
            dispatchBridgeAsync = ::dispatchBridgeAsync,
            shouldLogFrontendTrace = ::shouldLogFrontendTrace,
            runtimeTrace = if (debugTracingEnabled) {
                { message -> logger.warn(message) }
            } else {
                null
            },
        )
    }
    private val transportDispatcher = GraphBrowserTransportDispatcher(
        browserProvider = { browser },
        bridge = bridge,
        sliceRenderer = sliceRenderer,
        transportState = transportState,
        debugTracingEnabled = debugTracingEnabled,
        browserLoadedProvider = { browserLoaded },
        pendingSnapshotConsumer = { revision -> pendingTransportSnapshots.remove(revision) },
        lastDispatchedSnapshotUpdater = { snapshot -> lastDispatchedSnapshot = snapshot },
        runtimeTrace = runtimeTraceSink,
    )
    private val browserLifecycle: GraphBrowserLifecycle? = browser?.let { currentBrowser ->
        GraphBrowserLifecycle(
            browser = currentBrowser,
            onMainFrameLoadStarted = { cefBrowser ->
                debugLazy(logger.isDebugEnabled, logger::debug) { "JCEF 开始加载页面: ${cefBrowser.url}" }
                browserLoaded = false
                transportState.onMainFrameLoadStarted()
            },
            onMainFrameLoadEnded = { cefBrowser, httpStatusCode ->
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "JCEF 页面加载完成: url=${cefBrowser.url}, status=$httpStatusCode"
                }
                browserLoaded = true
                debugLazy(logger.isDebugEnabled, logger::debug) { "开始注入链路图 bridge 脚本" }
                cefBrowser.executeJavaScript(buildBridgeScript(), cefBrowser.url, 0)
                transportDispatcher.executeSnapshotScript(transportState.onMainFrameLoadEnded(), "loadEnd")
            },
            onMainFrameLoadError = { errorCode, errorText, failedUrl ->
                logger.warn("JCEF 页面加载失败: url=$failedUrl, errorCode=$errorCode, errorText=$errorText")
            },
            onConsoleMessage = { level, message, source, line ->
                logger.warn("JCEF console[$level] $message ($source:$line)")
            },
        )
    }
    @Volatile
    private var browserLoaded: Boolean = false
    @Volatile
    private var disposed: Boolean = false
    private var lastDispatchedSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = bridge.currentState()
    init {
        bridgeRegistrar?.registerHandlers()
        browserLifecycle?.install()
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        if (debugTracingEnabled) {
            logger.warn(
                "链路图 JCEF 初始化: debugTrace=$debugTracingEnabled, interactionProbe=$interactionProbeEnabled, " +
                    "entryUrl=$entryUrl, frontendHtmlChars=${frontendHtml.length}, hasBrowser=${browser != null}",
            )
        }
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
            debugTracingEnabled = debugTracingEnabled,
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
            "开始向前端同步链路图状态: ${GraphBrowserDiagnostics.snapshotSummary(snapshot)}"
        }
        runtimeTrace {
            "开始向前端同步链路图状态: ${GraphBrowserDiagnostics.snapshotSummary(snapshot)}, delta=${GraphBrowserDiagnostics.snapshotDeltaSummary(lastDispatchedSnapshot, snapshot)}"
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
        transportDispatcher.executeSnapshotScript(transportToExecute, "sync:${snapshot.lastMessageType ?: "unknown"}")
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

    private fun handleFrontendReady(lastAppliedRevision: Long?) {
        transportDispatcher.executeSnapshotScript(transportState.onFrontendReady(lastAppliedRevision), "frontendReady")
    }

    private fun handleSnapshotAck(revision: Long) {
        transportDispatcher.executeSnapshotScript(transportState.onSnapshotAcknowledged(revision), "snapshotAck:$revision")
    }

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

    private fun buildBridgeScript(): String = bridgeRegistrar?.buildBridgeScript().orEmpty()

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

    private fun runtimeTrace(message: () -> String) {
        if (debugTracingEnabled) {
            logger.warn(message())
        }
    }
}
