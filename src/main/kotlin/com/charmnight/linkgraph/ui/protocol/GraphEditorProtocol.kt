package com.charmnight.linkgraph.ui.protocol

/**
 * 图编辑器前后端协议的常量集合。
 *
 * 把 schema 版本与增量传输类型等协议级常量集中在一处，方便前后端各自引用、对齐。
 * 任何常量值的变更都需要同步前后端发布，否则可能导致协议不兼容。
 */
object GraphEditorProtocol {
    /** 协议 schema 版本号；前后端握手时校验，避免版本错配。 */
    const val SCHEMA_VERSION: Int = 1

    /** 产物切片增量消息类型字符串。 */
    const val ARTIFACT_SLICE: String = "ARTIFACT_SLICE"

    /** 反馈切片增量消息类型字符串。 */
    const val FEEDBACK_SLICE: String = "FEEDBACK_SLICE"

    /** 当前协议支持的全部增量消息类型列表。新增类型时在此登记，前端会据此做合法校验。 */
    val INCREMENTAL_TRANSPORT_TYPES: List<String> = listOf(
        ARTIFACT_SLICE,
        FEEDBACK_SLICE,
    )
}
