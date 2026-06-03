package com.charmnight.linkgraph.architecture.query

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSemanticSeedIndexTest {
    @Test
    fun semanticSeedIsRecallOnlyAndMarkedAsSemanticEvidence() {
        val index = ProjectSemanticSeedIndex(
            enabled = true,
            records = listOf(
                ProjectSemanticSeedRecord(
                    nodeId = "class:OrderService",
                    text = "handles order checkout",
                    textHash = "hash",
                    embeddingModelId = "test-model",
                    sliceId = "slice:orders",
                ),
            ),
        )

        val result = index.search("checkout", topK = 1).single()

        assertEquals("class:OrderService", result.nodeId)
        assertEquals("SEMANTIC_SEED", result.metadata["evidence.kind"])
        assertEquals("RECALL_ONLY", result.metadata["evidence.role"])
    }
}
