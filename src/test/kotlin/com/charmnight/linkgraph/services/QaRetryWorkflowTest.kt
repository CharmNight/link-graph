package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.capability.QaCapability
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaMode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QaRetryWorkflowTest : BasePlatformTestCase() {
    fun testRetryLastQaRequestAsyncReplaysFailedRequestWithoutDuplicatingUserTurn() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        var attemptCount = 0
        val workflow = ReviewWorkflow(
            project = project,
            snapshotProvider = stateService.editorSnapshotProvider(),
            toolGraphSnapshotProvider = stateService.toolGraphSnapshotProvider(),
            eventSink = stateService.applicationEventSink(),
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphQaPatchService = GraphQaPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.LOCAL_RULE,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            qaExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(QaRetryWorkflowTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    qaExecutor = { input, _, _ ->
                        attemptCount += 1
                        if (attemptCount == 1) {
                            error("mock qa failure")
                        }
                        GraphPatchResult(
                            source = LlmResultSource.LOCAL_RULE,
                            question = input.question,
                            answer = "重试后成功返回问答结果。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestQaAsync("这里为什么会走兜底分支？")
        waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        workflow.retryLastQaRequestAsync()

        val snapshot = waitForSnapshot(stateService) { current ->
            current.qaRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(2, attemptCount)
        assertEquals("重试后成功返回问答结果。", snapshot.qaResult?.answer)
        assertEquals(QaMode.AUTO, snapshot.qaResult?.requestedMode)
        assertEquals(QaMode.ANSWER, snapshot.qaResult?.effectiveMode)
        assertEquals(QaMode.AUTO, snapshot.qaRequestState.requestedMode)
        assertEquals(QaMode.ANSWER, snapshot.qaRequestState.effectiveMode)
        assertNotNull(snapshot.qaResult?.qaSession)
        assertEquals(1, snapshot.qaResult?.qaSession?.messages?.count { it.role == QaMessageRole.USER })
        assertEquals("这里为什么会走兜底分支？", snapshot.qaResult?.qaSession?.messages?.first { it.role == QaMessageRole.USER }?.content)
        assertTrue(snapshot.qaRequestRecoveryState.lastFailedRequest == null)
    }

    private fun sampleGraph(): GraphDocument {
        return GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit-order",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    signature = "com.example.OrderController.submit():void",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
    }

    private fun waitForSnapshot(
        stateService: GraphEditorStateService,
        predicate: (com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) -> Boolean,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        var latest = stateService.snapshot()
        PlatformTestUtil.waitWithEventsDispatching("等待问答状态收敛", {
            latest = stateService.snapshot()
            predicate(latest)
        }, 5000)
        return latest
    }
}
