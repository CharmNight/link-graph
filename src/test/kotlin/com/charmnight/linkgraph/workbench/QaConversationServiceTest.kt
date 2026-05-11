package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaConversationServiceTest {
    @Test
    fun `qa turn appends messages and extracts candidate changes without mutating draft`() {
        val service = QaConversationService()

        val result = service.applyModelTurn(
            session = QaConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
            ),
            modelTurn = QaModelTurn(
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
        assertEquals(QaMessageRole.ASSISTANT, result.session.messages.single().role)
        assertEquals(1, result.newCandidateChanges.size)
        assertEquals(1, result.session.candidateChanges.size)
        assertTrue(result.session.investigationThreads.isEmpty())
        assertEquals(emptyList(), result.session.turnOutcomes)
        assertTrue(result.draftWrites.isEmpty())
    }

    @Test
    fun `qa turn updates matching candidate id incrementally instead of replacing the whole list`() {
        val service = QaConversationService()

        val existing = CandidateDraftChange(
            changeId = "change-upload-condition",
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = "修改上传条件判断",
            afterState = "if (a < 50)",
        )

        val result = service.applyModelTurn(
            session = QaConversationSession(
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
            modelTurn = QaModelTurn(
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
    fun `qa turn promotes matching investigation thread when direct-evidence change arrives`() {
        val service = QaConversationService()

        val result = service.applyModelTurn(
            session = QaConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-upload-condition",
                        status = InvestigationThreadStatus.OPEN,
                        title = "上传条件判断可能有误",
                        targetNodeIds = listOf("flow-action:upload-condition"),
                    ),
                ),
            ),
            modelTurn = QaModelTurn(
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
        assertEquals(1, result.session.investigationThreads.size)
        assertEquals(InvestigationThreadStatus.PROMOTED, result.session.investigationThreads.single().status)
        assertEquals(InvestigationTurnOutcomeStatus.PROMOTED_TO_CANDIDATE, result.latestTurnOutcome?.status)
        assertEquals("change-upload-condition", result.session.focusTargetId)
    }

    @Test
    fun `follow-up investigation keeps a single open thread instead of appending semantically overlapping threads`() {
        val service = QaConversationService()
        val originalThread = InvestigationThread(
            threadId = "thread-upload-risk",
            status = InvestigationThreadStatus.OPEN,
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
            session = QaConversationSession(
                sessionId = "method-upload-file",
                scopeKey = "method:uploadFile",
                messages = listOf(
                    QaConversationMessage(
                        messageId = "qa-user-1",
                        role = QaMessageRole.USER,
                        content = "请继续取证：展开上传实现，确认路径校验是否真实存在。",
                    ),
                ),
                investigationThreads = listOf(originalThread),
                focusTargetId = "thread-upload-risk",
            ),
            modelTurn = QaModelTurn(
                answer = "继续取证后，仍然只能确认这是同一条上传风险主线，需要补充直接源码证据。",
                sourceThreadId = "thread-upload-risk",
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-upload-risk-follow-up",
                        status = InvestigationThreadStatus.OPEN,
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

        assertEquals(1, result.session.investigationThreads.count { it.status == InvestigationThreadStatus.OPEN })
        assertEquals(emptyList(), result.newInvestigationThreads)
        val mergedThread = result.session.investigationThreads.single()
        assertEquals("thread-upload-risk", mergedThread.threadId)
        assertEquals("上传路径校验仍待确认", mergedThread.title)
        assertEquals(2, mergedThread.evidence.size)
        assertEquals(1, result.session.investigationThreads.size)
        assertEquals("thread-upload-risk", result.session.investigationThreads.single().threadId)
        assertEquals(InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS, result.latestTurnOutcome?.status)
        assertEquals(listOf("flow-action:upload"), result.latestTurnOutcome?.evidenceDelta?.addedNodeIds)
        assertEquals("thread-upload-risk", result.session.focusTargetId)
    }

    @Test
    fun `follow-up investigation marks no-progress outcome when evidence and thread content do not advance`() {
        val service = QaConversationService()

        val result = service.applyModelTurn(
            session = QaConversationSession(
                sessionId = "method-download",
                scopeKey = "method:fileDownload",
                messages = listOf(
                    QaConversationMessage(
                        messageId = "qa-user-1",
                        role = QaMessageRole.USER,
                        content = "请继续取证：确认下载路径配置是如何解析的。",
                    ),
                ),
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-download-path",
                        status = InvestigationThreadStatus.OPEN,
                        title = "下载路径配置待确认",
                        targetNodeIds = listOf("method:file-download"),
                        summary = "当前只看到下载入口。",
                        evidenceGap = "还没有看到配置解析实现。",
                        recommendedQuestion = "请继续取证：确认下载路径配置是如何解析的。",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "download-callsite",
                                claim = "当前只看到 fileDownload 调用点。",
                                evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                            ),
                        ),
                    ),
                ),
                turnOutcomes = listOf(
                    InvestigationTurnOutcome(
                        outcomeId = "turn-1",
                        threadId = "thread-download-path",
                        status = InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS,
                        summary = "下载路径配置待确认",
                        detail = "已补充下载入口信息。",
                        observedNodeIds = listOf("method:file-download"),
                        observedFilePaths = listOf("CommonController.java"),
                        strongestEvidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                    ),
                ),
                focusTargetId = "thread-download-path",
            ),
            modelTurn = QaModelTurn(
                answer = "继续取证后，当前仍然只有原有调用点证据。",
                sourceThreadId = "thread-download-path",
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-download-follow-up",
                        status = InvestigationThreadStatus.OPEN,
                        title = "下载路径配置待确认",
                        targetNodeIds = listOf("method:file-download"),
                        summary = "当前只看到下载入口。",
                        evidenceGap = "还没有看到配置解析实现。",
                        recommendedQuestion = "请继续取证：确认下载路径配置是如何解析的。",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "download-callsite",
                                claim = "当前只看到 fileDownload 调用点。",
                                evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                            ),
                        ),
                    ),
                ),
                observedNodeIds = listOf("method:file-download"),
                observedFilePaths = listOf("CommonController.java"),
            ),
        )

        assertEquals(InvestigationTurnOutcomeStatus.OPEN_NO_PROGRESS, result.latestTurnOutcome?.status)
        assertEquals(emptyList(), result.latestTurnOutcome?.evidenceDelta?.addedNodeIds)
        assertEquals(emptyList(), result.latestTurnOutcome?.evidenceDelta?.addedFilePaths)
    }
}
