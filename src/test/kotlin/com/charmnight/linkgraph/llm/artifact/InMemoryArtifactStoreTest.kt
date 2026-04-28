package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
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
                    source = LlmResultSource.MOCK,
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
}
