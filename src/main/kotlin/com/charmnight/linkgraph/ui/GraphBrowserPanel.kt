package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
    constructor(project: Project) : this(project, ClasspathFrontendAssetLoader.runtime())

    private data class PendingTransportSnapshot(
        val snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        val renderedSnapshotScript: GraphEditorTransportSliceRenderer.RenderedSnapshotScript,
    )

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
    private val transportRenderScheduler = GraphEditorTransportRenderScheduler(
        renderExecutor = AppExecutorUtil.getAppExecutorService(),
        dispatchExecutor = { action ->
            ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        },
    )
    private val pendingTransportSnapshotsLock = Any()
    private val pendingTransportSnapshots = mutableMapOf<Long, PendingTransportSnapshot>()
    private val entryUrl: String = INLINE_ENTRY_URL
    private val frontendHtml: String = resolveFrontendHtml()
    @Volatile
    private var renderedEntryHtml: String? = null
    private val browser: JBCefBrowser? = createBrowser()
    private val frontendAssetRegistrar: GraphBrowserFrontendAssetRegistrar? = browser?.let { currentBrowser ->
        GraphBrowserFrontendAssetRegistrar(
            browser = currentBrowser,
            frontendAssetLoader = frontendAssetLoader,
            entryUrl = entryUrl,
            entryHtmlProvider = ::currentRenderedEntryHtml,
        )
    }
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
        sliceRenderer = sliceRenderer,
        transportState = transportState,
        debugTracingEnabled = debugTracingEnabled,
        browserLoadedProvider = { browserLoaded },
        dispatchedSnapshotProvider = { lastDispatchedSnapshot },
        pendingSnapshotConsumer = ::commitPendingTransportSnapshot,
        lastDispatchedSnapshotUpdater = ::updateLastDispatchedSnapshot,
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
    @Volatile
    private var lastDispatchedSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = bridge.currentState()
    init {
        frontendAssetRegistrar?.registerHandlers()
        bridgeRegistrar?.registerHandlers()
        browserLifecycle?.install()
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        val initialSnapshot = lastDispatchedSnapshot
        renderedEntryHtml = renderEntryHtml(initialSnapshot)
        if (debugTracingEnabled) {
            logger.warn(
                "链路图 JCEF 初始化: debugTrace=$debugTracingEnabled, interactionProbe=$interactionProbeEnabled, " +
                    "entryUrl=$entryUrl, frontendHtmlChars=${frontendHtml.length}, " +
                    "renderedEntryHtmlChars=${renderedEntryHtml?.length ?: 0}, hasBrowser=${browser != null}",
            )
        }
        debugLazy(logger.isDebugEnabled, logger::debug) { "准备加载链路图前端入口: $entryUrl" }
        bridge.onFrontendLoaded(entryUrl)
        browser?.loadURL(entryUrl)
    }

    private fun currentRenderedEntryHtml(): String =
        renderedEntryHtml ?: renderEntryHtml(lastDispatchedSnapshot).also { renderedEntryHtml = it }

    private fun renderEntryHtml(initialSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): String {
        // 首次入口请求时就内嵌 bootstrap，避免前端先渲染一版演示态再切到真实项目状态。
        val initialArtifactRefs = sliceRenderer.prepareBootstrapSnapshotArtifacts(initialSnapshot)
        return pageRenderer.render(
            frontendHtml,
            transportState.sessionId,
            initialSnapshot,
            artifactRefs = initialArtifactRefs,
            debugTracingEnabled = debugTracingEnabled,
        )
    }

    fun currentEntryUrl(): String = entryUrl

    fun bridge(): GraphEditorBridge = bridge

    fun syncFromProjectState() {
        if (disposed) {
            return
        }
        if (browser == null) {
            return
        }
        val snapshot = bridge.currentState()
        val previousSnapshot = lastDispatchedSnapshot
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始向前端同步链路图状态: ${GraphBrowserDiagnostics.snapshotSummary(snapshot)}"
        }
        runtimeTrace {
            "开始向前端同步链路图状态: ${GraphBrowserDiagnostics.snapshotSummary(snapshot)}, delta=${GraphBrowserDiagnostics.snapshotDeltaSummary(previousSnapshot, snapshot)}"
        }
        transportRenderScheduler.schedule(
            revision = snapshot.snapshotRevision,
            render = {
                if (disposed) {
                    null
                } else {
                    sliceRenderer.renderIncrementalSnapshotScript(
                        sessionId = transportState.sessionId,
                        previousSnapshot = previousSnapshot,
                        snapshot = snapshot,
                    )
                }
            },
            dispatch = { transportScript ->
                dispatchRenderedSnapshot(snapshot, transportScript)
            },
        )
    }

    private fun dispatchRenderedSnapshot(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        renderedSnapshotScript: GraphEditorTransportSliceRenderer.RenderedSnapshotScript,
    ) {
        if (disposed) {
            return
        }
        synchronized(pendingTransportSnapshotsLock) {
            pendingTransportSnapshots.keys.removeAll { pendingRevision ->
                pendingRevision < snapshot.snapshotRevision
            }
            pendingTransportSnapshots[snapshot.snapshotRevision] = PendingTransportSnapshot(
                snapshot = snapshot,
                renderedSnapshotScript = renderedSnapshotScript,
            )
        }
        val availability = transportState.onSnapshotAvailable(
            revision = snapshot.snapshotRevision,
            script = renderedSnapshotScript.script,
        )
        if (!availability.accepted) {
            synchronized(pendingTransportSnapshotsLock) {
                pendingTransportSnapshots.remove(snapshot.snapshotRevision)
            }
        }
        transportDispatcher.executeSnapshotScript(availability.transport, "sync:${snapshot.lastMessageType ?: "unknown"}")
    }

    private fun commitPendingTransportSnapshot(
        revision: Long,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot? {
        val pending = synchronized(pendingTransportSnapshotsLock) {
            pendingTransportSnapshots.remove(revision)
        } ?: return null
        sliceRenderer.commitRenderedSnapshot(pending.renderedSnapshotScript)
        return pending.snapshot
    }

    private fun updateLastDispatchedSnapshot(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) {
        lastDispatchedSnapshot = snapshot
        renderedEntryHtml = null
    }

    private fun clearPendingTransportSnapshots() {
        synchronized(pendingTransportSnapshotsLock) {
            pendingTransportSnapshots.clear()
        }
    }

    override fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        browserLoaded = false
        clearPendingTransportSnapshots()
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
            frontendAssetLoader.loadEntryHtml()
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
