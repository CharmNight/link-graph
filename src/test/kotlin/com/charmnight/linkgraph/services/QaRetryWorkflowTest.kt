package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphAuditPatchService
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
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QaRetryWorkflowTest : BasePlatformTestCase() {
    fun testRetryLastAuditRequestAsyncReplaysFailedRequestWithoutDuplicatingUserTurn() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        stateService.loadGraph(sampleGraph(), "currentMethod")
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = {},
        )
        var attemptCount = 0
        val workflow = ReviewWorkflow(
            project = project,
            session = session,
            planningContextFactory = PlanningContextFactory(
                graphDiffer = GraphDiffer(),
                syncPreviewPlanner = com.charmnight.linkgraph.sync.SyncPreviewPlanner(),
                graphGenerationService = com.charmnight.linkgraph.llm.GraphGenerationService(),
                settingsProvider = { LinkGraphSettingsState() },
            ),
            graphAuditPatchService = GraphAuditPatchService(),
            graphDiffPatchService = GraphDiffPatchService(),
            graphBeautificationService = object : GraphBeautificationService {
                override fun beautify(
                    context: com.charmnight.linkgraph.llm.GraphBeautificationContext,
                    settings: LinkGraphSettingsState,
                    onPreview: ((String, Boolean) -> Unit)?,
                ) = com.charmnight.linkgraph.llm.GraphBeautificationResult(
                    source = LlmResultSource.MOCK,
                    promptPreview = "unused",
                )
            },
            graphDiffer = GraphDiffer(),
            settingsProvider = { LinkGraphSettingsState() },
            auditExecutorOverrideProvider = { null },
            asyncRequestLifecycle = AsyncRequestLifecycleSupport(
                project = project,
                session = session,
                timeoutOverrideProvider = { 500L },
            ),
            logger = Logger.getInstance(QaRetryWorkflowTest::class.java),
            qaCapabilityFactory = {
                QaCapability(
                    auditExecutor = { input, _, _ ->
                        attemptCount += 1
                        if (attemptCount == 1) {
                            error("mock qa failure")
                        }
                        GraphPatchResult(
                            source = LlmResultSource.MOCK,
                            question = input.question,
                            answer = "重试后成功返回问答结果。",
                            promptPreview = "prompt",
                        )
                    },
                )
            },
        )

        workflow.requestAuditAsync("这里为什么会走兜底分支？")
        waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.FAILED
        }

        workflow.retryLastAuditRequestAsync()

        val snapshot = waitForSnapshot(stateService) { current ->
            current.auditRequestState.phase == com.charmnight.linkgraph.ui.AsyncRequestPhase.SUCCEEDED
        }

        assertEquals(2, attemptCount)
        assertEquals("重试后成功返回问答结果。", snapshot.auditResult?.answer)
        assertNotNull(snapshot.auditResult?.auditSession)
        assertEquals(1, snapshot.auditResult?.auditSession?.messages?.count { it.role == AuditMessageRole.USER })
        assertEquals("这里为什么会走兜底分支？", snapshot.auditResult?.auditSession?.messages?.first { it.role == AuditMessageRole.USER }?.content)
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
