package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcome
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcomeStatus
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.QaRequestKind
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditResultNormalizerTest {
    private val 归一化器 = AuditResultNormalizer()

    @Test
    fun answerModeDropsCandidatesThreadsPatchAndSessionOutcomesAfterMerge() {
        val context = modeContext(QaMode.ANSWER, baseSession = sessionWithHistory())

        val normalized = 归一化器.normalize(
            result = baseResult(
                candidateChanges = listOf(candidate("new-change")),
                investigationThreads = listOf(thread("new-thread")),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.AUTO, normalized.requestedMode)
        assertEquals(QaMode.ANSWER, normalized.effectiveMode)
        assertNull(normalized.patch)
        assertTrue(normalized.candidateChanges.isEmpty())
        assertTrue(normalized.newCandidateChanges.isEmpty())
        assertTrue(normalized.investigationThreads.isEmpty())
        assertNull(normalized.latestTurnOutcome)
        assertTrue(normalized.recentTurnOutcomes.isEmpty())
        assertTrue(normalized.auditSession?.candidateChanges?.isEmpty() == true)
        assertTrue(normalized.auditSession?.investigationThreads?.isEmpty() == true)
        assertTrue(normalized.auditSession?.turnOutcomes?.isEmpty() == true)
        assertNull(normalized.auditSession?.focusTargetId)
    }

    @Test
    fun reviewModeDropsCandidatesButKeepsRiskThreads() {
        val context = modeContext(QaMode.REVIEW)

        val normalized = 归一化器.normalize(
            result = baseResult(
                candidateChanges = listOf(candidate("review-change")),
                investigationThreads = listOf(thread("review-thread")),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.REVIEW, normalized.effectiveMode)
        assertNull(normalized.patch)
        assertTrue(normalized.candidateChanges.isEmpty())
        assertTrue(normalized.newCandidateChanges.isEmpty())
        assertEquals(listOf("review-thread"), normalized.investigationThreads.map(InvestigationThread::threadId))
        assertTrue(normalized.auditSession?.candidateChanges?.isEmpty() == true)
        assertEquals(listOf("review-thread"), normalized.auditSession?.investigationThreads?.map(InvestigationThread::threadId))
    }

    @Test
    fun changeModeKeepsCandidatesAndRiskThreads() {
        val context = modeContext(QaMode.CHANGE)

        val normalized = 归一化器.normalize(
            result = baseResult(
                candidateChanges = listOf(candidate("change-1")),
                investigationThreads = listOf(thread("thread-1")),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.CHANGE, normalized.effectiveMode)
        assertEquals(listOf("change-1"), normalized.candidateChanges.map(CandidateDraftChange::changeId))
        assertEquals(listOf("change-1"), normalized.newCandidateChanges.map(CandidateDraftChange::changeId))
        assertEquals(listOf("thread-1"), normalized.investigationThreads.map(InvestigationThread::threadId))
        assertEquals(listOf("thread-1"), normalized.auditSession?.investigationThreads?.map(InvestigationThread::threadId))
    }

    @Test
    fun autoModeKeepsCandidatesAndRiskThreads() {
        val context = modeContext(QaMode.AUTO)

        val normalized = 归一化器.normalize(
            result = baseResult(
                candidateChanges = listOf(candidate("auto-change")),
                investigationThreads = listOf(thread("auto-thread")),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.AUTO, normalized.effectiveMode)
        assertEquals(listOf("auto-change"), normalized.candidateChanges.map(CandidateDraftChange::changeId))
        assertEquals(listOf("auto-thread"), normalized.investigationThreads.map(InvestigationThread::threadId))
    }

    @Test
    fun investigateModeFiltersTopLevelToSourceThreadButPreservesOtherSessionThreads() {
        val context = modeContext(
            effectiveMode = QaMode.INVESTIGATE,
            sourceThreadId = "thread-a",
            baseSession = AuditConversationSession(
                sessionId = "session-investigate",
                scopeKey = "scope",
                investigationThreads = listOf(thread("thread-a"), thread("thread-b")),
                turnOutcomes = listOf(outcome("thread-a"), outcome("thread-b")),
            ),
        )

        val normalized = 归一化器.normalize(
            result = baseResult(
                investigationThreads = listOf(thread("thread-a"), thread("thread-b")),
                latestTurnOutcome = outcome("thread-a"),
                recentTurnOutcomes = listOf(outcome("thread-a"), outcome("thread-b")),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.INVESTIGATE, normalized.effectiveMode)
        assertNull(normalized.patch)
        assertTrue(normalized.candidateChanges.isEmpty())
        assertEquals(listOf("thread-a"), normalized.investigationThreads.map(InvestigationThread::threadId))
        assertEquals("thread-a", normalized.latestTurnOutcome?.threadId)
        assertEquals(listOf("thread-a"), normalized.recentTurnOutcomes.map(InvestigationTurnOutcome::threadId))
        assertEquals(
            setOf("thread-a", "thread-b"),
            normalized.auditSession?.investigationThreads?.map(InvestigationThread::threadId)?.toSet(),
        )
        assertEquals("thread-a", normalized.auditSession?.focusTargetId)
    }

    @Test
    fun resultWithExistingSessionIsStampedAndBoundaryAppliedWithoutReclassifying() {
        val context = modeContext(QaMode.REVIEW)

        val normalized = 归一化器.normalize(
            result = baseResult(
                candidateChanges = listOf(candidate("existing-change")),
                investigationThreads = listOf(thread("existing-thread")),
                auditSession = AuditConversationSession(
                    sessionId = "already-normalized",
                    scopeKey = "scope",
                    candidateChanges = listOf(candidate("session-change")),
                    investigationThreads = listOf(thread("session-thread")),
                ),
            ),
            modeContext = context,
        )

        assertEquals(QaMode.AUTO, normalized.requestedMode)
        assertEquals(QaMode.REVIEW, normalized.effectiveMode)
        assertTrue(normalized.candidateChanges.isEmpty())
        assertEquals(listOf("existing-thread"), normalized.investigationThreads.map(InvestigationThread::threadId))
        assertTrue(normalized.auditSession?.candidateChanges?.isEmpty() == true)
        assertEquals(listOf("session-thread"), normalized.auditSession?.investigationThreads?.map(InvestigationThread::threadId))
    }

    private fun modeContext(
        effectiveMode: QaMode,
        sourceThreadId: String? = null,
        baseSession: AuditConversationSession? = null,
    ): QaModeContext {
        return QaModeContext(
            request = ReplayableQaRequest(
                requestId = "request-1",
                kind = if (sourceThreadId == null) QaRequestKind.ASK else QaRequestKind.INVESTIGATE_THREAD,
                question = "检查这个方法",
                mode = QaMode.AUTO,
                selectedNodeIds = listOf("method:target"),
                sourceThreadId = sourceThreadId,
                baseSession = baseSession,
            ),
            effectiveMode = effectiveMode,
        )
    }

    private fun baseResult(
        candidateChanges: List<CandidateDraftChange> = emptyList(),
        investigationThreads: List<InvestigationThread> = emptyList(),
        latestTurnOutcome: InvestigationTurnOutcome? = null,
        recentTurnOutcomes: List<InvestigationTurnOutcome> = emptyList(),
        auditSession: AuditConversationSession? = null,
    ): GraphPatchResult {
        return GraphPatchResult(
            source = LlmResultSource.MOCK,
            question = "检查这个方法",
            answer = "answer",
            promptPreview = "prompt",
            patch = patch(),
            candidateChanges = candidateChanges,
            investigationThreads = investigationThreads,
            latestTurnOutcome = latestTurnOutcome,
            recentTurnOutcomes = recentTurnOutcomes,
            auditSession = auditSession,
        )
    }

    private fun sessionWithHistory(): AuditConversationSession {
        return AuditConversationSession(
            sessionId = "session-history",
            scopeKey = "scope",
            candidateChanges = listOf(candidate("old-change")),
            investigationThreads = listOf(thread("old-thread")),
            turnOutcomes = listOf(outcome("old-thread")),
            focusTargetId = "old-thread",
        )
    }

    private fun candidate(changeId: String): CandidateDraftChange {
        return CandidateDraftChange(
            changeId = changeId,
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = changeId,
            targetNodeIds = listOf("method:target"),
            evidence = listOf(finding("$changeId-finding")),
        )
    }

    private fun thread(threadId: String): InvestigationThread {
        return InvestigationThread(
            threadId = threadId,
            status = InvestigationThreadStatus.OPEN,
            title = threadId,
            targetNodeIds = listOf("method:target"),
            evidence = listOf(finding("$threadId-finding")),
        )
    }

    private fun outcome(threadId: String): InvestigationTurnOutcome {
        return InvestigationTurnOutcome(
            outcomeId = "outcome-$threadId",
            threadId = threadId,
            status = InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS,
        )
    }

    private fun finding(id: String): ResultEvidenceFinding {
        return ResultEvidenceFinding(
            id = id,
            claim = "直接证据",
            evidenceLevel = ResultEvidenceLevel.DIRECT_GRAPH,
            references = listOf(ResultEvidenceReference(nodeId = "method:target")),
        )
    }

    private fun patch(): GraphPatch {
        return GraphPatch(
            summary = "patch",
            operations = listOf(
                GraphPatchOperation(
                    id = "op-1",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "method:new",
                    node = GraphNode(
                        id = "method:new",
                        type = NodeType.METHOD,
                        title = "新增节点",
                        sourceTag = GraphSourceTag.DRAFT_AI,
                    ),
                ),
            ),
        )
    }
}
