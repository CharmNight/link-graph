package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex

/**
 * 单次符号搜索的结果项。
 *
 * @property symbol 命中的 JVM 符号
 * @property score 命中得分；用于排序（越高越靠前）
 * @property matchKind 命中类型描述（ID_EXACT / SIMPLE_NAME_PREFIX 等）
 */
data class ArchitectureSymbolSearchResult(
    val symbol: JvmSymbol,
    val score: Int,
    val matchKind: String,
)

/**
 * 架构符号搜索器。
 *
 * 基于 [JvmSymbolIndex] 实现模糊匹配搜索：
 * - 完全匹配（ID/全限定名/简单名）得分最高；
 * - 大小写不敏感匹配次之；
 * - 前缀/包含匹配得分较低；
 * - 源码路径包含作为兜底。
 * 让用户可以用简单关键字快速跳转到任意架构符号。
 */
class ArchitectureSymbolSearch(
    /** JVM 符号索引；搜索的基础数据源。 */
    private val symbolIndex: JvmSymbolIndex,
) {
    /**
     * 执行一次搜索。
     *
     * @param query 查询字符串（前后空白会被去掉）
     * @param limit 最多返回结果数；默认 50
     * @return 按得分降序、符号名升序排列的命中结果
     */
    fun search(query: String, limit: Int = 50): List<ArchitectureSymbolSearchResult> {
        val normalized = query.trim()
        // 空查询直接返回空，避免无意义全量返回
        if (normalized.isBlank()) {
            return emptyList()
        }
        return symbolIndex.symbolsById.values
            // 每个符号尝试匹配，未命中返回 null 并被过滤
            .mapNotNull { symbol -> symbol.match(normalized) }
            .sortedWith(
                // 主排序：得分降序；次排序：全限定名升序（保证多次查询顺序稳定）；末尾按 ID 兜底
                compareByDescending<ArchitectureSymbolSearchResult> { it.score }
                    .thenBy { it.symbol.qualifiedName }
                    .thenBy { it.symbol.id },
            )
            // limit 至少为 1，避免参数误传为 0 时返回空
            .take(limit.coerceAtLeast(1))
    }

    /**
     * 判断当前符号与查询的匹配情况。
     * 内部根据不同匹配规则计算得分，未匹配返回 null。
     */
    private fun JvmSymbol.match(query: String): ArchitectureSymbolSearchResult? {
        val sourcePath = source?.displayPath.orEmpty()
        val match = when {
            // 精确匹配 ID/全限定名/简单名 → 最高分
            id == query -> "ID_EXACT" to 1000
            qualifiedName == query -> "QUALIFIED_NAME_EXACT" to 950
            simpleName == query -> "SIMPLE_NAME_EXACT" to 900
            // 大小写不敏感的精确匹配 → 次高分
            qualifiedName.equals(query, ignoreCase = true) -> "QUALIFIED_NAME_CASE_INSENSITIVE" to 880
            simpleName.equals(query, ignoreCase = true) -> "SIMPLE_NAME_CASE_INSENSITIVE" to 850
            // 前缀匹配 → 中等分数
            qualifiedName.startsWith(query, ignoreCase = true) -> "QUALIFIED_NAME_PREFIX" to 760
            simpleName.startsWith(query, ignoreCase = true) -> "SIMPLE_NAME_PREFIX" to 740
            // 包含匹配 → 较低分数
            simpleName.contains(query, ignoreCase = true) -> "SIMPLE_NAME_CONTAINS" to 520
            qualifiedName.contains(query, ignoreCase = true) -> "QUALIFIED_NAME_CONTAINS" to 360
            // 源码路径包含 → 最低分兜底
            sourcePath.contains(query, ignoreCase = true) -> "SOURCE_PATH_CONTAINS" to 240
            else -> return null
        }
        return ArchitectureSymbolSearchResult(this, match.second, match.first)
    }
}
