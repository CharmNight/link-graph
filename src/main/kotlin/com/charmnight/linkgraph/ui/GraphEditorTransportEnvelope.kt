package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.protocol.GraphEditorProtocol

/**
 * 传输信封携带的状态 DTO 多态根类型。
 *
 * - [Snapshot] 携带 [BootstrapPayloadDto]
 * - [ArtifactSlice] 携带 [ArtifactSlicePayloadDto] 或 [ArtifactContentsSliceDto]
 * - [FeedbackSlice] 携带 [FeedbackSlicePayloadDto]
 *
 * 通过 sealed interface 替代原先的 `state: Any`——Gson 反射序列化行为不变，
 * 但 Kotlin 端编译期就能限制 state 只能是上述 DTO，避免任意对象流入信封。
 */
sealed interface TransportSliceStateDto

/**
 * 传输信封：把图谱状态以不同形式（完整快照 / 产物切片 / 反馈切片）封装为前端可消费的结构。
 *
 * [state] 类型统一为 [TransportSliceStateDto]，由 sealed 体系约束合法子类型；
 * 之前是 `Any`，被 Gson 反射序列化兜底但失去编译期类型保护。
 *
 * 由 [GraphEditorTransportSliceRenderer.renderScript] 整体 JSON 化（Gson 反射序列化）。
 */
sealed interface GraphEditorTransportEnvelope {
    val sessionId: String
    val revision: Long
    val state: TransportSliceStateDto
    val transportType: String?

    data class Snapshot(
        override val sessionId: String,
        override val revision: Long,
        override val state: TransportSliceStateDto,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String? = null
    }

    data class ArtifactSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: TransportSliceStateDto,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.ARTIFACT_SLICE
    }

    data class FeedbackSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: TransportSliceStateDto,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.FEEDBACK_SLICE
    }
}
