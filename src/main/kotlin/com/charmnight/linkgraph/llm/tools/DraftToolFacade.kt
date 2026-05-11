package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.ArtifactRef
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.ArtifactStorePruner
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus

/**
 * 统一读取草稿箱相关状态。
 */
class DraftToolFacade {
    private val artifactStorePruner = ArtifactStorePruner

    /** 返回当前候选草稿。 */
    fun candidateDrafts(snapshot: ToolGraphSnapshot): List<CandidateDraftArtifact> {
        return snapshot.auditResult?.candidateChanges.orEmpty()
            .filter { change -> change.status == CandidateDraftChangeStatus.PENDING_CONFIRMATION }
            .map { change ->
            CandidateDraftArtifact(
                artifactId = "candidate-${change.changeId}",
                candidate = change,
            )
        }
    }

    /** 返回当前已确认正式意图。 */
    fun confirmedIntents(snapshot: ToolGraphSnapshot): List<ConfirmedIntentArtifact> {
        return snapshot.draftWorkbenchState.draftChanges.map { entry ->
            ConfirmedIntentArtifact(
                artifactId = "confirmed-${entry.entryId}",
                entry = entry,
            )
        }
    }

    /** 把当前已确认正式意图同步到 artifact store，并返回对应引用。 */
    fun syncConfirmedIntents(
        snapshot: ToolGraphSnapshot,
        artifactStore: ArtifactStore,
    ): List<ArtifactRef> {
        val currentArtifacts = confirmedIntents(snapshot)
        val currentIds = currentArtifacts.mapTo(linkedSetOf()) { artifact -> artifact.artifactId }
        artifactStorePruner.syncWorkbenchScopedArtifacts(
            artifactStore = artifactStore,
            type = ArtifactType.CONFIRMED_INTENT,
            currentArtifactIds = currentIds,
        )
        return currentArtifacts.map(artifactStore::save)
    }

    /** 把当前候选草稿同步到 artifact store，并返回对应引用。 */
    fun syncCandidateDrafts(
        snapshot: ToolGraphSnapshot,
        artifactStore: ArtifactStore,
    ): List<ArtifactRef> {
        val currentArtifacts = candidateDrafts(snapshot)
        val currentIds = currentArtifacts.mapTo(linkedSetOf()) { artifact -> artifact.artifactId }
        artifactStorePruner.syncWorkbenchScopedArtifacts(
            artifactStore = artifactStore,
            type = ArtifactType.CANDIDATE_DRAFT,
            currentArtifactIds = currentIds,
        )
        return currentArtifacts.map(artifactStore::save)
    }
}
