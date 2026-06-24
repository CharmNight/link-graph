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

    /** 已渲染但尚未被前端确认的快照暂存，便于在前端就绪后回放或在前端 ACK 时清理。 */
    private data class PendingTransportSnapshot(
        val snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        val renderedSnapshotScript: GraphEditorTransportSliceRenderer.RenderedSnapshotScript,
    )

    /** 跟踪前端就绪状态、最近确认版本等传输层运行时信息。 */
    private val transportState = GraphBrowserTransportState()
    /** 与前端通信的双向桥：负责发送快照脚本、接收前端的就绪/ACK 回调。 */
    private val bridge: GraphEditorBridge = GraphEditorBridge(
        project = project,
        onFrontendReady = ::handleFrontendReady,
        onSnapshotAck = ::handleSnapshotAck,
    )
    /** 是否启用运行时调试跟踪，开启后会向日志输出大量传输细节，仅供排查使用。 */
    private val debugTracingEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled(DEBUG_TRACE_ENV)
    /** 调试跟踪开启时使用的日志输出回调，未开启时为 null 以避免多余开销。 */
    private val runtimeTraceSink: (((() -> String) -> Unit))? =
        if (debugTracingEnabled) {
            { message -> logger.warn(message()) }
        } else {
            null
        }
    /** 负责渲染入口 HTML 页面（含内嵌初始快照）的页面渲染器。 */
    private val pageRenderer = GraphEditorPageRenderer()
    /** 负责把快照渲染成可下发到前端的增量传输脚本。 */
    private val sliceRenderer = GraphEditorTransportSliceRenderer(
        pageRenderer = pageRenderer,
        runtimeTrace = runtimeTraceSink,
    )
    /** 调度快照渲染与下发任务，避免阻塞 EDT 并保证先后顺序。 */
    private val transportRenderScheduler = GraphEditorTransportRenderScheduler(
        renderExecutor = AppExecutorUtil.getAppExecutorService(),
        dispatchExecutor = { action ->
            ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        },
    )
    /** 保护待下发快照 map 的锁，避免 EDT 与后台线程并发修改。 */
    private val pendingTransportSnapshotsLock = Any()
    /** 暂存已渲染但尚未确认的快照，按 revision 索引。 */
    private val pendingTransportSnapshots = mutableMapOf<Long, PendingTransportSnapshot>()
    /** 前端入口使用的虚拟 URL，配合自定义 scheme handler 完成资源加载。 */
    private val entryUrl: String = INLINE_ENTRY_URL
    /** 加载到的前端入口 HTML 文本，渲染前可能被替换为降级页面。 */
    private val frontendHtml: String = resolveFrontendHtml()
    /** 缓存的入口 HTML，当快照变化时会被清空以便下次重新生成。 */
    @Volatile
    private var renderedEntryHtml: String? = null
    /** 真正承载前端的 JCEF 浏览器实例，当 JCEF 不可用时为 null。 */
    private val browser: JBCefBrowser? = createBrowser()
    /** 注册自定义 scheme handler，把前端静态资源请求路由到 classpath 资源。 */
    private val frontendAssetRegistrar: GraphBrowserFrontendAssetRegistrar? = browser?.let { currentBrowser ->
        GraphBrowserFrontendAssetRegistrar(
            browser = currentBrowser,
            frontendAssetLoader = frontendAssetLoader,
            entryUrl = entryUrl,
            entryHtmlProvider = ::currentRenderedEntryHtml,
        )
    }
    /** 是否在前端注入交互探测脚本，用于排查用户与图谱的交互细节。 */
    private val interactionProbeEnabled: Boolean =
        debugTracingEnabled &&
            LinkGraphDebugEnvironment.isEnabled(DEBUG_INTERACTION_PROBE_ENV)
    /** 注册 JS-Java 桥，并把 Java 端回调绑定到前端可调用的全局对象。 */
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
    /** 实际执行 JS 脚本下发并维护“已下发快照”状态的派发器。 */
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
    /** JCEF 浏览器生命周期监听器：处理页面开始/结束加载、加载失败与控制台日志。 */
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
    /** 标记当前面板是否已被释放，避免释放后继续向前端派发任务。 */
    @Volatile
    private var disposed: Boolean = false
    /** 最近一次成功派发到前端的快照，作为下次增量计算的基线。 */
    @Volatile
    private var lastDispatchedSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = bridge.currentState()
    init {
        // 安装自定义 scheme、桥接注册与生命周期监听器，使浏览器与后端通信完整可用
        frontendAssetRegistrar?.registerHandlers()
        bridgeRegistrar?.registerHandlers()
        browserLifecycle?.install()
        // JCEF 不可用时退化到 Swing 编辑器面板，保证至少能显示入口信息
        add(browser?.component ?: createFallbackView(entryUrl), BorderLayout.CENTER)
        val initialSnapshot = lastDispatchedSnapshot
        // 预渲染入口 HTML，使首次加载就能拿到正确的初始状态而不是演示态
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

    /** 返回当前入口 HTML，若缓存被清空则按最近快照重新渲染并写回缓存。 */
    private fun currentRenderedEntryHtml(): String =
        renderedEntryHtml ?: renderEntryHtml(lastDispatchedSnapshot).also { renderedEntryHtml = it }

    /**
     * 把前端 HTML 与初始快照拼装成完整入口页面，避免前端先以演示态启动再切换，
     * 这样用户第一次看到的就是真实的项目状态。
     */
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

    /** 暴露给外部的入口 URL，主要用于诊断或日志输出。 */
    fun currentEntryUrl(): String = entryUrl

    /** 返回当前桥接实例，外部可借此注册消息处理器或主动发起请求。 */
    fun bridge(): GraphEditorBridge = bridge

    /**
     * 把后端当前快照同步到前端：调度渲染、生成增量脚本并派发，
     * 内部处理了释放检测与基线快照对比。
     */
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

    /**
     * 把渲染好的快照脚本登记为待下发并在合适时机派发：先清理更早 revision 的暂存，
     * 通知传输层该脚本可下发，若被拒绝则移除暂存以避免泄漏。
     */
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

    /** 在前端 ACK 后从暂存区取出对应 revision 的快照并提交渲染缓存，返回实际生效的快照。 */
    private fun commitPendingTransportSnapshot(
        revision: Long,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot? {
        val pending = synchronized(pendingTransportSnapshotsLock) {
            pendingTransportSnapshots.remove(revision)
        } ?: return null
        sliceRenderer.commitRenderedSnapshot(pending.renderedSnapshotScript)
        return pending.snapshot
    }

    /** 在快照成功下发后更新基线，并清空入口 HTML 缓存以保证下次重新生成。 */
    private fun updateLastDispatchedSnapshot(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) {
        lastDispatchedSnapshot = snapshot
        renderedEntryHtml = null
    }

    /** 清空所有未确认的待下发快照，用于释放或重置传输状态。 */
    private fun clearPendingTransportSnapshots() {
        synchronized(pendingTransportSnapshotsLock) {
            pendingTransportSnapshots.clear()
        }
    }

    /** 释放浏览器、清理暂存与子组件，禁止外部多次调用造成重复释放。 */
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

    /** 创建 JCEF 浏览器实例，当前运行环境不支持 JCEF 时返回 null。 */
    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }
        return runCatching {
            JBCefBrowser()
        }.getOrNull()
    }

    /** 前端就绪后由 bridge 回调，根据前端报告的最后应用版本派发补齐脚本。 */
    private fun handleFrontendReady(lastAppliedRevision: Long?) {
        transportDispatcher.executeSnapshotScript(transportState.onFrontendReady(lastAppliedRevision), "frontendReady")
    }

    /** 前端确认某个 revision 已应用后，触发传输层推进到下一个待下发脚本。 */
    private fun handleSnapshotAck(revision: Long) {
        transportDispatcher.executeSnapshotScript(transportState.onSnapshotAcknowledged(revision), "snapshotAck:$revision")
    }

    /** 判断前端跟踪日志是否需要写出到日志，过滤掉过于频繁的事件以减少噪声。 */
    private fun shouldLogFrontendTrace(payload: String): Boolean {
        if (payload.isBlank()) {
            return false
        }
        val event = FRONTEND_TRACE_EVENT_REGEX.find(payload)?.groupValues?.getOrNull(1) ?: return true
        return event !in NOISY_FRONTEND_TRACE_EVENTS
    }

    /** 把可能耗时的前端响应放到后台线程处理，避免阻塞 JCEF 的 JS 回调线程。 */
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

    /** 让桥接注册器生成在页面加载完成时注入的 JS 桥接脚本。 */
    private fun buildBridgeScript(): String = bridgeRegistrar?.buildBridgeScript().orEmpty()

    /** 当 JCEF 不可用时使用 Swing 编辑器面板作为占位视图，避免整个工具窗口空白。 */
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

    /** 从资源加载器读取前端入口 HTML，加载失败时回退到最简占位页面。 */
    private fun resolveFrontendHtml(): String {
        return runCatching {
            frontendAssetLoader.loadEntryHtml()
        }.onFailure { error ->
            logger.warn("构建链路图前端页面失败，回退到占位页", error)
        }.getOrElse {
            createFallbackHtml(FALLBACK_ENTRY_URL)
        }
    }

    /** 生成降级用的最小 HTML 页面，仅展示入口 URL，供前端无法加载时使用。 */
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
        // 控制是否打开运行时传输细节跟踪的环境变量名
        private const val DEBUG_TRACE_ENV: String = "LINKGRAPH_DEBUG_TRACE"
        // 控制是否注入前端交互探测脚本的环境变量名
        private const val DEBUG_INTERACTION_PROBE_ENV: String = "LINKGRAPH_DEBUG_INTERACTION_PROBE"
        // 资源加载失败时使用的降级入口 URL
        const val FALLBACK_ENTRY_URL: String = "linkgraph://shell/index.html"
        // 默认前端入口 URL，配合自定义 scheme handler 使用
        const val INLINE_ENTRY_URL: String = "https://linkgraph.local/index.html"
        // 从前端跟踪 payload 中提取事件名的正则
        private val FRONTEND_TRACE_EVENT_REGEX: Regex = Regex(""""event":"([^"]+)"""")
        // 高频且对排查价值不大的前端事件，命中后不再写日志
        private val NOISY_FRONTEND_TRACE_EVENTS: Set<String> = setOf(
            "routedEdge.render",
            "jcef.runtimeProbe",
        )
        private val logger = Logger.getInstance(GraphBrowserPanel::class.java)
    }

    /** 仅在调试跟踪开启时输出消息到日志，便于排查传输链路问题。 */
    private fun runtimeTrace(message: () -> String) {
        if (debugTracingEnabled) {
            logger.warn(message())
        }
    }
}
