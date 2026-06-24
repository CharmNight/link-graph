package com.charmnight.linkgraph.model

/**
 * 表示图节点或图边与源码、设计稿之间的绑定状态。
 *
 * 用于描述设计图中的元素是否已经映射到真实代码，以及映射的完整程度。
 * UI 会根据状态给出不同的视觉提示（例如 DESIGN_ONLY 用虚线，CONFLICTED 用红色）。
 */
enum class BindingStatus {
    /** 表示当前元素已经与真实代码或目标对象完成绑定。 */
    BOUND,

    /** 表示当前元素仅存在于设计侧，尚未映射到代码。 */
    DESIGN_ONLY,

    /** 表示当前元素可以根据设计信息生成对应代码。 */
    GENERATABLE,

    /** 表示当前元素只完成了部分同步，仍存在待补齐内容。 */
    PARTIALLY_SYNCED,

    /** 表示当前元素在多方信息之间存在冲突。 */
    CONFLICTED,
}
