package com.charmnight.linkgraph.model

/**
 * 表示图元素在不同来源之间的差异状态。
 *
 * 用于差异比对视图，把"代码侧"和"设计侧（Mermaid）"两个来源之间的元素位置与内容差异
 * 做统一编码。
 */
enum class DiffStatus {
    /** 表示不同来源中的元素完全一致。 */
    MATCHED,

    /** 表示元素只存在于代码侧，设计图未体现。 */
    ONLY_IN_CODE,

    /** 表示元素只存在于 Mermaid 设计侧，代码中尚未落地。 */
    ONLY_IN_MERMAID,

    /** 表示元素在多个来源之间存在内容差异（字段、签名等不完全一致）。 */
    MODIFIED,
}
