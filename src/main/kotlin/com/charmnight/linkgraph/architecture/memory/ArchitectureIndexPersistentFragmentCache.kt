package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget

data class ArchitectureIndexPersistentRestoreResult(
    val index: ArchitectureGraphIndex?,
    val fragments: List<ArchitectureIndexSliceFragment>,
    val hits: Int,
    val misses: Int,
    val missingSliceIds: Set<String>,
)

class ArchitectureIndexPersistentFragmentCache(
    private val store: PersistentArchitectureIndexCacheStore,
    private val merger: ArchitectureIndexFragmentMerger = ArchitectureIndexFragmentMerger(),
) {
    fun restoreCompleteIndex(
        manifest: ProjectSliceManifest,
        staleSliceIds: Set<String>,
        cacheKeyForSlice: (ProjectSlice) -> ArchitectureIndexFragmentCacheKey,
        budget: JvmResolutionBudget,
    ): ArchitectureIndexPersistentRestoreResult {
        val fragments = mutableListOf<ArchitectureIndexSliceFragment>()
        val missing = linkedSetOf<String>()
        var hits = 0
        var misses = 0
        manifest.slices.forEach { slice ->
            if (slice.id in staleSliceIds) {
                misses += 1
                missing += slice.id
                return@forEach
            }
            val fragment = store.read(cacheKeyForSlice(slice))
            if (fragment == null) {
                misses += 1
                missing += slice.id
            } else {
                hits += 1
                fragments += fragment
            }
        }
        val index = if (missing.isEmpty()) {
            val merged = merger.merge(fragments)
            ArchitectureGraphIndex.from(
                symbolIndex = merged.symbolIndex,
                relationIndex = merged.relationIndex,
                budget = budget,
            )
        } else {
            null
        }
        return ArchitectureIndexPersistentRestoreResult(
            index = index,
            fragments = fragments,
            hits = hits,
            misses = misses,
            missingSliceIds = missing,
        )
    }
}
