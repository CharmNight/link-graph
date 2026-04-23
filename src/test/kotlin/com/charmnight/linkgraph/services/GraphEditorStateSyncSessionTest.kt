package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.ui.GraphEditorStateService
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphEditorStateSyncSessionTest {
    @Test
    fun flushesBrowserSyncOnlyOnceAfterMultipleStateMutations() {
        val stateService = GraphEditorStateService()
        var syncCount = 0

        withGraphEditorStateSyncSession(
            stateService = stateService,
            onSyncRequested = { syncCount += 1 },
        ) {
            apply {
                asyncRequests.beginGenerationPlanRequest()
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.INFO,
                    "正在生成实现计划，请稍候。",
                )
            }
            assertEquals("operationFeedback", snapshot().lastMessageType)
        }

        assertEquals(1, syncCount)
    }

    @Test
    fun doesNotSyncBrowserWhenNoMutationWasApplied() {
        val stateService = GraphEditorStateService()
        var syncCount = 0

        withGraphEditorStateSyncSession(
            stateService = stateService,
            onSyncRequested = { syncCount += 1 },
        ) {
            assertEquals(null, snapshot().lastMessageType)
        }

        assertEquals(0, syncCount)
    }
}
