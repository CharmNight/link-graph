package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.reviewSelectedDiffItemIds
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantApplicationCommandHandlerTest {
    @Test
    fun requestAssistantTaskRoutesEachIntentToExistingWorkflowExecutor() {
        val executor = RecordingAssistantTaskExecutor()
        val handler = AssistantApplicationCommandHandler(executor)

        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.DESCRIBE_CLASS,
                actionId = AssistantActionId.DESCRIBE_CLASS,
                prompt = "介绍 ClientRequestQuotaManager",
                selectedNodeIds = listOf("class:quota-manager"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_FLOW,
                prompt = "解释当前方法",
                selectedNodeIds = listOf("method:submit-order"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "这个方法会影响哪里？",
                selectedNodeIds = listOf("method:submit-order"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.GENERATE_CODE,
                actionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                prompt = "补失败兜底",
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.CHECK_CHANGE,
                actionId = AssistantActionId.CHECK_CHANGE,
                prompt = "检查这次改动",
                selectedDiffItemIds = listOf("diff:OrderController.kt"),
            ),
        )

        assertEquals(
            listOf(
                "explain:介绍 ClientRequestQuotaManager:class:quota-manager::BUSINESS",
                "explain:解释当前方法:method:submit-order::BUSINESS",
            ),
            executor.events.filter { it.startsWith("explain:") },
        )
        assertEquals(listOf("qa:这个方法会影响哪里？:method:submit-order::AUTO"), executor.events.filter { it.startsWith("qa:") })
        assertEquals(listOf("plan:补失败兜底"), executor.events.filter { it.startsWith("plan:") })
        assertEquals(1, executor.reviewGraphRequests.size)
        assertEquals(IndexedGraphView.REVIEW, executor.reviewGraphRequests.single().view)
        assertEquals(listOf("diff:OrderController.kt"), executor.reviewGraphRequests.single().reviewSelectedDiffItemIds())
        assertEquals(listOf("diff-review:检查这次改动:diff:OrderController.kt"), executor.events.filter { it.startsWith("diff-review:") })
    }

    @Test
    fun requestAssistantTaskCanHandleCommandThroughDispatcher() {
        val executor = RecordingAssistantTaskExecutor()
        val dispatcher = ApplicationCommandDispatcher(listOf(AssistantApplicationCommandHandler(executor)))

        dispatcher.dispatch(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "继续问答",
            ),
        )

        assertTrue(executor.events.contains("qa:继续问答:::AUTO"))
    }

    @Test
    fun checkChangeSkipsReviewGraphWhenNoDiffItemsSelected() {
        val executor = RecordingAssistantTaskExecutor()
        val handler = AssistantApplicationCommandHandler(executor)

        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.CHECK_CHANGE,
                actionId = AssistantActionId.CHECK_CHANGE,
                prompt = "随便看看",
                // 关键：selectedDiffItemIds 为空，不应触发 executeReviewGraph
            ),
        )

        assertEquals(0, executor.reviewGraphRequests.size, "空 diff 列表时不应触发 executeReviewGraph")
        assertEquals(
            listOf("diff-review:随便看看:"),
            executor.events.filter { it.startsWith("diff-review:") },
            "executeDiffReview 仍应执行，使用默认或用户 prompt",
        )
    }

    @Test
    fun requestAssistantTaskRoutesUnifiedComposerContext() {
        val executor = RecordingAssistantTaskExecutor()
        val handler = AssistantApplicationCommandHandler(executor)

        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_FLOW,
                prompt = "继续解释异常分支",
                selectedNodeIds = listOf("method:fallback"),
                target = AssistantComposerTarget.ExplanationFollowUp(
                    stepId = "step:error",
                    stepTitle = "异常分支",
                    focusNodeId = "method:handle-error",
                ),
                explanationGranularity = StepGranularity.CODE_SEMANTIC,
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "继续取证这个风险",
                selectedNodeIds = listOf("method:submit-order"),
                target = AssistantComposerTarget.RiskInvestigation(
                    threadId = "risk-thread:1",
                    targetNodeIds = listOf("method:submit-order"),
                ),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "按风险复核模式检查证据",
                selectedNodeIds = listOf("method:submit-order"),
                mode = QaMode.REVIEW,
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.GENERATE_CODE,
                actionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                prompt = "把第二步拆小一些",
                target = AssistantComposerTarget.GenerationDiscussion(planItemId = "plan:item:2"),
            ),
        )

        assertTrue(executor.events.contains("explain:继续解释异常分支:method:handle-error:step:error:CODE_SEMANTIC"))
        assertTrue(executor.events.contains("qa:继续取证这个风险:method:submit-order:risk-thread:1:INVESTIGATE"))
        assertTrue(executor.events.contains("qa:按风险复核模式检查证据:method:submit-order::REVIEW"))
        assertTrue(executor.events.contains("discussion:把第二步拆小一些:plan:item:2"))
    }

    @Test
    fun requestAssistantTaskRoutesClassDescriptionAsFreshExplanation() {
        val executor = RecordingAssistantTaskExecutor()
        val handler = AssistantApplicationCommandHandler(executor)

        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.DESCRIBE_CLASS,
                actionId = AssistantActionId.DESCRIBE_CLASS,
                prompt = "请介绍类图节点“ClientRequestQuotaManager”",
                selectedNodeIds = listOf("class:quota-manager"),
                target = AssistantComposerTarget.ExplanationFollowUp(
                    stepId = "old-step",
                    stepTitle = "旧关系解释",
                    focusNodeId = "class:other",
                ),
                explanationGranularity = StepGranularity.BUSINESS,
            ),
        )

        assertEquals(
            listOf("explain:请介绍类图节点“ClientRequestQuotaManager”:class:quota-manager::BUSINESS"),
            executor.events.filter { it.startsWith("explain:") },
        )
        assertEquals(listOf(AssistantActionId.DESCRIBE_CLASS), executor.explanationActions)
    }

    @Test
    fun requestAssistantTaskRoutesExplicitActionIdWithoutReadingPromptText() {
        val executor = RecordingAssistantTaskExecutor()
        val handler = AssistantApplicationCommandHandler(executor)

        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.DESCRIBE_CLASS,
                prompt = "讲解当前视图",
                selectedNodeIds = listOf("class:quota-manager"),
                target = AssistantComposerTarget.ExplanationFollowUp(
                    stepId = "old-step",
                    stepTitle = "旧关系解释",
                    focusNodeId = "class:other",
                ),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_STRUCTURE,
                prompt = "讲解当前视图",
                selectedNodeIds = listOf("class:quota-manager"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_FLOW,
                prompt = "讲解当前视图",
                selectedNodeIds = listOf("method:submit-order"),
            ),
        )

        assertEquals(
            listOf(
                "explain:讲解当前视图:class:quota-manager::BUSINESS",
                "explain:讲解当前视图:class:quota-manager::BUSINESS",
                "explain:讲解当前视图:method:submit-order::BUSINESS",
            ),
            executor.events.filter { it.startsWith("explain:") },
        )
        assertEquals(
            listOf(
                AssistantActionId.DESCRIBE_CLASS,
                AssistantActionId.EXPLAIN_STRUCTURE,
                AssistantActionId.EXPLAIN_FLOW,
            ),
            executor.explanationActions,
        )
    }
}

