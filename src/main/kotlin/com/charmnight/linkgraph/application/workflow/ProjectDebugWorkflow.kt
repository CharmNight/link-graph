package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.intellij.openapi.diagnostic.Logger

internal class ProjectDebugWorkflow(
    private val logger: Logger,
    private val debugGraphFactory: DebugGraphFactory,
    private val subjectGraphWorkflow: SubjectGraphWorkflow,
    private val invalidateAuditRequests: () -> Unit,
    private val eventSink: GraphEditorApplicationEventSink,
) {
    fun prepareDebugRequestedAnalysisDisplayModeIfPresent(envName: String) {
        val displayMode = resolveDebugRequestedAnalysisDisplayMode(envName) ?: return
        if (!subjectGraphWorkflow.prepareRequestedAnalysisDisplayMode(displayMode)) {
            return
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "检测到调试展示模式环境变量 $envName=$displayMode，将在自动载图时优先展示该模式"
        }
    }

    fun loadDebugGraph(mode: String) {
        val debugGraph = debugGraphFactory.create(mode)
        if (debugGraph == null) {
            logger.warn("未知的调试自动载图模式: $mode")
            return
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始注入调试链路图: mode=$mode, summary=${debugGraph.summary}"
        }
        invalidateAuditRequests()
        eventSink.emit(
            GraphEditorApplicationEvent.DebugGraphLoaded(
                graph = debugGraph.graph,
                source = "debug:$mode",
                selectedMethodSignature = debugGraph.anchorSignature,
                summary = debugGraph.summary,
            ),
        )
    }

    fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        subjectGraphWorkflow.loadDebugMethodGraphBySignatureAsync(signature)
    }

    private fun resolveDebugRequestedAnalysisDisplayMode(envName: String): AnalysisDisplayMode? {
        val rawValue = System.getenv(envName)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            AnalysisDisplayMode.valueOf(rawValue.uppercase())
        }.getOrElse { error ->
            logger.warn("无效的调试展示模式环境变量: $envName=$rawValue", error)
            null
        }
    }
}
