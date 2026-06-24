package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 主题图工作流的运行时状态。
 *
 * 记录当前投影设置、请求的展示模式、上次分析的主题与结果。
 * 使用 @Volatile 让多线程读写安全（但语义上仍是"最后写入获胜"）。
 */
internal class SubjectGraphWorkflowState {
    /** 当前交互式投影设置（深度、邻居数等）。 */
    @Volatile
    var projectionSettings: InteractiveProjectionSettings = InteractiveProjectionSettings()

    /** 用户请求切换到的展示模式；初始为流程图。 */
    @Volatile
    var requestedDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FLOWCHART

    /** 最近一次分析的主题句柄；用于判断是否需要重新分析。 */
    @Volatile
    var lastAnalyzedSubjectHandle: SubjectHandle? = null

    /** 最近一次分析的完整结果；可作为下次增量分析的起点。 */
    @Volatile
    var lastSemanticAnalysisResult: SemanticAnalysisResult? = null

    /** 清空分析缓存。切换主题或重新加载时调用。 */
    fun clearLastAnalysisCache() {
        lastAnalyzedSubjectHandle = null
        lastSemanticAnalysisResult = null
    }

    /** 重置投影设置为默认值。 */
    fun resetProjectionSettings() {
        projectionSettings = InteractiveProjectionSettings()
    }

    /**
     * 准备切换到指定展示模式。
     *
     * @param displayMode 目标模式
     * @return true 表示实际发生了切换；false 表示已是该模式（无变化）
     */
    fun prepareRequestedDisplayMode(displayMode: AnalysisDisplayMode): Boolean {
        if (requestedDisplayMode == displayMode) {
            return false
        }
        requestedDisplayMode = displayMode
        return true
    }
}
