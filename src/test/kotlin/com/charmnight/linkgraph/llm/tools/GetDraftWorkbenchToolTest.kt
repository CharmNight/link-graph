package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetDraftWorkbenchToolTest : BasePlatformTestCase() {
    fun testReturnsCandidateAndConfirmedDraftSummaries() {
        val result = GetDraftWorkbenchTool(DraftToolFacade()).invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = project,
                snapshot = testSnapshot(
                    auditResult = com.charmnight.linkgraph.llm.GraphPatchResult(
                        source = com.charmnight.linkgraph.llm.LlmResultSource.MOCK,
                        question = "Q",
                        answer = "A",
                        promptPreview = "prompt",
                        candidateChanges = listOf(
                            CandidateDraftChange(
                                changeId = "candidate-1",
                                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                                title = "修改上传条件",
                                reason = "当前条件不正确。",
                            ),
                        ),
                    ),
                    draftWorkbenchState = DraftWorkbenchState(
                        draftChanges = listOf(
                            DraftWorkbenchEntry(
                                entryId = "confirmed-1",
                                kind = DraftEntryKind.CHANGE,
                                title = "正式上传修改",
                            ),
                        ),
                    ),
                ),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val candidates = result.payload["candidateDrafts"] as List<*>
        val confirmed = result.payload["confirmedIntents"] as List<*>

        assertEquals(1, result.payload["candidateCount"])
        assertEquals(1, result.payload["confirmedCount"])
        assertEquals(1, candidates.size)
        assertEquals(1, confirmed.size)
        assertEquals(ArtifactType.CANDIDATE_DRAFT, (candidates.first() as CandidateDraftArtifact).type)
        assertEquals(ArtifactType.CONFIRMED_INTENT, (confirmed.first() as ConfirmedIntentArtifact).type)
    }

    fun testDoesNotExposeConfirmedCandidatesAsCandidateDrafts() {
        val result = GetDraftWorkbenchTool(DraftToolFacade()).invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = project,
                snapshot = testSnapshot(
                    auditResult = com.charmnight.linkgraph.llm.GraphPatchResult(
                        source = com.charmnight.linkgraph.llm.LlmResultSource.MOCK,
                        question = "Q",
                        answer = "A",
                        promptPreview = "prompt",
                        candidateChanges = listOf(
                            CandidateDraftChange(
                                changeId = "candidate-pending",
                                status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                                title = "待确认变更",
                                reason = "pending",
                            ),
                            CandidateDraftChange(
                                changeId = "candidate-confirmed",
                                status = CandidateDraftChangeStatus.CONFIRMED,
                                title = "已确认变更",
                                reason = "confirmed",
                            ),
                        ),
                    ),
                    draftWorkbenchState = DraftWorkbenchState(
                        draftChanges = listOf(
                            DraftWorkbenchEntry(
                                entryId = "confirmed-1",
                                kind = DraftEntryKind.CHANGE,
                                title = "正式上传修改",
                            ),
                        ),
                    ),
                ),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val candidates = result.payload["candidateDrafts"] as List<*>

        assertEquals(1, result.payload["candidateCount"])
        assertEquals(1, candidates.size)
        assertEquals(
            "candidate-pending",
            (candidates.single() as CandidateDraftArtifact).candidate.changeId,
        )
    }
}
