package com.charmnight.linkgraph.jvm.index

/**
 * 不可用状态下的 [JvmSourceTextSymbolExtractor] 占位实现：始终返回空列表。
 *
 * 当实际环境无法读取源码文本（例如索引阶段尚未完成、文件被锁定）时，
 * 用该对象作为兜底依赖，避免上游需要做空判断。
 */
object UnavailableJvmSourceTextSymbolExtractor : JvmSourceTextSymbolExtractor {
    /** 永远返回空列表，表示无任何符号可提取。 */
    override fun extract(path: String, text: String): List<JvmSourceTextSymbol> = emptyList()
}
