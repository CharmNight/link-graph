package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.intellij.ui.jcef.JBCefBrowser

/**
 * 负责把"快照脚本/分片脚本"实际投递到 JCEF 浏览器执行，并记录必要的渲染追踪信息。
 *
 * 通过传入的各种 provider/consumer 与外部状态解耦，使其专注于"渲染 -> 执行 -> 追踪"这一流水线。
 */
internal class GraphBrowserTransportDispatcher(
    /** 获取当前 JBCefBrowser 实例，不存在时返回 null。 */
    private val browserProvider: () -> JBCefBrowser?,
    /** 把状态切片渲染成可执行脚本的渲染器。 */
    private val sliceRenderer: GraphEditorTransportSliceRenderer,
    /** 维护分发修订号、确认等传输级状态的持有对象。 */
    private val transportState: GraphBrowserTransportState,
    /** 是否开启调试追踪（影响运行时探针调度）。 */
    private val debugTracingEnabled: Boolean,
    /** 查询浏览器主框架是否已加载完成。 */
    private val browserLoadedProvider: () -> Boolean,
    /** 提供当前"已下发"的快照，用于构造分片包。 */
    private val dispatchedSnapshotProvider: () -> GraphEditorStateSnapshot,
    /** 按修订号查询尚未确认的待发送快照。 */
    private val pendingSnapshotConsumer: (Long) -> GraphEditorStateSnapshot?,
    /** 在成功下发某快照后，更新"最近一次已下发快照"。 */
    private val lastDispatchedSnapshotUpdater: (GraphEditorStateSnapshot) -> Unit,
    /** 可选的运行时追踪日志写入器，为空时跳过追踪记录。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    /**
     * 在浏览器中执行一次快照脚本。
     *
     * 流程：取浏览器 -> 校验脚本 -> 取对应快照 -> 更新已下发快照 -> 执行 JS -> 追踪 + 探针。
     */
    fun executeSnapshotScript(
        transport: GraphBrowserTransportState.DispatchedTransport?,
        reason: String,
    ) {
        // 没有可用浏览器时直接放弃本次投递。
        val currentBrowser = browserProvider() ?: return
        // 没有传输内容则没有可执行脚本，跳过。
        val dispatchedTransport = transport ?: return
        // 空脚本无需触发 JS 执行。
        if (dispatchedTransport.script.isBlank()) {
            return
        }
        // 取不到对应快照意味着状态已被消费/清理，无法继续。
        val dispatchedSnapshot = pendingSnapshotConsumer(dispatchedTransport.revision) ?: return
        // 在执行前先更新最近一次已下发快照，保证外部视图与即将执行的脚本一致。
        lastDispatchedSnapshotUpdater(dispatchedSnapshot)
        val executeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(
            dispatchedTransport.script,
            currentBrowser.cefBrowser.url,
            0,
        )
        traceStage(
            stage = "transport.executeJavaScript",
            startedAtNanos = executeStartedAt,
        ) {
            listOf(
                "reason=$reason",
                "revision=${dispatchedTransport.revision}",
                "scriptChars=${dispatchedTransport.script.length}",
                "browserLoaded=${browserLoadedProvider()}",
            )
        }
        scheduleRuntimeProbe(reason)
    }

    /**
     * 向浏览器单独下发一份"制品切片"脚本。
     *
     * 与全量快照不同，这里只投递用户请求的具体制品内容，附带当前快照修订号供前端比对。
     */
    fun dispatchArtifactSlice(artifactIds: List<String>) {
        val currentBrowser = browserProvider() ?: return
        // 空请求直接跳过，避免无意义渲染。
        if (artifactIds.isEmpty()) {
            return
        }
        // 渲染制品内容；命中为空则说明没有可下发数据。
        val artifactContents = sliceRenderer.artifactContents(artifactIds)
        if (artifactContents.isEmpty()) {
            return
        }
        val dispatchedSnapshot = dispatchedSnapshotProvider()
        val renderStartedAt = System.nanoTime()
        val script = sliceRenderer.renderScript(
            listOf(
                GraphEditorTransportEnvelope.ArtifactSlice(
                    sessionId = transportState.sessionId,
                    revision = dispatchedSnapshot.snapshotRevision,
                    state = ArtifactContentsSliceDto(
                        artifactContents = artifactContents,
                        snapshotRevision = dispatchedSnapshot.snapshotRevision,
                        lastMessageType = "artifactSlice",
                    ),
                ),
            ),
        )
        traceStage(
            stage = "transport.artifactSlice.render",
            startedAtNanos = renderStartedAt,
        ) {
            listOf(
                "requested=${artifactIds.size}",
                "found=${artifactContents.size}",
                "revision=${dispatchedSnapshot.snapshotRevision}",
                "scriptChars=${script.length}",
            )
        }
        val executeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(script, currentBrowser.cefBrowser.url, 0)
        traceStage(
            stage = "transport.artifactSlice.executeJavaScript",
            startedAtNanos = executeStartedAt,
        ) {
            listOf(
                "found=${artifactContents.size}",
                "revision=${dispatchedSnapshot.snapshotRevision}",
                "scriptChars=${script.length}",
            )
        }
    }

    /**
     * 调度一次运行时探针脚本，用于在调试模式下采集前端运行态信息。
     *
     * 仅在开启调试追踪、浏览器存在且已加载时才真正执行。
     */
    private fun scheduleRuntimeProbe(reason: String) {
        // 未启用调试追踪则不安排探针。
        if (!debugTracingEnabled) {
            return
        }
        val currentBrowser = browserProvider() ?: return
        // 浏览器尚未加载完成时，探针脚本无法稳定运行，跳过。
        if (!browserLoadedProvider()) {
            return
        }
        val probeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(
            GraphBrowserDebugProbe.buildRuntimeProbeScript(reason),
            currentBrowser.cefBrowser.url,
            0,
        )
        traceStage(
            stage = "transport.runtimeProbe.schedule",
            startedAtNanos = probeStartedAt,
        ) {
            listOf("reason=$reason")
        }
    }

    /**
     * 把某个阶段的耗时与明细写入运行时追踪日志。
     *
     * 当未提供 runtimeTrace 时直接跳过，避免空记录开销。
     */
    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }
}
