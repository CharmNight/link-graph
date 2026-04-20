package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.ui.GraphEditorStateService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskResolutionServiceTest {
    private val service = RiskResolutionService()

    @Test
    fun `apply resolution updates both top-level and session investigation threads`() {
        val result = GraphPatchResult(
            source = LlmResultSource.MOCK,
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
            auditSession = AuditConversationSession(
                sessionId = "audit-1",
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
        assertEquals(RiskResolutionStatus.DEFERRED, updated?.auditSession?.investigationThreads?.single()?.resolution?.status)
    }

    @Test
    fun `deferred risk allows plan without confirmed draft changes`() {
        val decision = service.evaluatePlanEligibility(
            GraphEditorStateService.Snapshot(
                auditResult = GraphPatchResult(
                    source = LlmResultSource.MOCK,
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
            ),
        )

        assertTrue(decision.allowed)
        assertEquals("实现计划", decision.stageLabel)
    }

    @Test
    fun `deferred risk blocks code generation even with confirmed draft changes`() {
        val decision = service.evaluateCodeEligibility(
            GraphEditorStateService.Snapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
                auditResult = GraphPatchResult(
                    source = LlmResultSource.MOCK,
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
            ),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.detailMessage.contains("暂挂风险"))
    }

    @Test
    fun `accepted risk allows code generation when confirmed draft changes exist`() {
        val decision = service.evaluateCodeEligibility(
            GraphEditorStateService.Snapshot(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-1",
                            kind = DraftEntryKind.CHANGE,
                            title = "补充默认兜底",
                        ),
                    ),
                ),
                auditResult = GraphPatchResult(
                    source = LlmResultSource.MOCK,
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
            ),
        )

        assertTrue(decision.allowed)
        assertEquals("代码草稿", decision.stageLabel)
    }
}
