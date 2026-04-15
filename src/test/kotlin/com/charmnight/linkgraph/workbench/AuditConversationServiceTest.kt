package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
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
        assertTrue(result.session.investigationLeads.isEmpty())
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

    @Test
    fun `audit turn promotes matching investigation lead when direct-evidence change arrives`() {
        val service = AuditConversationService()

        val result = service.applyModelTurn(
            session = AuditConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
                investigationLeads = listOf(
                    AuditInvestigationLead(
                        leadId = "lead-upload-condition",
                        status = AuditInvestigationLeadStatus.OPEN,
                        title = "上传条件判断可能有误",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                    ),
                ),
            ),
            modelTurn = AuditModelTurn(
                answer = "现在已经拿到直接源码证据，可以转成真实变更。",
                candidateChanges = listOf(
                    CandidateDraftChange(
                        changeId = "change-upload-condition",
                        status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                        title = "修正上传条件判断",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                    ),
                ),
            ),
        )

        assertEquals(1, result.session.candidateChanges.size)
        assertEquals(
            AuditInvestigationLeadStatus.PROMOTED,
            result.session.investigationLeads.single().status,
        )
        assertEquals("change-upload-condition", result.session.focusTargetId)
    }

    @Test
    fun `follow-up investigation keeps a single open lead instead of appending semantically overlapping leads`() {
        val service = AuditConversationService()
        val originalLead = AuditInvestigationLead(
            leadId = "lead-upload-risk",
            status = AuditInvestigationLeadStatus.OPEN,
            title = "上传路径风险待确认",
            targetNodeIds = listOf("flow-action:upload"),
            summary = "当前只看到上传入口。",
            evidenceGap = "还没有看到上传实现里的路径校验。",
            recommendedQuestion = "请继续取证：展开上传实现。",
            claimType = "RISK_HINT",
            evidence = listOf(
                ResultEvidenceFinding(
                    id = "finding-upload-callsite",
                    claim = "这里只能看到上传调用点。",
                    evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                ),
            ),
        )

        val result = service.applyModelTurn(
            session = AuditConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
                messages = listOf(
                    AuditConversationMessage(
                        messageId = "audit-user-1",
                        role = AuditMessageRole.USER,
                        content = "请继续取证：展开上传实现，确认路径校验是否真实存在。",
                    ),
                ),
                investigationLeads = listOf(originalLead),
                focusTargetId = "lead-upload-risk",
            ),
            modelTurn = AuditModelTurn(
                answer = "继续取证后，仍然只能确认这是同一条上传风险主线，需要补充直接源码证据。",
                sourceLeadId = "lead-upload-risk",
                investigationLeads = listOf(
                    AuditInvestigationLead(
                        leadId = "lead-upload-risk-follow-up",
                        status = AuditInvestigationLeadStatus.OPEN,
                        title = "上传路径校验仍待确认",
                        targetNodeIds = listOf("flow-action:upload"),
                        summary = "当前看到上传调用继续向下，但还没有直接看到路径规范化或目录校验。",
                        evidenceGap = "仍然缺少 upload 实现中的直接源码证据。",
                        recommendedQuestion = "请继续取证：定位 upload 实现里真正执行文件落盘前的路径校验。",
                        claimType = "RISK_HINT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-upload-impl-missing",
                                claim = "当前仍未直接观察到路径校验实现。",
                                evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, result.session.investigationLeads.count { it.status == AuditInvestigationLeadStatus.OPEN })
        assertEquals(emptyList(), result.newInvestigationLeads)
        val mergedLead = result.session.investigationLeads.single()
        assertEquals("lead-upload-risk", mergedLead.leadId)
        assertEquals("上传路径校验仍待确认", mergedLead.title)
        assertEquals(2, mergedLead.evidence.size)
        assertEquals("lead-upload-risk", result.session.focusTargetId)
    }
}
