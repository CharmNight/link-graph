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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LinkGraphProjectServiceAsyncLifecycleTest : BasePlatformTestCase() {
    fun testGenerationPlanAsyncRejectsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestGenerationPlanAsync()

        val snapshot = waitForSnapshot { current ->
            current.generationPlanRequestState.phase == GraphEditorStateService.AsyncRequestPhase.FAILED
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.generationPlanRequestState.phase)
        assertEquals("实现计划", snapshot.generationPlanRequestState.scene)
        assertTrue(snapshot.generationPlanRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    fun testCodeDraftAsyncRejectsWhenNoConfirmedDraftChangesExist() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.requestCodeDraftsAsync()

        val snapshot = waitForSnapshot { current ->
            current.codeDraftRequestState.phase == GraphEditorStateService.AsyncRequestPhase.FAILED
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.FAILED, snapshot.codeDraftRequestState.phase)
        assertEquals("代码草稿", snapshot.codeDraftRequestState.scene)
        assertTrue(snapshot.codeDraftRequestState.errorMessage?.contains("请先确认至少一条草稿变更") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
    }

    fun testAuditAsyncSuccessDoesNotGetOverwrittenByItsOwnTimeout() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testEffectiveGenerationSettingsOverride = remoteSettings()
        service.testAsyncRequestTimeoutMillisOverride = 120
        service.testAuditExecutorOverride = { _: GraphAuditContext, question: String ->
            Thread.sleep(20)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = question,
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        service.requestAuditAsync("请审计当前链路")

        val succeeded = waitForSnapshot { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        Thread.sleep(220)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val afterTimeoutWindow = stateService.snapshot()

        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, succeeded.auditRequestState.phase)
        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, afterTimeoutWindow.auditRequestState.phase)
        assertTrue(afterTimeoutWindow.auditRequestState.errorMessage.isNullOrBlank())
        assertTrue(afterTimeoutWindow.operationFeedback?.message?.contains("审计完成") == true)
    }

    fun testAuditAsyncTimesOutAndStopsBlindWaiting() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testEffectiveGenerationSettingsOverride = remoteSettings()
        service.testAsyncRequestTimeoutMillisOverride = 120
        service.testAuditExecutorOverride = { _: GraphAuditContext, _: String ->
            Thread.sleep(600)
            GraphPatchResult(
                source = LlmResultSource.REMOTE,
                question = "请审计当前链路",
                answer = "远程结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        service.requestAuditAsync("请审计当前链路")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.TIMED_OUT
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.TIMED_OUT, snapshot.auditRequestState.phase)
        assertTrue(snapshot.auditRequestState.errorMessage?.contains("超时") == true)
        assertTrue(snapshot.auditRequestState.detailMessage?.contains("流式输出") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.ERROR, snapshot.operationFeedback?.level)
    }

    fun testAuditAsyncTracksLocalRuleExecutionWhenRemoteLlmIsDisabled() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraphProjection(
            visibleGraph = sampleGraph(),
            fullGraph = sampleGraph(),
            source = "currentMethod",
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testEffectiveGenerationSettingsOverride = LinkGraphSettingsState(
            llmEnabled = false,
            provider = "MOCK",
            timeoutSeconds = 45,
        )
        service.testAuditExecutorOverride = { _: GraphAuditContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = "本地规则结果",
                promptPreview = "prompt",
                warnings = emptyList(),
            )
        }

        service.requestAuditAsync("请审计当前链路")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, snapshot.auditRequestState.phase)
        assertEquals(GraphEditorStateService.AsyncRequestExecutionMode.LOCAL_RULE, snapshot.auditRequestState.executionMode)
        assertNotNull(snapshot.auditRequestState.requestId)
        assertEquals("审计", snapshot.auditRequestState.scene)
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

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testEffectiveGenerationSettingsOverride = remoteSettings()
        service.testAuditExecutorOverride = { _: GraphAuditContext, question: String ->
            GraphPatchResult(
                source = LlmResultSource.MOCK,
                question = question,
                answer = "已回退到本地规则分析。",
                promptPreview = "prompt",
                warnings = listOf("远程 LLM 审计失败，已回退为本地规则分析：HTTP 503。"),
            )
        }

        service.requestAuditAsync("请审计当前链路")

        val snapshot = waitForSnapshot { current ->
            current.auditRequestState.phase == GraphEditorStateService.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(GraphEditorStateService.AsyncRequestPhase.SUCCEEDED, snapshot.auditRequestState.phase)
        assertTrue(snapshot.auditRequestState.fallbackUsed)
        assertEquals(GraphEditorStateService.AsyncRequestExecutionMode.REMOTE_FALLBACK, snapshot.auditRequestState.executionMode)
        assertTrue(snapshot.auditRequestState.statusMessage?.contains("已回退") == true)
        assertEquals(GraphEditorStateService.OperationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
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
        predicate: (GraphEditorStateService.Snapshot) -> Boolean,
    ): GraphEditorStateService.Snapshot {
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
