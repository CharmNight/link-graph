package com.charmnight.linkgraph.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditConversationServiceTest {
    @Test
    fun `audit turn appends messages and extracts candidate changes without mutating draft`() {
        val service = AuditConversationService()

        val result = service.applyModelTurn(
            session = AuditConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
            ),
            modelTurn = AuditModelTurn(
                answer = "建议把上传条件从 a > 10 改成 a < 100。",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-upload-condition",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "修改上传条件判断",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                        beforeState = "if (a > 10)",
                        afterState = "if (a < 100)",
                        reason = "当前判断条件与业务预期不一致。",
                        impactSummary = "会改变上传逻辑进入分支。",
                    ),
                ),
            ),
        )

        assertEquals(1, result.session.messages.size)
        assertEquals(AuditMessageRole.ASSISTANT, result.session.messages.single().role)
        assertEquals(1, result.newCandidateChanges.size)
        assertEquals(1, result.session.candidateChanges.size)
        assertTrue(result.draftWrites.isEmpty())
    }

    @Test
    fun `audit turn updates matching candidate id incrementally instead of replacing the whole list`() {
        val service = AuditConversationService()

        val existing = CandidateDraftChange(
            changeId = "change-upload-condition",
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = "修改上传条件判断",
            afterState = "if (a < 50)",
        )

        val result = service.applyModelTurn(
            session = AuditConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
                candidateChanges = listOf(
                    existing,
                    CandidateDraftChange(
                        changeId = "change-log-text",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "调整日志内容",
                        afterState = """print("nihao")""",
                    ),
                ),
            ),
            modelTurn = AuditModelTurn(
                answer = "条件判断建议继续修正为 a < 100。",
                candidateChanges = listOf(
                    existing.copy(afterState = "if (a < 100)"),
                ),
            ),
        )

        assertEquals(2, result.session.candidateChanges.size)
        assertEquals(emptyList(), result.newCandidateChanges)
        assertEquals("if (a < 100)", result.session.candidateChanges.first { it.changeId == "change-upload-condition" }.afterState)
        assertEquals("""print("nihao")""", result.session.candidateChanges.first { it.changeId == "change-log-text" }.afterState)
    }
}
