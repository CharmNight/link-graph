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

/**
 * 索引化图管线的一次基准测试结果。
 *
 * 记录冷启动全量索引耗时、各投影产物的生成耗时和序列化后字节数，
 * 用于衡量插件在不同输入规模下的表现是否在可接受范围内。
 */
data class IndexedGraphBenchmarkReport(
    /** 全量冷索引构建耗时（毫秒）。 */
    val coldFullIndexMillis: Long,
    /** 架构总览投影的生成耗时（毫秒）。 */
    val architectureOverviewMillis: Long,
    /** 类图首次完整生成的耗时（毫秒）。 */
    val classDiagramFirstCompleteMillis: Long,
    /** 评审图投影的生成耗时（毫秒）。 */
    val reviewGraphMillis: Long,
    /** 架构总览投影序列化后的字节数。 */
    val architecturePayloadBytes: Long,
    /** 类图投影序列化后的字节数。 */
    val classDiagramPayloadBytes: Long,
    /** 评审图投影序列化后的字节数。 */
    val reviewPayloadBytes: Long,
    /** Agent 探索工具单次调用耗时（毫秒）。 */
    val agentExploreToolMillis: Long,
    /** Agent 探索工具单次调用返回结果序列化后的字节数。 */
    val agentExplorePayloadBytes: Long,
    /** 持久化缓存命中次数，0 表示未启用或未命中。 */
    val persistentCacheHits: Long = 0,
    /** 持久化缓存未命中次数。 */
    val persistentCacheMisses: Long = 0,
) {
    /**
     * 把测试结果序列化成扁平 JSON，便于落盘或导出对比。
     */
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
        /**
         * 返回一份全零的占位测试结果。
         * 主要用于占位、序列兼容以及在没有真实运行时数据时回退展示。
         */
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

/**
 * 持久化缓存的命中/未命中计数。
 */
data class IndexedGraphBenchmarkCacheStats(
    /** 缓存命中次数。 */
    val hits: Long = 0,
    /** 缓存未命中次数。 */
    val misses: Long = 0,
)

/**
 * 索引化图管线的基准测试执行器。
 *
 * 通过传入的工厂函数构造各阶段输入，统一调用各投影器生成图，
 * 并测量耗时与序列化体积，输出一份 [IndexedGraphBenchmarkReport]。
 */
class IndexedGraphBenchmarkRunner(
    /** 触发一次冷启动全量索引构建，返回最终的架构图索引。 */
    private val coldFullIndex: () -> ArchitectureGraphIndex,
    /** 触发一次 Agent 探索工具调用，返回原始工具结果。 */
    private val agentExploreTool: () -> ToolResult,
    /** 提供持久化缓存的命中统计，默认为全零。 */
    private val persistentCacheStats: () -> IndexedGraphBenchmarkCacheStats = { IndexedGraphBenchmarkCacheStats() },
    /** 架构总览投影器，可在测试中替换为桩实现。 */
    private val architectureProjector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    /** 类图投影器，可在测试中替换为桩实现。 */
    private val classDiagramProjector: ClassDiagramProjector = ClassDiagramProjector(),
    /** 评审图投影器，可在测试中替换为桩实现。 */
    private val reviewGraphProjector: ReviewGraphProjector = ReviewGraphProjector(),
) {
    /**
     * 执行完整的一次基准测试流程并汇总报告。
     */
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

    /**
     * 构造一份空的评审证据包，使评审图投影在基准测试中保持稳定的输入。
     */
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

    /**
     * 测量一段动作的耗时，并返回结果与毫秒级耗时。
     */
    private fun <T> measure(action: () -> T): Measurement<T> {
        val started = System.nanoTime()
        val value = action()
        return Measurement(
            value = value,
            elapsedMillis = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L),
        )
    }

    /**
     * 测量一段动作的耗时及其产物序列化后的 UTF-8 字节数。
     * 用于评估前端实际接收到的数据规模。
     */
    private fun measurePayload(action: () -> Any?): PayloadMeasurement {
        val measured = measure(action)
        return PayloadMeasurement(
            elapsedMillis = measured.elapsedMillis,
            payloadBytes = JsonCodec.toJson(measured.value).toByteArray(StandardCharsets.UTF_8).size.toLong(),
        )
    }

    /**
     * 通用耗时测量结果，保留原始返回值便于后续使用。
     */
    private data class Measurement<T>(
        /** 被测动作的返回值。 */
        val value: T,
        /** 耗时（毫秒）。 */
        val elapsedMillis: Long,
    )

    /**
     * 投影产物测量结果，只关心耗时与字节量。
     */
    private data class PayloadMeasurement(
        /** 耗时（毫秒）。 */
        val elapsedMillis: Long,
        /** 序列化后的字节数。 */
        val payloadBytes: Long,
    )
}
