package com.charmnight.linkgraph.workbench

enum class StepGranularity {
    BUSINESS,
    METHOD_CALL,
    CODE_SEMANTIC,
}

enum class StepKind {
    BUSINESS_ACTION,
    METHOD_CALL,
    CONDITION,
    RETURN,
    RESOURCE_INTERACTION,
}

enum class CandidateDraftChangeStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    SUPERSEDED,
}

enum class DraftEntryKind {
    CHANGE,
    NOTE,
}

data class WorkbenchStep(
    val stepId: String,
    val title: String,
    val granularity: StepGranularity,
    val kind: StepKind,
    val description: String = "",
)

data class CandidateDraftChange(
    val changeId: String,
    val status: CandidateDraftChangeStatus,
)
