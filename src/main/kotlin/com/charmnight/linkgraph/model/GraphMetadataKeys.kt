package com.charmnight.linkgraph.model

/**
 * 图节点/边元数据中常用的 key 常量集合。
 *
 * 元数据是一个自由映射（key→value），不同子系统各自约定 key 命名。
 * 把这些常用 key 集中在 object 中可以避免拼写错误，也让 IDE 自动补全更友好。
 */
object GraphMetadataKeys {
    /** 与"源码定位"相关的 key 集合。用于把节点跳转回代码位置。 */
    object Source {
        /** 源码文件路径。 */
        const val FILE_PATH: String = "source.filePath"
        /** IntelliJ 虚拟文件 URL（用于跨项目跳转）。 */
        const val VIRTUAL_FILE_URL: String = "source.virtualFileUrl"
        /** 起始字符偏移。 */
        const val START_OFFSET: String = "source.startOffset"
        /** 结束字符偏移。 */
        const val END_OFFSET: String = "source.endOffset"
        /** 起始行号。 */
        const val START_LINE: String = "source.startLine"
        /** 结束行号。 */
        const val END_LINE: String = "source.endLine"
        /** 列号。 */
        const val COLUMN: String = "source.column"
        /** 来源标记（导入、索引、设计等）。 */
        const val ORIGIN: String = "source.origin"
        /** 是否来自反编译。 */
        const val DECOMPILED: String = "source.decompiled"
    }

    /** 与"UI 状态"相关的 key 集合。仅前端使用，不应进入后端持久化。 */
    object Ui {
        /** 节点 X 坐标。 */
        const val X: String = "ui.x"
        /** 节点 Y 坐标。 */
        const val Y: String = "ui.y"
    }
}
