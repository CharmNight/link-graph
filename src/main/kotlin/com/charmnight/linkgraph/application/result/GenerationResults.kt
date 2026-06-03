package com.charmnight.linkgraph.application.result

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunArtifactSummary
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult

enum class ApplicationFeedbackLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class ApplicationRuntimeArtifactSummary(
    val artifactId: String,
    val artifactType: String,
    val title: String,
    val description: String? = null,
) {
    companion object {
        fun from(summary: AgentRunArtifactSummary): ApplicationRuntimeArtifactSummary {
            return ApplicationRuntimeArtifactSummary(
                artifactId = summary.artifactId,
                artifactType = summary.artifactType,
                title = summary.title,
                description = summary.description,
            )
        }
    }
}

data class GenerationPlanResult(
    val plan: GenerationPlan,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel,
    val statusMessage: String,
)

data class CodeDraftWriteResult(
    val report: GeneratedCodeDraftWriteReport,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val statusMessage: String? = null,
)

data class GeneratedCodeDraftsResult(
    val drafts: List<GeneratedCodeDraft>,
    val warnings: List<String>,
    val source: LlmResultSource,
    val promptPreview: String?,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val statusMessage: String? = null,
)

data class GenerationRequestFailureResult(
    val scene: String,
    val message: String,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary> = emptyList(),
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preservePreviousStatusKind: Boolean = true,
)

data class GenerationRequestStartedResult(
    val scene: GenerationRequestScene,
    val requestState: AsyncRequestState,
    val statusMessage: String,
    val clearRuntimeArtifactScene: String? = null,
)

data class GenerationDiscussionResult(
    val result: GenerationPlanDiscussionResult,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel,
    val statusMessage: String,
)

enum class GenerationRequestScene {
    PLAN,
    PLAN_DISCUSSION,
    CODE_DRAFT,
}
