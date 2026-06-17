package com.charmnight.linkgraph.perf

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.projection.business.ArchitectureGraphProjector
import com.charmnight.linkgraph.projection.business.ClassDiagramProjector
import com.charmnight.linkgraph.json.JsonCodec
import com.charmnight.linkgraph.llm.tools.ToolResult
import com.charmnight.linkgraph.review.BlastRadius
import com.charmnight.linkgraph.review.ReviewEvidenceBundle
import com.charmnight.linkgraph.projection.business.ReviewGraphProjector
import java.nio.charset.StandardCharsets

data class IndexedGraphBenchmarkReport(
    val coldFullIndexMillis: Long,
    val architectureOverviewMillis: Long,
    val classDiagramFirstCompleteMillis: Long,
    val reviewGraphMillis: Long,
    val architecturePayloadBytes: Long,
    val classDiagramPayloadBytes: Long,
    val reviewPayloadBytes: Long,
    val agentExploreToolMillis: Long,
    val agentExplorePayloadBytes: Long,
    val persistentCacheHits: Long = 0,
    val persistentCacheMisses: Long = 0,
) {
    fun toJson(): String = JsonCodec.toJson(
        mapOf(
            "coldFullIndexMillis" to coldFullIndexMillis,
            "architectureOverviewMillis" to architectureOverviewMillis,
            "classDiagramFirstCompleteMillis" to classDiagramFirstCompleteMillis,
            "reviewGraphMillis" to reviewGraphMillis,
            "architecturePayloadBytes" to architecturePayloadBytes,
            "classDiagramPayloadBytes" to classDiagramPayloadBytes,
            "reviewPayloadBytes" to reviewPayloadBytes,
            "agentExploreToolMillis" to agentExploreToolMillis,
            "agentExplorePayloadBytes" to agentExplorePayloadBytes,
            "persistentCacheHits" to persistentCacheHits,
            "persistentCacheMisses" to persistentCacheMisses,
        ),
    )

    companion object {
        fun sample(): IndexedGraphBenchmarkReport =
            IndexedGraphBenchmarkReport(
                coldFullIndexMillis = 0,
                architectureOverviewMillis = 0,
                classDiagramFirstCompleteMillis = 0,
                reviewGraphMillis = 0,
                architecturePayloadBytes = 0,
                classDiagramPayloadBytes = 0,
                reviewPayloadBytes = 0,
                agentExploreToolMillis = 0,
                agentExplorePayloadBytes = 0,
                persistentCacheHits = 0,
                persistentCacheMisses = 0,
            )
    }
}

data class IndexedGraphBenchmarkCacheStats(
    val hits: Long = 0,
    val misses: Long = 0,
)

class IndexedGraphBenchmarkRunner(
    private val coldFullIndex: () -> ArchitectureGraphIndex,
    private val agentExploreTool: () -> ToolResult,
    private val persistentCacheStats: () -> IndexedGraphBenchmarkCacheStats = { IndexedGraphBenchmarkCacheStats() },
    private val architectureProjector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val classDiagramProjector: ClassDiagramProjector = ClassDiagramProjector(),
    private val reviewGraphProjector: ReviewGraphProjector = ReviewGraphProjector(),
) {
    fun run(): IndexedGraphBenchmarkReport {
        val cold = measure { coldFullIndex() }
        val index = cold.value
        val architectureOverview = measurePayload { architectureProjector.project(index) }
        val classDiagram = measurePayload { classDiagramProjector.project(index) }
        val reviewGraph = measurePayload { reviewGraphProjector.project(emptyReviewBundle(), index = index) }
        val agentExplore = measurePayload { agentExploreTool() }
        val cacheStats = persistentCacheStats()
        return IndexedGraphBenchmarkReport(
            coldFullIndexMillis = cold.elapsedMillis,
            architectureOverviewMillis = architectureOverview.elapsedMillis,
            classDiagramFirstCompleteMillis = classDiagram.elapsedMillis,
            reviewGraphMillis = reviewGraph.elapsedMillis,
            architecturePayloadBytes = architectureOverview.payloadBytes,
            classDiagramPayloadBytes = classDiagram.payloadBytes,
            reviewPayloadBytes = reviewGraph.payloadBytes,
            agentExploreToolMillis = agentExplore.elapsedMillis,
            agentExplorePayloadBytes = agentExplore.payloadBytes,
            persistentCacheHits = cacheStats.hits,
            persistentCacheMisses = cacheStats.misses,
        )
    }

    private fun emptyReviewBundle(): ReviewEvidenceBundle =
        ReviewEvidenceBundle(
            changedSymbols = emptyList(),
            blastRadius = BlastRadius(
                changedSymbols = emptyList(),
                upstream = emptyList(),
                downstream = emptyList(),
                spiProviders = emptyList(),
                reflectionTargets = emptyList(),
                serviceLoaderLoads = emptyList(),
                proxyTargets = emptyList(),
                relatedTests = emptyList(),
            ),
            evidenceRefs = emptyList(),
        )

    private fun <T> measure(action: () -> T): Measurement<T> {
        val started = System.nanoTime()
        val value = action()
        return Measurement(
            value = value,
            elapsedMillis = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L),
        )
    }

    private fun measurePayload(action: () -> Any?): PayloadMeasurement {
        val measured = measure(action)
        return PayloadMeasurement(
            elapsedMillis = measured.elapsedMillis,
            payloadBytes = JsonCodec.toJson(measured.value).toByteArray(StandardCharsets.UTF_8).size.toLong(),
        )
    }

    private data class Measurement<T>(
        val value: T,
        val elapsedMillis: Long,
    )

    private data class PayloadMeasurement(
        val elapsedMillis: Long,
        val payloadBytes: Long,
    )
}
