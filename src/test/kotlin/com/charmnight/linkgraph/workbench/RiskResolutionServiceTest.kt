package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.testing.*
import com.charmnight.linkgraph.ui.toApplicationSnapshot

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.ui.GraphEditorStateService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskResolutionServiceTest {
    private val service = RiskResolutionService()

    @Test
    fun `apply resolution updates both top-level and session investigation threads`() {
        val result = GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = "请继续取证",
            answer = "需要继续下钻",
            promptPreview = "prompt",
            investigationThreads = listOf(
                InvestigationThread(
                    threadId = "thread-download-path",
                    status = InvestigationThreadStatus.OPEN,
                    title = "下载路径配置待确认",
                ),
            ),
            qaSession = QaConversationSession(
                sessionId = "qa-1",
                scopeKey = "method:fileDownload",
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-download-path",
                        status = InvestigationThreadStatus.OPEN,
                        title = "下载路径配置待确认",
                    ),
                ),
            ),
        )

        val updated = service.applyResolution(
            result = result,
            threadId = "thread-download-path",
            status = RiskResolutionStatus.DEFERRED,
        )

        assertEquals(RiskResolutionStatus.DEFERRED, updated?.investigationThreads?.single()?.resolution?.status)
        assertEquals(RiskResolutionStatus.DEFERRED, updated?.qaSession?.investigationThreads?.single()?.resolution?.status)
    }

    @Test
    fun `draft validation flags unresolved risks inside the draft stage`() {
        val decision = service.evaluateDraftValidation(
            testSnapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
                qaResult = GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = "请判断这里是否遗漏默认兜底",
                    answer = "存在待确认风险",
                    promptPreview = "prompt",
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-fallback",
                            status = InvestigationThreadStatus.OPEN,
                            title = "默认兜底待确认",
                            resolution = RiskResolution(
                                threadId = "thread-fallback",
                                status = RiskResolutionStatus.DEFERRED,
                            ),
                        ),
                    ),
                ),
            ).toApplicationSnapshot().toRiskResolutionSnapshot(),
        )

        assertEquals(DraftValidationStatus.REVIEW_REQUIRED, decision.status)
        assertEquals(listOf("thread-fallback"), decision.unresolvedThreadIds)
        assertTrue(decision.message.contains("待验证风险"))
    }

    @Test
    fun `deferred risk blocks code generation even with confirmed draft changes`() {
        val decision = service.evaluateCodeEligibility(
            testSnapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
                qaResult = GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = "请判断这里是否遗漏默认兜底",
                    answer = "存在待确认风险",
                    promptPreview = "prompt",
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-fallback",
                            status = InvestigationThreadStatus.OPEN,
                            title = "默认兜底待确认",
                            resolution = RiskResolution(
                                threadId = "thread-fallback",
                                status = RiskResolutionStatus.DEFERRED,
                            ),
                        ),
                    ),
                ),
            ).toApplicationSnapshot().toRiskResolutionSnapshot(),
        )

        assertFalse(decision.allowed)
        val detailMessage = assertNotNull(decision.detailMessage)
        assertTrue(detailMessage.contains("暂挂风险"))
    }

    @Test
    fun `accepted risk allows code generation when confirmed draft changes exist`() {
        val decision = service.evaluateCodeEligibility(
            testSnapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
                qaResult = GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = "请判断这里是否遗漏默认兜底",
                    answer = "存在待确认风险",
                    promptPreview = "prompt",
                    investigationThreads = listOf(
                        InvestigationThread(
                            threadId = "thread-fallback",
                            status = InvestigationThreadStatus.OPEN,
                            title = "默认兜底待确认",
                            resolution = RiskResolution(
                                threadId = "thread-fallback",
                                status = RiskResolutionStatus.ACCEPTED_RISK,
                            ),
                        ),
                    ),
                ),
            ).toApplicationSnapshot().toRiskResolutionSnapshot(),
        )

        assertTrue(decision.allowed)
        assertEquals("代码草稿", decision.stageLabel)
        assertNull(decision.detailMessage)
    }

    @Test
    fun `draft validation reports ready when confirmed draft changes have no unresolved risk`() {
        val decision = service.evaluateDraftValidation(
            testSnapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
            ).toApplicationSnapshot().toRiskResolutionSnapshot(),
        )

        assertEquals(DraftValidationStatus.READY, decision.status)
        assertNull(decision.detailMessage)
    }
}
