package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphEditorStateSyncSessionTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun legacyBatchSyncSessionIsDeletedInFavorOfStateStore() {
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt")),
        )
        assertTrue(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateStore.kt")),
        )
    }

    @Test
    fun projectEditorSessionUsesStateStoreCommitFlowInsteadOfDraftSnapshotBatch() {
        val source = Files.readString(
            root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt"),
        )

        assertFalse(source.contains("withGraphEditorStateSyncSession"))
        assertFalse(source.contains("markViewGraphChanged"))
        assertTrue(source.contains("tryCommit"))
    }
}
