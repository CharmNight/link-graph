package com.charmnight.linkgraph.semantic.model

import com.charmnight.linkgraph.semantic.subject.SourceRange

/**
 * 表示语义单元与源码位置之间的映射关系。
 */
data class SourceMapping(
    /** 保存源码文件路径。 */
    val sourcePath: String,
    /** 保存源码范围。 */
    val sourceRange: SourceRange,
    /** 保存对应的目标语义单元标识。 */
    val targetUnitId: String,
)
