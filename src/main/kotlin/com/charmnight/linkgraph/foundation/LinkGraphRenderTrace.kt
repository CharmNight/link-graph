package com.charmnight.linkgraph.foundation

import com.charmnight.linkgraph.model.GraphDocument
import java.util.Locale

/**
 * 渲染链路的轻量级埋点工具。
 *
 * 提供条件化的日志输出与耗时统计，避免在埋点关闭时产生字符串拼接开销，
 * 同时给出可读的图谱规模摘要，便于在渲染日志中快速定位异常。
 */
internal object LinkGraphRenderTrace {
    /**
     * 在开关打开时输出一条埋点日志。
     *
     * 通过懒加载消息函数保证关闭埋点时不会触发字符串构造，
     * 适用于热点路径上的诊断输出。
     *
     * @param enabled 是否启用埋点输出
     * @param log 实际写入日志的回调
     * @param message 懒计算的日志内容
     */
    fun trace(
        enabled: Boolean,
        log: (String) -> Unit,
        message: () -> String,
    ) {
        if (!enabled) {
            return
        }
        log(message())
    }

    /**
     * 输出一个渲染阶段的耗时埋点。
     *
     * 将纳秒时间差换算为毫秒并附加额外的细节字段，
     * 仅在启用时才会执行消息拼接与日志写入。
     *
     * @param enabled 是否启用埋点输出
     * @param log 实际写入日志的回调
     * @param stage 当前渲染阶段的标识名称
     * @param startedAtNanos 阶段开始时的纳秒时间戳
     * @param finishedAtNanos 阶段结束时的纳秒时间戳，默认取当前时刻
     * @param details 懒计算的附加细节列表，用于补充上下文信息
     */
    fun stage(
        enabled: Boolean,
        log: (String) -> Unit,
        stage: String,
        startedAtNanos: Long,
        finishedAtNanos: Long = System.nanoTime(),
        details: () -> List<String> = { emptyList() },
    ) {
        trace(enabled, log) {
            // 计算阶段耗时并向下取整到非负毫秒，避免时钟回拨导致负值
            val durationMs = (finishedAtNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000.0
            buildString {
                append("渲染链路 trace: stage=")
                append(stage)
                append(", durationMs=")
                append(String.format(Locale.ROOT, "%.2f", durationMs))
                details().filter { it.isNotBlank() }.forEach { detail ->
                    append(", ")
                    append(detail)
                }
            }
        }
    }

    /**
     * 生成图谱规模的概要描述。
     *
     * 汇总节点与边的数量、按类型分组的节点计数，以及一组排过序的示例节点 ID，
     * 用于在不打印整张图的前提下提供足够的排查线索。
     *
     * @param graph 待汇总的图谱文档，为空时返回零值占位描述
     * @return 可直接拼入日志的单行概要文本
     */
    fun graphSummary(graph: GraphDocument?): String {
        if (graph == null) {
            return "nodes=0, edges=0"
        }
        // 按节点类型聚合计数，并用排序后的键保证日志稳定可比较
        val nodeTypes = graph.nodes
            .groupingBy { node -> node.type.name }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(separator = "|") { (type, count) -> "$type:$count" }
            .ifBlank { "none" }
        // 取排序后的前若干个节点 ID 作为示例，控制日志长度
        val sampleNodeIds = graph.nodes
            .asSequence()
            .map { node -> node.id }
            .sorted()
            .take(6)
            .joinToString(separator = "|")
            .ifBlank { "none" }
        return "nodes=${graph.nodes.size}, edges=${graph.edges.size}, nodeTypes=$nodeTypes, sampleNodeIds=$sampleNodeIds"
    }
}
