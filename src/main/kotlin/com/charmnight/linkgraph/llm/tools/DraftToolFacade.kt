package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.ArtifactRef
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus

/**
 * 统一读取草稿箱相关状态。
 */
class DraftToolFacade {
    /** 返回当前候选草稿。 */
    fun candidateDrafts(snapshot: GraphEditorStateService.Snapshot): List<CandidateDraftArtifact> {
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
    fun confirmedIntents(snapshot: GraphEditorStateService.Snapshot): List<ConfirmedIntentArtifact> {
        return snapshot.draftWorkbenchState.draftChanges.map { entry ->
            ConfirmedIntentArtifact(
                artifactId = "confirmed-${entry.entryId}",
                entry = entry,
            )
        }
    }

    /** 把当前已确认正式意图同步到 artifact store，并返回对应引用。 */
    fun syncConfirmedIntents(
        snapshot: GraphEditorStateService.Snapshot,
        artifactStore: ArtifactStore,
    ): List<ArtifactRef> {
        val currentArtifacts = confirmedIntents(snapshot)
        val currentIds = currentArtifacts.mapTo(linkedSetOf()) { artifact -> artifact.artifactId }
        artifactStore.byType(ArtifactType.CONFIRMED_INTENT)
            .map { artifact -> artifact.artifactId }
            .filterNot { artifactId -> artifactId in currentIds }
            .forEach(artifactStore::remove)
        return currentArtifacts.map(artifactStore::save)
    }

    /** 把当前候选草稿同步到 artifact store，并返回对应引用。 */
    fun syncCandidateDrafts(
        snapshot: GraphEditorStateService.Snapshot,
        artifactStore: ArtifactStore,
    ): List<ArtifactRef> {
        val currentArtifacts = candidateDrafts(snapshot)
        val currentIds = currentArtifacts.mapTo(linkedSetOf()) { artifact -> artifact.artifactId }
        artifactStore.byType(ArtifactType.CANDIDATE_DRAFT)
            .map { artifact -> artifact.artifactId }
            .filterNot { artifactId -> artifactId in currentIds }
            .forEach(artifactStore::remove)
        return currentArtifacts.map(artifactStore::save)
    }
}
