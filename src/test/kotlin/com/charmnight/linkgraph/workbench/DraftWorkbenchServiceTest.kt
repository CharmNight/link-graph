package com.charmnight.linkgraph.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftWorkbenchServiceTest {
    @Test
    fun `confirming candidate change writes only to draft layer and marks graph as changed`() {
        val service = DraftWorkbenchService()

        val result = service.confirmCandidateChange(
            draft = DraftWorkbenchState(),
            candidate = CandidateDraftChange(
                changeId = "change-upload-condition",
                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                title = "修改条件判断",
                targetNodeIds = listOf("flow-action:upload-condition"),
                beforeState = "if (a > 10)",
                afterState = "if (a < 100)",
                reason = "原判断条件错误。",
                impactSummary = "会影响上传分支。",
            ),
        )

        assertEquals(1, result.draftChanges.size)
        assertEquals(DraftEntryKind.CHANGE, result.draftChanges.first().kind)
        assertEquals("change-upload-condition", result.draftChanges.first().sourceChangeId)
        assertTrue(result.graphChanged)
    }
}
