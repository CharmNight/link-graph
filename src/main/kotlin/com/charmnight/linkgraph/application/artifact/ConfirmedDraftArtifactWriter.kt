package com.charmnight.linkgraph.application.artifact

import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.ArtifactStorePruner
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

class ConfirmedDraftArtifactWriter(
    private val artifactStoreProvider: () -> ArtifactStore,
) {
    private val artifactStorePruner = ArtifactStorePruner

    fun recordConfirmedEntry(entry: DraftWorkbenchEntry?) {
        entry ?: return
        artifactStoreProvider().save(
            ConfirmedIntentArtifact(
                artifactId = "confirmed-${entry.entryId}",
                entry = entry,
            ),
        )
    }

    fun removeConfirmedEntry(entryId: String) {
        artifactStorePruner.removeConfirmedIntent(
            artifactStore = artifactStoreProvider(),
            entryId = entryId,
        )
    }
}
