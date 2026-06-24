package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult

/** 助手意图，标识用户在助手会话中的目标。 */
enum class AssistantIntent {
    DESCRIBE_CLASS,
    EXPLAIN_CODE,
    ASK_CODE,
    GENERATE_CODE,
    CHECK_CHANGE,
}

/** 助手动作 ID，对应用户可触发的具体动作。 */
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

/** 助手轮次种类，描述本轮回复的性质。 */
enum class AssistantTurnKind {
    EXPLANATION,
    QA,
    GENERATION_PLAN,
    CODE_DRAFT,
    CHECK_RESULT,
}

/** 助手上下文快照，记录会话发生时的用户界面选择与场景信息。 */
data class AssistantContextSnapshot(
    val selectedNodeIds: List<String> = emptyList(),
    val selectedDiffItemIds: List<String> = emptyList(),
    val analysisDisplayMode: String? = null,
    val currentSceneId: String? = null,
    val selectedMethodSignature: String? = null,
    val scopeLabel: String = "",
)

/** 助手轮次引用，记录轮次标识、种类、意图、来源消息与结果 ID 等。 */
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

/** 助手失败结果，记录失败阶段、消息与上下文信息。 */
data class AssistantFailureResult(
    val resultId: String,
    val message: String,
    val detailMessage: String? = null,
    val phase: String,
    val requestId: Long? = null,
    val sourceMessageType: String,
    val createdAtEpochMillis: Long? = null,
)

/** 输入框的目标对象，标识当前输入是新建任务还是某种后续动作。 */
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

/** 输入框状态，包括草稿文本、目标、动作 ID 等。 */
data class AssistantComposerState(
    val draft: String = "",
    val target: AssistantComposerTarget = AssistantComposerTarget.NewTask,
    val draftSource: String? = null,
    val actionId: AssistantActionId? = null,
    val sceneId: String? = null,
    val qaMode: QaMode = QaMode.AUTO,
)

/** 助手会话状态，包含意图、上下文、输入框与历史轮次等。 */
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

/** 助手结果存储条目，承载 QA、解释、生成方案、草稿、检查等多种结果。 */
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

/** 助手结果存储，按结果 ID 维护历史结果条目。 */
data class AssistantResultStore(
    val results: Map<String, AssistantResultStoreEntry> = emptyMap(),
) {
    companion object {
        /** 历史保留条数上限。 */
        const val HISTORY_RETENTION_LIMIT: Int = 50
    }

    /** 写入或更新结果条目。 */
    fun put(
        resultId: String,
        entry: AssistantResultStoreEntry?,
    ): AssistantResultStore {
        if (resultId.isBlank() || entry == null) {
            return this
        }
        return copy(results = results + (resultId to entry))
    }

    /** 仅保留指定的结果 ID，其余丢弃。 */
    fun retainOnly(resultIds: Collection<String>): AssistantResultStore {
        val retainedResultIds = resultIds.toSet()
        return copy(results = results.filterKeys { resultId -> resultId in retainedResultIds })
    }
}
