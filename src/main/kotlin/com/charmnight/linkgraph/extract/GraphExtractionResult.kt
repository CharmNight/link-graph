package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 表示链路提取后的结果。
 */
data class GraphExtractionResult(
    /** 保存提取得到的图文档。 */
    val document: GraphDocument,
    /** 保存入口方法对应的提取边界信息。 */
    val entryMethodBoundaries: Map<String, ExtractionBoundary> = emptyMap(),
)
