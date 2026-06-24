package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 主题图工作流所需的所有外部依赖集合。
 *
 * 把工作流需要的端口、工厂、回调等聚合到一个数据类中，
 * 让工作流类只接收一个参数即可，避免冗长的构造器签名。
 * 同时也便于测试时构造桩依赖。
 */
internal data class SubjectGraphWorkflowDependencies(
    /** 当前项目。 */
    val project: Project,
    /** 编辑器快照提供者。 */
    val snapshotProvider: EditorSnapshotProvider,
    /** 异步请求生命周期支持。 */
    val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 主题定位器提供者；惰性取得避免早期初始化顺序问题。 */
    val subjectLocatorProvider: () -> SubjectLocator,
    /** 语义分析器提供者。 */
    val semanticAnalyzerProvider: () -> SemanticAnalyzer,
    /** 分析结果工厂提供者。 */
    val analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    /** 代码主题句柄工厂。 */
    val codeSubjectHandleFactory: CodeSubjectHandleFactory,
    /** 工作台图提交器。 */
    val workspaceGraphCommitter: WorkspaceGraphCommitter,
    /** 应用事件接收器。 */
    val eventSink: GraphEditorApplicationEventSink,
    /** 失效 QA 请求的回调。 */
    val invalidateQaRequests: () -> Unit,
    /** 记录图诊断信息的回调。 */
    val logGraphDiagnostics: (String, GraphDocument?) -> Unit,
    /** 运行时埋点回调；可空表示未启用。 */
    val runtimeTrace: ((() -> String) -> Unit)?,
    /** 日志器。 */
    val logger: Logger,
) {
    /** 便捷方法：派发一个应用事件。 */
    fun emit(event: GraphEditorApplicationEvent) {
        eventSink.emit(event)
    }

    /**
     * 便捷方法：派发一条反馈消息。
     *
     * @param level 反馈等级
     * @param message 反馈文案
     * @param preservePreviousStatusKind 是否保留上一次的状态种类；默认不保留
     */
    fun emitFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preservePreviousStatusKind: Boolean = false,
    ) {
        emit(GraphEditorApplicationEvent.Feedback(level, message, preservePreviousStatusKind))
    }
}
