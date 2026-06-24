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
 *
 * 把"快照中的草稿数据"与"artifact store 中的产物"两边做同步：
 * - 读取候选/已确认草稿列表；
 * - 把这些草稿同步写入 artifact store（让后续 step 可以引用）；
 * - 通过 pruner 清理 store 中已不存在的过时产物。
 */
class DraftToolFacade {
    /** 产物修剪器单例引用。 */
    private val artifactStorePruner = ArtifactStorePruner

    /**
     * 返回当前候选草稿列表。
     * 只保留 PENDING_CONFIRMATION 状态（即尚未确认/拒绝）。
     */
    fun candidateDrafts(snapshot: ToolGraphSnapshot): List<CandidateDraftArtifact> {
        return snapshot.qaResult?.candidateChanges.orEmpty()
            .filter { change -> change.status == CandidateDraftChangeStatus.PENDING_CONFIRMATION }
            .map { change ->
                CandidateDraftArtifact(
                    artifactId = "candidate-${change.changeId}",
                    candidate = change,
                )
            }
    }

    /**
     * 返回当前已确认正式意图列表。
     * 已确认 = draftWorkbenchState.draftChanges 中的条目。
     */
    fun confirmedIntents(snapshot: ToolGraphSnapshot): List<ConfirmedIntentArtifact> {
        return snapshot.draftWorkbenchState.draftChanges.map { entry ->
            ConfirmedIntentArtifact(
                artifactId = "confirmed-${entry.entryId}",
                entry = entry,
            )
        }
    }

    /**
     * 把当前已确认正式意图同步到 artifact store，并返回对应引用。
     * 同步过程会清理 store 中已不存在的过时产物，保证 store 与工作台状态一致。
     */
    fun syncConfirmedIntents(
        snapshot: ToolGraphSnapshot,
        artifactStore: ArtifactStore,
    ): List<ArtifactRef> {
        val currentArtifacts = confirmedIntents(snapshot)
        val currentIds = currentArtifacts.mapTo(linkedSetOf()) { artifact -> artifact.artifactId }
        // 先清理过时产物，再写入当前产物，保证 store 与工作台一致
        artifactStorePruner.syncWorkbenchScopedArtifacts(
            artifactStore = artifactStore,
            type = ArtifactType.CONFIRMED_INTENT,
            currentArtifactIds = currentIds,
        )
        return currentArtifacts.map(artifactStore::save)
    }

    /**
     * 把当前候选草稿同步到 artifact store，并返回对应引用。
     * 与 [syncConfirmedIntents] 类似，但处理候选层。
     */
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
