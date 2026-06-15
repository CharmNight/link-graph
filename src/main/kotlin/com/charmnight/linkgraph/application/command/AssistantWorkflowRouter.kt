package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.QaMode

internal class AssistantWorkflowRouter(
    private val executor: AssistantTaskExecutor,
) {
    fun route(command: ApplicationCommand.RequestAssistantTask) {
        val prompt = command.prompt.trim()
        val actionId = command.actionId
        val routedIntent = actionId.toIntent()
        when (actionId) {
            AssistantActionId.DESCRIBE_CLASS -> executor.executeExplanation(
                goal = prompt,
                focusNodeId = command.selectedNodeIds.firstOrNull(),
                followUp = null,
                granularity = command.explanationGranularity,
                intent = routedIntent,
                actionId = actionId,
            )
            AssistantActionId.EXPLAIN_STRUCTURE,
            AssistantActionId.EXPLAIN_FLOW -> executor.executeExplanation(
                goal = prompt,
                focusNodeId = command.explanationFocusNodeId(),
                followUp = command.explanationFollowUp(prompt),
                granularity = command.explanationGranularity,
                intent = routedIntent,
                actionId = actionId,
            )
            AssistantActionId.ASK_CONTEXT -> executor.executeQa(
                question = prompt,
                selectedNodeIds = command.qaSelectedNodeIds(),
                sourceThreadId = command.assistantTargetSourceThreadId(),
                mode = command.qaMode(),
            )
            AssistantActionId.GENERATE_IMPLEMENTATION -> {
                val discussionTarget = command.target as? AssistantComposerTarget.GenerationDiscussion
                if (discussionTarget != null) {
                    executor.executeGenerationDiscussion(prompt, discussionTarget.planItemId)
                } else {
                    executor.executeGenerationPlan(prompt)
                }
            }
            AssistantActionId.CHECK_CHANGE -> {
                executor.executeReviewGraph(command.selectedDiffItemIds)
                executor.executeDiffReview(
                    question = prompt.ifBlank { DEFAULT_CHECK_CHANGE_PROMPT },
                    selectedDiffItemIds = command.selectedDiffItemIds,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_CHECK_CHANGE_PROMPT: String = "请检查当前改动的风险、影响范围和相关测试。"
    }
}

private fun ApplicationCommand.RequestAssistantTask.explanationFollowUp(
    prompt: String,
): GraphBeautificationFollowUpContext? {
    val target = target as? AssistantComposerTarget.ExplanationFollowUp ?: return null
    val stepId = target.stepId.takeIf(String::isNotBlank) ?: return null
    val stepTitle = target.stepTitle?.takeIf(String::isNotBlank) ?: return null
    val question = prompt.takeIf(String::isNotBlank) ?: return null
    return GraphBeautificationFollowUpContext(stepId, stepTitle, question)
}

private fun ApplicationCommand.RequestAssistantTask.explanationFocusNodeId(): String? =
    (target as? AssistantComposerTarget.ExplanationFollowUp)?.focusNodeId
        ?: selectedNodeIds.firstOrNull()

private fun ApplicationCommand.RequestAssistantTask.qaSelectedNodeIds(): List<String> =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery ->
            currentTarget.selectedNodeIds.takeIf(List<String>::isNotEmpty) ?: selectedNodeIds
        is AssistantComposerTarget.RiskInvestigation ->
            currentTarget.targetNodeIds.takeIf(List<String>::isNotEmpty) ?: selectedNodeIds
        else -> selectedNodeIds
    }

private fun ApplicationCommand.RequestAssistantTask.assistantTargetSourceThreadId(): String? =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery -> currentTarget.sourceThreadId
        is AssistantComposerTarget.RiskInvestigation -> currentTarget.threadId
        else -> null
    }

private fun ApplicationCommand.RequestAssistantTask.qaMode(): QaMode =
    when (val currentTarget = target) {
        is AssistantComposerTarget.QaRecovery -> currentTarget.mode ?: QaMode.AUTO
        is AssistantComposerTarget.RiskInvestigation -> QaMode.INVESTIGATE
        else -> QaMode.AUTO
    }
