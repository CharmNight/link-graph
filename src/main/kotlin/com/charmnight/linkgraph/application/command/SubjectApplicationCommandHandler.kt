package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.workflow.SubjectGraphWorkflow
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind

/** 处理当前编辑器主体图谱相关命令。 */
internal class SubjectApplicationCommandHandler(
    private val subjectFlow: SubjectGraphWorkflow,
) {
    fun handle(command: ApplicationCommand.PreviewCurrentEditorSubjectKind): SubjectPreviewKind? =
        subjectFlow.previewCurrentEditorSubjectKind()

    fun handle(command: ApplicationCommand.AddCurrentEditorContextNode): Boolean =
        subjectFlow.addCurrentEditorContextNode()

    fun handle(command: ApplicationCommand.LoadCurrentEditorContextGraph) {
        subjectFlow.loadCurrentEditorContextGraphAsync()
    }

    fun handle(command: ApplicationCommand.RequestExpandOverflowNode) {
        subjectFlow.requestExpandOverflowNode(command.nodeId)
    }

    fun handle(command: ApplicationCommand.RequestAnalysisDisplayMode) {
        subjectFlow.requestAnalysisDisplayMode(command.displayMode)
    }
}
