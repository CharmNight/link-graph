package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex

data class ArchitectureSymbolSearchResult(
    val symbol: JvmSymbol,
    val score: Int,
    val matchKind: String,
)

class ArchitectureSymbolSearch(
    private val symbolIndex: JvmSymbolIndex,
) {
    fun search(query: String, limit: Int = 50): List<ArchitectureSymbolSearchResult> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        return symbolIndex.symbolsById.values
            .mapNotNull { symbol -> symbol.match(normalized) }
            .sortedWith(
                compareByDescending<ArchitectureSymbolSearchResult> { it.score }
                    .thenBy { it.symbol.qualifiedName }
                    .thenBy { it.symbol.id },
            )
            .take(limit.coerceAtLeast(1))
    }

    private fun JvmSymbol.match(query: String): ArchitectureSymbolSearchResult? {
        val sourcePath = source?.displayPath.orEmpty()
        val match = when {
            id == query -> "ID_EXACT" to 1000
            qualifiedName == query -> "QUALIFIED_NAME_EXACT" to 950
            simpleName == query -> "SIMPLE_NAME_EXACT" to 900
            qualifiedName.equals(query, ignoreCase = true) -> "QUALIFIED_NAME_CASE_INSENSITIVE" to 880
            simpleName.equals(query, ignoreCase = true) -> "SIMPLE_NAME_CASE_INSENSITIVE" to 850
            qualifiedName.startsWith(query, ignoreCase = true) -> "QUALIFIED_NAME_PREFIX" to 760
            simpleName.startsWith(query, ignoreCase = true) -> "SIMPLE_NAME_PREFIX" to 740
            simpleName.contains(query, ignoreCase = true) -> "SIMPLE_NAME_CONTAINS" to 520
            qualifiedName.contains(query, ignoreCase = true) -> "QUALIFIED_NAME_CONTAINS" to 360
            sourcePath.contains(query, ignoreCase = true) -> "SOURCE_PATH_CONTAINS" to 240
            else -> return null
        }
        return ArchitectureSymbolSearchResult(this, match.second, match.first)
    }
}
