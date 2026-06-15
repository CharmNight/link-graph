package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult

enum class AssistantIntent {
    DESCRIBE_CLASS,
    EXPLAIN_CODE,
    ASK_CODE,
    GENERATE_CODE,
    CHECK_CHANGE,
}

enum class AssistantActionId {
    DESCRIBE_CLASS,
    EXPLAIN_STRUCTURE,
    EXPLAIN_FLOW,
    ASK_CONTEXT,
    GENERATE_IMPLEMENTATION,
    CHECK_CHANGE,
    ;

    fun toIntent(): AssistantIntent =
        when (this) {
            DESCRIBE_CLASS -> AssistantIntent.DESCRIBE_CLASS
            EXPLAIN_STRUCTURE,
            EXPLAIN_FLOW -> AssistantIntent.EXPLAIN_CODE
            ASK_CONTEXT -> AssistantIntent.ASK_CODE
            GENERATE_IMPLEMENTATION -> AssistantIntent.GENERATE_CODE
            CHECK_CHANGE -> AssistantIntent.CHECK_CHANGE
        }

    companion object {
        fun fromIntent(intent: AssistantIntent): AssistantActionId =
            when (intent) {
                AssistantIntent.DESCRIBE_CLASS -> DESCRIBE_CLASS
                AssistantIntent.EXPLAIN_CODE -> EXPLAIN_FLOW
                AssistantIntent.ASK_CODE -> ASK_CONTEXT
                AssistantIntent.GENERATE_CODE -> GENERATE_IMPLEMENTATION
                AssistantIntent.CHECK_CHANGE -> CHECK_CHANGE
            }
    }
}

enum class AssistantTurnKind {
    EXPLANATION,
    QA,
    GENERATION_PLAN,
    CODE_DRAFT,
    CHECK_RESULT,
}

data class AssistantContextSnapshot(
    val selectedNodeIds: List<String> = emptyList(),
    val selectedDiffItemIds: List<String> = emptyList(),
    val analysisDisplayMode: String? = null,
    val currentSceneId: String? = null,
    val selectedMethodSignature: String? = null,
    val scopeLabel: String = "",
)

data class AssistantTurnRef(
    val turnId: String,
    val kind: AssistantTurnKind,
    val intent: AssistantIntent? = null,
    val actionId: AssistantActionId? = null,
    val sourceMessageType: String,
    val resultId: String,
    val createdAtEpochMillis: Long,
    val context: AssistantContextSnapshot,
)

data class AssistantFailureResult(
    val resultId: String,
    val message: String,
    val detailMessage: String? = null,
    val phase: String,
    val requestId: Long? = null,
    val sourceMessageType: String,
    val createdAtEpochMillis: Long? = null,
)

sealed interface AssistantComposerTarget {
    data object NewTask : AssistantComposerTarget

    data class QaRecovery(
        val requestId: String,
        val selectedNodeIds: List<String> = emptyList(),
        val sourceThreadId: String? = null,
        val mode: QaMode? = null,
    ) : AssistantComposerTarget

    data class ExplanationFollowUp(
        val stepId: String,
        val stepTitle: String? = null,
        val focusNodeId: String? = null,
    ) : AssistantComposerTarget

    data class GenerationDiscussion(
        val planItemId: String? = null,
    ) : AssistantComposerTarget

    data class RiskInvestigation(
        val threadId: String,
        val targetNodeIds: List<String> = emptyList(),
    ) : AssistantComposerTarget
}

data class AssistantComposerState(
    val draft: String = "",
    val target: AssistantComposerTarget = AssistantComposerTarget.NewTask,
    val draftSource: String? = null,
    val actionId: AssistantActionId? = null,
    val sceneId: String? = null,
)

data class AssistantSessionState(
    val sessionId: String,
    val activeIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
    val activeActionId: AssistantActionId? = null,
    val contextLocked: Boolean = false,
    val context: AssistantContextSnapshot = AssistantContextSnapshot(),
    val composer: AssistantComposerState = AssistantComposerState(),
    val nextResultSequence: Long = 1,
    val turns: List<AssistantTurnRef> = emptyList(),
)

data class AssistantResultStoreEntry(
    val kind: AssistantTurnKind,
    val failure: AssistantFailureResult? = null,
    val qa: GraphPatchResult? = null,
    val explanation: GraphBeautificationResult? = null,
    val generationPlan: GenerationPlan? = null,
    val generationDiscussionSession: GenerationPlanDiscussionSession? = null,
    val codeDrafts: List<GeneratedCodeDraft> = emptyList(),
    val codeDraftWarnings: List<String> = emptyList(),
    val check: GraphPatchResult? = null,
)

data class AssistantResultStore(
    val results: Map<String, AssistantResultStoreEntry> = emptyMap(),
) {
    companion object {
        const val HISTORY_RETENTION_LIMIT: Int = 50
    }

    fun put(
        resultId: String,
        entry: AssistantResultStoreEntry?,
    ): AssistantResultStore {
        if (resultId.isBlank() || entry == null) {
            return this
        }
        return copy(results = results + (resultId to entry))
    }

    fun retainOnly(resultIds: Collection<String>): AssistantResultStore {
        val retainedResultIds = resultIds.toSet()
        return copy(results = results.filterKeys { resultId -> resultId in retainedResultIds })
    }
}
