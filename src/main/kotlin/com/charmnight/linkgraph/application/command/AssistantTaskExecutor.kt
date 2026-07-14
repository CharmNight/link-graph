package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.agent.model.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity

internal interface AssistantTaskExecutor {
    fun executeExplanation(
        goal: String,
        focusNodeId: String?,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        intent: AssistantIntent,
        actionId: AssistantActionId,
    )

    fun executeQa(
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode,
    )

    fun executeGenerationPlan(userGoal: String)

    fun executeGenerationDiscussion(question: String, focusItemId: String?)

    fun executeReviewGraph(selectedDiffItemIds: List<String>)

    fun executeDiffReview(question: String, selectedDiffItemIds: List<String>)
}

internal class WorkflowAssistantTaskExecutor(
    private val reviewFlow: ReviewWorkflow,
    private val reviewGraphFlow: ReviewGraphWorkflow,
    private val generationPlanFlow: GenerationPlanWorkflow,
    private val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
) : AssistantTaskExecutor {
    override fun executeExplanation(
        goal: String,
        focusNodeId: String?,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        intent: AssistantIntent,
        actionId: AssistantActionId,
    ) {
        reviewFlow.requestGraphBeautificationAsync(
            goal = goal,
            focusNodeId = focusNodeId,
            followUp = followUp,
            granularity = granularity,
            assistantIntent = intent,
            assistantActionId = actionId,
        )
    }

    override fun executeQa(
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode,
    ) {
        reviewFlow.requestQaAsync(
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
    }

    override fun executeGenerationPlan(userGoal: String) {
        generationPlanFlow.requestGenerationPlanAsync(userGoal)
    }

    override fun executeGenerationDiscussion(question: String, focusItemId: String?) {
        generationDiscussionFlow.requestGenerationPlanDiscussionAsync(question, focusItemId)
    }

    override fun executeReviewGraph(selectedDiffItemIds: List<String>) {
        reviewGraphFlow.requestIndexedGraph(requestReviewGraphRequest(selectedDiffItemIds))
    }

    override fun executeDiffReview(question: String, selectedDiffItemIds: List<String>) {
        reviewFlow.requestDiffReviewAsync(question, selectedDiffItemIds)
    }
}
