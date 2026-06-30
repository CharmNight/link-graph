package com.charmnight.linkgraph.agent.artifact

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanSource
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryArtifactStoreTest {
    @Test
    fun savesLoadsAndFiltersArtifactsByType() {
        val store = InMemoryArtifactStore()
        val graphRef = store.save(
            GraphSummaryArtifact(
                artifactId = "graph-1",
                graph = com.charmnight.linkgraph.model.GraphDocument(),
                selectedNodeIds = emptyList(),
                graphSource = "workingGraph",
            ),
        )
        store.save(
            QaConclusionArtifact(
                artifactId = "qa-1",
                result = GraphPatchResult(
                    source = LlmResultSource.LOCAL_RULE,
                    question = "Q",
                    answer = "A",
                    promptPreview = "prompt",
                ),
            ),
        )

        assertEquals(ArtifactType.GRAPH_SUMMARY, store.get(graphRef)?.type)
        assertEquals(1, store.byType(ArtifactType.GRAPH_SUMMARY).size)
        assertEquals(1, store.byType(ArtifactType.QA_CONCLUSION).size)
    }

    @Test
    fun prunerSyncsWorkbenchScopedArtifactsByCurrentIds() {
        val store = InMemoryArtifactStore()
        store.save(
            ConfirmedIntentArtifact(
                artifactId = "confirmed-old",
                entry = DraftWorkbenchEntry(entryId = "old", kind = DraftEntryKind.CHANGE),
            ),
        )
        store.save(
            ConfirmedIntentArtifact(
                artifactId = "confirmed-current",
                entry = DraftWorkbenchEntry(entryId = "current", kind = DraftEntryKind.CHANGE),
            ),
        )
        store.save(
            CandidateDraftArtifact(
                artifactId = "candidate-old",
                candidate = CandidateDraftChange(
                    changeId = "old",
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                ),
            ),
        )

        ArtifactStorePruner.syncWorkbenchScopedArtifacts(
            artifactStore = store,
            type = ArtifactType.CONFIRMED_INTENT,
            currentArtifactIds = setOf("confirmed-current"),
        )
        ArtifactStorePruner.syncWorkbenchScopedArtifacts(
            artifactStore = store,
            type = ArtifactType.CANDIDATE_DRAFT,
            currentArtifactIds = emptySet(),
        )

        assertEquals(null, store.get("confirmed-old"))
        assertEquals(ArtifactType.CONFIRMED_INTENT, store.get("confirmed-current")?.type)
        assertEquals(null, store.get("candidate-old"))
    }

    @Test
    fun prunerRemovesRunScopedPlanArtifactsButKeepsCurrentWorkbenchPlan() {
        val store = InMemoryArtifactStore()
        store.save(
            PlanArtifact(
                artifactId = "plan-old-run-1",
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "旧运行期计划",
                ),
            ),
        )
        store.save(
            PlanArtifact(
                artifactId = "plan-current",
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "当前 workbench 计划",
                ),
            ),
        )

        ArtifactStorePruner.pruneAfterRun(
            artifactStore = store,
            currentArtifactIds = setOf("plan-current"),
        )

        assertEquals(null, store.get("plan-old-run-1"))
        assertEquals(ArtifactType.PLAN, store.get("plan-current")?.type)
    }
}
