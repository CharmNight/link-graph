package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.reviewSelectedDiffItemIds
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
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
                intent = AssistantIntent.EXPLAIN_CODE,
                prompt = "解释当前方法",
                selectedNodeIds = listOf("method:submit-order"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                prompt = "这个方法会影响哪里？",
                selectedNodeIds = listOf("method:submit-order"),
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.GENERATE_CODE,
                prompt = "补失败兜底",
            ),
        )
        handler.handle(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.CHECK_CHANGE,
                prompt = "检查这次改动",
                selectedDiffItemIds = listOf("diff:OrderController.kt"),
            ),
        )

        assertEquals(listOf("explain:解释当前方法:method:submit-order"), executor.events.filter { it.startsWith("explain:") })
        assertEquals(listOf("qa:这个方法会影响哪里？:method:submit-order:AUTO"), executor.events.filter { it.startsWith("qa:") })
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
                prompt = "继续问答",
            ),
        )

        assertTrue(executor.events.contains("qa:继续问答::AUTO"))
    }
}

private class RecordingAssistantTaskExecutor : AssistantTaskExecutor {
    val events = mutableListOf<String>()
    val reviewGraphRequests = mutableListOf<com.charmnight.linkgraph.application.indexed.IndexedGraphRequest>()

    override fun requestGraphBeautification(
        goal: String,
        focusNodeId: String?,
    ) {
        events += "explain:$goal:${focusNodeId.orEmpty()}"
    }

    override fun requestQa(
        question: String,
        selectedNodeIds: List<String>,
        mode: QaMode,
    ) {
        events += "qa:$question:${selectedNodeIds.joinToString(",")}:${mode.name}"
    }

    override fun requestGenerationPlan(userGoal: String) {
        events += "plan:$userGoal"
    }

    override fun requestReviewGraph(selectedDiffItemIds: List<String>) {
        reviewGraphRequests += com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest(selectedDiffItemIds)
    }

    override fun requestDiffReview(
        question: String,
        selectedDiffItemIds: List<String>,
    ) {
        events += "diff-review:$question:${selectedDiffItemIds.joinToString(",")}"
    }
}
