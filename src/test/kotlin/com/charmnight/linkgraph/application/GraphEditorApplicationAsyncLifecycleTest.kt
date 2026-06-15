package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorCommandRouter
import com.charmnight.linkgraph.ui.GraphEditorMessage
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GraphEditorApplicationAsyncLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerGraphEditorApplicationServicesForTest()
    }

    fun testGenerationPlanAsyncAllowsRequestsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val commandRouter = project.getService(GraphEditorCommandRouter::class.java)
        testOverrides.effectiveGenerationSettings = LinkGraphSettingsState(
            llmEnabled = true,
            provider = "MOCK",
            timeoutSeconds = 45,
        )
        commandRouter.dispatch(
            GraphEditorMessage.RequestAssistantTask(
                intent = AssistantIntent.GENERATE_CODE,
                actionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                prompt = "",
            ),
        )

        val snapshot = waitForSnapshot { current ->
            current.generationPlanRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.generationPlanRequestState.scene == "实现计划生成"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.generationPlanRequestState.phase)
        assertEquals("实现计划生成", snapshot.generationPlanRequestState.scene)
        assertEquals(
            com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.LOCAL_RULE,
            snapshot.generationPlanRequestState.executionMode,
        )
        assertNotNull(snapshot.generationPlan)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    fun testCodeDraftAsyncRejectsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        project.getService(LinkGraphProjectTestOverrides::class.java)
        project.getService(GraphEditorCommandRouter::class.java).dispatch(GraphEditorMessage.RequestCodeDrafts)

        val snapshot = waitForSnapshot { current ->
            current.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED, snapshot.codeDraftRequestState.phase)
        assertEquals("代码草稿", snapshot.codeDraftRequestState.scene)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    fun testQaAsyncSuccessDoesNotGetOverwrittenByItsOwnTimeout() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val commandRouter = project.getService(GraphEditorCommandRouter::class.java)
        testOverrides.effectiveGenerationSettings = remoteSettings()
        testOverrides.asyncRequestTimeoutMillis = 120
        testOverrides.qaExecutor = { _: GraphQaContext, question: String ->
            Thread.sleep(20)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = question,
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.dispatch(
            GraphEditorMessage.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "请围绕当前链路进行问答",
            ),
        )

        val succeeded = waitForSnapshot { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.qaRequestState.scene == "问答"
        }

        Thread.sleep(220)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val afterTimeoutWindow = stateService.snapshot()

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, succeeded.qaRequestState.phase)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, afterTimeoutWindow.qaRequestState.phase)
        assertTrue(afterTimeoutWindow.qaRequestState.errorMessage.isNullOrBlank())
        assertTrue(afterTimeoutWindow.operationFeedback?.message?.contains("问答完成") == true)
    }

    fun testQaAsyncTimesOutAndStopsBlindWaiting() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val commandRouter = project.getService(GraphEditorCommandRouter::class.java)
        testOverrides.effectiveGenerationSettings = remoteSettings()
        testOverrides.asyncRequestTimeoutMillis = 120
        testOverrides.qaExecutor = { _: GraphQaContext, _: String ->
            Thread.sleep(600)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = "请围绕当前链路进行问答",
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.dispatch(
            GraphEditorMessage.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "请围绕当前链路进行问答",
            ),
        )

        val snapshot = waitForSnapshot { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.TIMED_OUT &&
                current.qaRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.TIMED_OUT, snapshot.qaRequestState.phase)
        assertTrue(snapshot.qaRequestState.errorMessage?.contains("超时") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("流式输出") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
    }

    fun testQaAsyncTracksLocalRuleExecutionWhenRemoteLlmIsDisabled() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val commandRouter = project.getService(GraphEditorCommandRouter::class.java)
        testOverrides.effectiveGenerationSettings = LinkGraphSettingsState(
            llmEnabled = false,
            provider = "MOCK",
            timeoutSeconds = 45,
        )
        testOverrides.qaExecutor = { _: GraphQaContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = question,
                answer = "本地规则结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.dispatch(
            GraphEditorMessage.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "请围绕当前链路进行问答",
            ),
        )

        val snapshot = waitForSnapshot { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.qaRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.qaRequestState.phase)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.LOCAL_RULE, snapshot.qaRequestState.executionMode)
        assertNotNull(snapshot.qaRequestState.requestId)
        assertEquals("问答", snapshot.qaRequestState.scene)
        assertTrue(snapshot.qaRequestState.statusMessage?.contains("本地规则") == true)
        assertTrue(snapshot.qaRequestState.detailMessage?.contains("未启用") == true)
        assertEquals(false, snapshot.qaRequestState.fallbackUsed)
    }

    fun testQaAsyncMarksRemoteFallbackInsteadOfPretendingStillRunning() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val commandRouter = project.getService(GraphEditorCommandRouter::class.java)
        testOverrides.effectiveGenerationSettings = remoteSettings()
        testOverrides.qaExecutor = { _: GraphQaContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = question,
                answer = "已回退到本地规则分析。",
                promptPreview = "prompt",
                warnings = listOf("远程 LLM 问答失败，已回退为本地规则分析：HTTP 503。"),
            )
        }

        commandRouter.dispatch(
            GraphEditorMessage.RequestAssistantTask(
                intent = AssistantIntent.ASK_CODE,
                actionId = AssistantActionId.ASK_CONTEXT,
                prompt = "请围绕当前链路进行问答",
            ),
        )

        val snapshot = waitForSnapshot { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.qaRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.qaRequestState.phase)
        assertTrue(snapshot.qaRequestState.fallbackUsed)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.REMOTE_FALLBACK, snapshot.qaRequestState.executionMode)
        assertTrue(snapshot.qaRequestState.statusMessage?.contains("已回退") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    private fun sampleGraph(): GraphDocument {
        return GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place():void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
    }

    private fun remoteSettings(): LinkGraphSettingsState {
        return LinkGraphSettingsState(
            llmEnabled = true,
            provider = "OPENAI_COMPATIBLE",
            endpoint = "https://example.com/v1",
            apiKey = "token",
            model = "gpt-test",
            timeoutSeconds = 45,
        )
    }

    private fun waitForSnapshot(
        predicate: (com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) -> Boolean,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (predicate(snapshot)) {
                return snapshot
            }
            Thread.sleep(50)
        }
        fail("等待异步请求状态收敛超时")
        throw IllegalStateException("unreachable")
    }
}
