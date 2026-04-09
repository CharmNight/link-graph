package com.charmnight.linkgraph.model

/**
 * 表示图元素在不同来源之间的差异状态。
 */
enum class DiffStatus {
    /** 表示不同来源中的元素完全一致。 */
    MATCHED,
    /** 表示元素只存在于代码侧。 */
    ONLY_IN_CODE,
    /** 表示元素只存在于 Mermaid 设计侧。 */
    ONLY_IN_MERMAID,
    /** 表示元素在多个来源之间存在内容差异。 */
    MODIFIED,
}
