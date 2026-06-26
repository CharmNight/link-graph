package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.protocol.GraphEditorProtocol

/**
 * 传输信封：把图谱状态以不同形式（完整快照 / artifact 切片 / 反馈切片）封装为前端可消费的结构。
 *
 * P2-6：[state] 类型从 Map<String, Any?> 改为 Any，让信封可持有 [BootstrapPayloadDto]（DTO 实例）
 * 或 Map<String, Any?>（增量切片场景），由 [GraphEditorTransportSliceRenderer.renderScript] 整体 JSON 化。
 */
sealed interface GraphEditorTransportEnvelope {
    val sessionId: String
    val revision: Long
    val state: Any
    val transportType: String?

    data class Snapshot(
        override val sessionId: String,
        override val revision: Long,
        override val state: Any,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String? = null
    }

    data class ArtifactSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Any,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.ARTIFACT_SLICE
    }

    data class FeedbackSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Any,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.FEEDBACK_SLICE
    }
}
