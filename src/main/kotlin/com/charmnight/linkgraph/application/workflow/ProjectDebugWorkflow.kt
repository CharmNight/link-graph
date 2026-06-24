package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.intellij.openapi.diagnostic.Logger

/**
 * 项目调试工作流。
 *
 * 把"调试相关的展示模式切换"与"调试用图加载"封装为单独的工作流，
 * 避免污染主流程。仅在调试 run configuration 下被启用。
 *
 * @param logger 日志器
 * @param debugGraphFactory 调试图工厂；按模式名生成示例图
 * @param subjectGraphWorkflow 主题图工作流；用于复用其载图能力
 * @param invalidateQaRequests 失效 QA 请求的回调
 * @param eventSink 应用事件接收器
 */
internal class ProjectDebugWorkflow(
    private val logger: Logger,
    private val debugGraphFactory: DebugGraphFactory,
    private val subjectGraphWorkflow: SubjectGraphWorkflow,
    private val invalidateQaRequests: () -> Unit,
    private val eventSink: GraphEditorApplicationEventSink,
) {
    /**
     * 如果环境变量指定了调试展示模式，提前设置好。
     * 这样后续自动载图时会直接进入该模式，避免界面在默认模式与目标模式之间闪烁。
     *
     * @param envName 环境变量名
     */
    fun prepareDebugRequestedAnalysisDisplayModeIfPresent(envName: String) {
        val displayMode = resolveDebugRequestedAnalysisDisplayMode(envName) ?: return
        // 已经是该模式时不需要任何动作
        if (!subjectGraphWorkflow.prepareRequestedAnalysisDisplayMode(displayMode)) {
            return
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "检测到调试展示模式环境变量 $envName=$displayMode，将在自动载图时优先展示该模式"
        }
    }

    /**
     * 按模式名加载调试链路图。
     *
     * @param mode 调试图模式字符串（由 DebugGraphFactory 解析）
     */
    fun loadDebugGraph(mode: String) {
        val debugGraph = debugGraphFactory.create(mode)
        if (debugGraph == null) {
            // 未知模式直接告警并返回，避免抛异常打断调试会话
            logger.warn("未知的调试自动载图模式: $mode")
            return
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始注入调试链路图: mode=$mode, summary=${debugGraph.summary}"
        }
        // 调试图属于全新上下文，需要把 QA 请求一并失效
        invalidateQaRequests()
        eventSink.emit(
            GraphEditorApplicationEvent.DebugGraphLoaded(
                graph = debugGraph.graph,
                source = "debug:$mode",
                selectedMethodSignature = debugGraph.anchorSignature,
                summary = debugGraph.summary,
            ),
        )
    }

    /** 异步加载调试方法图（按方法签名）。委托给主题图工作流。 */
    fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        subjectGraphWorkflow.loadDebugMethodGraphBySignatureAsync(signature)
    }

    /**
     * 从环境变量解析调试展示模式。
     * 解析失败（不是合法的 AnalysisDisplayMode）时返回 null 并打告警。
     */
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
