package com.charmnight.linkgraph.workbench

enum class AssistantIntent {
    EXPLAIN_CODE,
    ASK_CODE,
    GENERATE_CODE,
    CHECK_CHANGE,
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
    val sourceMessageType: String,
    val resultId: String? = null,
    val createdAtEpochMillis: Long,
    val context: AssistantContextSnapshot,
)

data class AssistantSessionState(
    val sessionId: String,
    val activeIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
    val contextLocked: Boolean = false,
    val context: AssistantContextSnapshot = AssistantContextSnapshot(),
    val turns: List<AssistantTurnRef> = emptyList(),
)
