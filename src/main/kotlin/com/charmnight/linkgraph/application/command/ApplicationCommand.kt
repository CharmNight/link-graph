package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.application.model.GraphEditScript
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.editor.Editor

internal sealed interface ApplicationCommand<out R> {
    data class PreviewCurrentEditorSubjectKind(
        val editor: Editor? = null,
    ) : ApplicationCommand<SubjectPreviewKind?>

    data object AddCurrentEditorContextNode : ApplicationCommand<Boolean>

    data class LoadCurrentEditorContextGraph(
        val editor: Editor? = null,
    ) : ApplicationCommand<Unit>

    data class RequestExpandOverflowNode(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    data class RequestAnalysisDisplayMode(
        val displayMode: AnalysisDisplayMode,
    ) : ApplicationCommand<Unit>

    data class RequestIndexedGraph(
        val request: IndexedGraphRequest,
    ) : ApplicationCommand<Unit>

    data class LoadGraph(
        val graph: GraphDocument,
        val source: String,
    ) : ApplicationCommand<Unit>

    data class ImportMermaid(
        val mermaid: String,
    ) : ApplicationCommand<GraphDocument>

    data object ExportMermaid : ApplicationCommand<String>

    data object ShowDiffMode : ApplicationCommand<GraphDifferResult?>

    data class ApplyGraphEditScript(
        val script: GraphEditScript,
    ) : ApplicationCommand<Unit>

    data class LayoutChanged(
        val positions: Map<String, GraphLayoutPosition>,
    ) : ApplicationCommand<Unit>

    data object RequestSyncPreview : ApplicationCommand<List<SyncPreviewItem>>

    data class RequestSourceNavigation(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    data class RequestExpandInvocation(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    data class RequestRemoveInvocationExpansion(
        val expansionId: String,
    ) : ApplicationCommand<Unit>

    data object OpenSettings : ApplicationCommand<Unit>

    data class RequestAssistantTask(
        val actionId: AssistantActionId,
        val intent: AssistantIntent = actionId.toIntent(),
        val sceneId: String? = null,
        val prompt: String,
        val selectedNodeIds: List<String> = emptyList(),
        val selectedDiffItemIds: List<String> = emptyList(),
        val target: AssistantComposerTarget = AssistantComposerTarget.NewTask,
        val explanationGranularity: StepGranularity = StepGranularity.BUSINESS,
    ) : ApplicationCommand<Unit>

    data object RetryLastQaRequest : ApplicationCommand<Unit>

    data class ResolveInvestigationThread(
        val threadId: String,
        val status: RiskResolutionStatus,
        val note: String = "",
    ) : ApplicationCommand<Unit>

    data class ConfirmQaCandidateChange(
        val changeId: String,
    ) : ApplicationCommand<DraftWorkbenchEntry?>

    data class UnconfirmQaCandidateChange(
        val changeId: String,
    ) : ApplicationCommand<DraftWorkbenchEntry?>

    data class ApplyDraftPatchPreview(
        val operationIds: Set<String>? = null,
    ) : ApplicationCommand<GraphDocument?>

    data object ClearDraftPatchPreview : ApplicationCommand<Unit>

    data class RestoreDraftPatchPreview(
        val source: DraftPatchPreviewSource,
    ) : ApplicationCommand<GraphPatch?>

    data object UndoLastDraftPatchApply : ApplicationCommand<GraphDocument?>

    data object RequestCodeDrafts : ApplicationCommand<Unit>

    data object ApplyCodeDrafts : ApplicationCommand<Unit>

    data class ApplySingleCodeDraft(
        val draftId: String,
    ) : ApplicationCommand<Unit>

    data class OpenCodeDraftNativeDiff(
        val draftId: String,
    ) : ApplicationCommand<Unit>

    data class RequestDraftNavigation(
        val targetPath: String,
    ) : ApplicationCommand<Unit>

    data class PrepareDebugRequestedAnalysisDisplayMode(
        val envName: String,
    ) : ApplicationCommand<Unit>

    data class LoadDebugMethodGraphBySignature(
        val signature: String,
    ) : ApplicationCommand<Unit>

    data class LoadDebugGraph(
        val mode: String,
    ) : ApplicationCommand<Unit>
}
