package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LinkGraphProjectServiceAsyncLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
        project.registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
        project.registerServiceInstance(LinkGraphProjectService::class.java, LinkGraphProjectService(project))
        project.registerServiceInstance(GraphEditorCommandRouter::class.java, GraphEditorCommandRouter(project))
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
        commandRouter.requestGenerationPlanAsync()

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
        project.getService(GraphEditorCommandRouter::class.java).requestCodeDraftsAsync()

        val snapshot = waitForSnapshot { current ->
            current.codeDraftRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED, snapshot.codeDraftRequestState.phase)
        assertEquals("代码草稿", snapshot.codeDraftRequestState.scene)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    fun testAuditAsyncSuccessDoesNotGetOverwrittenByItsOwnTimeout() {
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
        testOverrides.auditExecutor = { _: GraphAuditContext, question: String ->
            Thread.sleep(20)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = question,
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.requestAuditAsync("请围绕当前链路进行问答")

        val succeeded = waitForSnapshot { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.auditRequestState.scene == "问答"
        }

        Thread.sleep(220)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val afterTimeoutWindow = stateService.snapshot()

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, succeeded.auditRequestState.phase)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, afterTimeoutWindow.auditRequestState.phase)
        assertTrue(afterTimeoutWindow.auditRequestState.errorMessage.isNullOrBlank())
        assertTrue(afterTimeoutWindow.operationFeedback?.message?.contains("问答完成") == true)
    }

    fun testAuditAsyncTimesOutAndStopsBlindWaiting() {
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
        testOverrides.auditExecutor = { _: GraphAuditContext, _: String ->
            Thread.sleep(600)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = "请围绕当前链路进行问答",
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.requestAuditAsync("请围绕当前链路进行问答")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.TIMED_OUT &&
                current.auditRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.TIMED_OUT, snapshot.auditRequestState.phase)
        assertTrue(snapshot.auditRequestState.errorMessage?.contains("超时") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("流式输出") == true)
        assertEquals(com.charmnight.linkgraph.ui.OperationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
    }

    fun testAuditAsyncTracksLocalRuleExecutionWhenRemoteLlmIsDisabled() {
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
        testOverrides.auditExecutor = { _: GraphAuditContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = "本地规则结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        commandRouter.requestAuditAsync("请围绕当前链路进行问答")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.auditRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.auditRequestState.phase)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.LOCAL_RULE, snapshot.auditRequestState.executionMode)
        assertNotNull(snapshot.auditRequestState.requestId)
        assertEquals("问答", snapshot.auditRequestState.scene)
        assertTrue(snapshot.auditRequestState.statusMessage?.contains("本地规则") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("未启用") == true)
        assertEquals(false, snapshot.auditRequestState.fallbackUsed)
    }

    fun testAuditAsyncMarksRemoteFallbackInsteadOfPretendingStillRunning() {
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
        testOverrides.auditExecutor = { _: GraphAuditContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = "已回退到本地规则分析。",
                promptPreview = "prompt",
                warnings = listOf("远程 LLM 问答失败，已回退为本地规则分析：HTTP 503。"),
            )
        }

        commandRouter.requestAuditAsync("请围绕当前链路进行问答")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED &&
                current.auditRequestState.scene == "问答"
        }

        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED, snapshot.auditRequestState.phase)
        assertTrue(snapshot.auditRequestState.fallbackUsed)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestExecutionMode.REMOTE_FALLBACK, snapshot.auditRequestState.executionMode)
        assertTrue(snapshot.auditRequestState.statusMessage?.contains("已回退") == true)
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
