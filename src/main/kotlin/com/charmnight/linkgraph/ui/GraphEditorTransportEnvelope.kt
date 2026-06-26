package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.protocol.GraphEditorProtocol

/**
 * 传输信封：把图谱状态以不同形式（完整快照 / artifact 切片 / 反馈切片）封装为前端可消费的结构。
 *
 * P2-6：[state] 类型为 Any，由各子类约定具体承载类型：
 * - [Snapshot.state]：[BootstrapPayloadDto]（完整快照根 DTO）
 * - [ArtifactSlice.state]：[ArtifactSlicePayloadDto]（增量 artifact 切片 DTO）或 [ArtifactContentsSliceDto]（按需拉取响应）
 * - [FeedbackSlice.state]：[FeedbackSlicePayloadDto]（反馈切片 DTO）
 *
 * 由 [GraphEditorTransportSliceRenderer.renderScript] 整体 JSON 化（Gson 反射序列化）。
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