private class RecordingAssistantTaskExecutor : AssistantTaskExecutor {
    val events = mutableListOf<String>()
    val explanationActions = mutableListOf<AssistantActionId>()
    val reviewGraphRequests = mutableListOf<com.charmnight.linkgraph.application.indexed.IndexedGraphRequest>()

    override fun executeExplanation(
        goal: String,
        focusNodeId: String?,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        intent: AssistantIntent,
        actionId: AssistantActionId,
    ) {
        explanationActions += actionId
        events += "explain:$goal:${focusNodeId.orEmpty()}:${followUp?.stepId.orEmpty()}:${granularity.name}"
    }

    override fun executeQa(
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode,
    ) {
        events += "qa:$question:${selectedNodeIds.joinToString(",")}:${sourceThreadId.orEmpty()}:${mode.name}"
    }

    override fun executeGenerationPlan(userGoal: String) {
        events += "plan:$userGoal"
    }

    override fun executeGenerationDiscussion(
        question: String,
        focusItemId: String?,
    ) {
        events += "discussion:$question:${focusItemId.orEmpty()}"
    }

    override fun executeReviewGraph(selectedDiffItemIds: List<String>) {
        reviewGraphRequests += com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest(selectedDiffItemIds)
    }

    override fun executeDiffReview(
        question: String,
        selectedDiffItemIds: List<String>,
    ) {
        events += "diff-review:$question:${selectedDiffItemIds.joinToString(",")}"
    }
}
