package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphStateArchitectureGateTest {
    private val root: Path = Path.of("").toAbsolutePath()

    private fun read(relativePath: String): String = Files.readString(root.resolve(relativePath))
    private fun sourceBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) {
            return ""
        }
        val end = source.indexOf("\n)\n", start).takeIf { it >= 0 } ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun stateModelUsesCanonicalWorkspaceGraphsAndSceneStateOnly() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt")
        val snapshotSource = sourceBlock(source, "data class GraphEditorStateSnapshot(")

        assertTrue(snapshotSource.contains("val semanticFactGraph: GraphDocument"))
        assertTrue(snapshotSource.contains("val workspaceBaseGraph: GraphDocument"))
        assertTrue(snapshotSource.contains("val workspaceGraph: GraphDocument"))
        assertTrue(source.contains("enum class GraphSceneId"))
        assertTrue(snapshotSource.contains("val currentSceneId: GraphSceneId"))
        assertTrue(snapshotSource.contains("val sceneStates: Map<GraphSceneId, GraphSceneState>"))
        assertFalse(snapshotSource.contains("val visibleGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val workingGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val referenceWorkingGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val referenceFactGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val selectedNodeId: String?"))
        assertFalse(snapshotSource.contains("val layoutState: GraphLayoutState"))
        assertFalse(snapshotSource.contains("val diffMode: Boolean"))
    }

    @Test
    fun legacyWholeSnapshotAndViewGraphMutationPathsAreRemoved() {
        val stateServiceSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt")
        val projectSessionSource = read("src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt")
        val graphSupportSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphStateSupport.kt")
        val workflowSource = read("src/main/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflow.kt")

        assertFalse(stateServiceSource.contains("replaceSnapshot("))
        assertFalse(stateServiceSource.contains("newDraftMutationContext("))
        assertFalse(projectSessionSource.contains("GraphEditorStateSyncSession"))
        assertFalse(projectSessionSource.contains("markViewGraphChanged"))
        assertFalse(graphSupportSource.contains("markViewGraphChanged"))
        assertFalse(workflowSource.contains("handleFrontendGraphChanged("))
        assertFalse(Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt")))
    }

    @Test
    fun bridgeProtocolRemovesLegacyWholeGraphChangedMessage() {
        val messageSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt")
        val bridgeSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt")
        val routerSource = read("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt")

        assertFalse(messageSource.contains("data class GraphChanged"))
        assertFalse(bridgeSource.contains("graphChangedQuery"))
        assertFalse(bridgeSource.contains("GraphEditorMessage.GraphChanged"))
        assertFalse(routerSource.contains("handleFrontendGraphChanged"))
        assertTrue(messageSource.contains("data class ApplyGraphEditScript"))
        assertTrue(bridgeSource.contains("applyGraphEditScriptQuery"))
        assertTrue(routerSource.contains("ApplyGraphEditScript"))
    }
}
