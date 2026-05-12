package com.charmnight.linkgraph.llm.artifact

/**
 * Centralizes artifact lifecycle rules so capabilities and tools do not each
 * invent their own cleanup policy.
 */
object ArtifactStorePruner {
    private val runScopedTypes = setOf(
        ArtifactType.GRAPH_SUMMARY,
        ArtifactType.GRAPH_DIFF,
        ArtifactType.CODE_EVIDENCE,
        ArtifactType.QA_EVIDENCE_TRACE,
        ArtifactType.QA_CONCLUSION,
    )

    fun pruneAfterRun(
        artifactStore: ArtifactStore,
        currentArtifactIds: Set<String>,
    ) {
        artifactStore.all()
            .filter(::isPrunableAfterRun)
            .map(AgentArtifact::artifactId)
            .filterNot { artifactId -> artifactId in currentArtifactIds }
            .forEach(artifactStore::remove)
    }

    fun syncWorkbenchScopedArtifacts(
        artifactStore: ArtifactStore,
        type: ArtifactType,
        currentArtifactIds: Set<String>,
    ) {
        require(type in workbenchScopedTypes) {
            "不支持按 workbench 当前集合同步的 artifact 类型: $type"
        }
        artifactStore.byType(type)
            .map(AgentArtifact::artifactId)
            .filterNot { artifactId -> artifactId in currentArtifactIds }
            .forEach(artifactStore::remove)
    }

    fun removeConfirmedIntent(
        artifactStore: ArtifactStore,
        entryId: String,
    ): Boolean {
        return artifactStore.remove("confirmed-$entryId")
    }

    private val workbenchScopedTypes = setOf(
        ArtifactType.CANDIDATE_DRAFT,
        ArtifactType.CONFIRMED_INTENT,
    )

    private fun isPrunableAfterRun(artifact: AgentArtifact): Boolean {
        if (artifact.type in runScopedTypes) {
            return true
        }
        return artifact.type == ArtifactType.PLAN && artifact.artifactId != "plan-current"
    }
}
