package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget

/**
 * 持久化索引恢复结果。
 *
 * @property index 恢复出的完整索引；任一 slice 缺失时为 null
 * @property fragments 成功恢复的 slice 片段列表
 * @property hits 缓存命中次数
 * @property misses 缓存未命中次数
 * @property missingSliceIds 缺失 slice 的 ID 集合
 */
data class ArchitectureIndexPersistentRestoreResult(
    val index: ArchitectureGraphIndex?,
    val fragments: List<ArchitectureIndexSliceFragment>,
    val hits: Int,
    val misses: Int,
    val missingSliceIds: Set<String>,
)

/**
 * 架构索引持久化分片缓存。
 *
 * 把整个索引按 slice 切片持久化，恢复时按 slice 读回再合并。
 * 这种方式支持增量失效：只需要重新生成变更的 slice，其他 slice 复用缓存。
 *
 * @param store 持久化存储
 * @param merger 分片合并器
 */
class ArchitectureIndexPersistentFragmentCache(
    private val store: PersistentArchitectureIndexCacheStore,
    private val merger: ArchitectureIndexFragmentMerger = ArchitectureIndexFragmentMerger(),
) {
    /**
     * 从持久化缓存恢复完整索引。
     *
     * 流程：
     * 1) 遍历 manifest 中的所有 slice；
     * 2) 跳过 staleSliceIds 中标记为失效的 slice；
     * 3) 跳过没有完整内容指纹的 slice（无法验证缓存有效性）；
     * 4) 从 store 读取每个 slice 的片段；
     * 5) 若所有 slice 都命中，合并为完整索引；否则返回 null 并报告缺失。
     *
     * @param manifest slice 清单
     * @param staleSliceIds 已失效的 slice ID 集合
     * @param cacheKeyForSlice 由 slice 计算缓存 key 的函数
     * @param budget 索引预算
     * @return 恢复结果
     */
    fun restoreCompleteIndex(
        manifest: ProjectSliceManifest,
        staleSliceIds: Set<String>,
        cacheKeyForSlice: (ProjectSlice) -> ArchitectureIndexFragmentCacheKey,
        budget: JvmResolutionBudget,
    ): ArchitectureIndexPersistentRestoreResult {
        // 缓存与当前代码不兼容时直接放弃：旧 schema 的 fragment 反序列化结构可能已变更，
        // 合并出来会得到错乱索引。把所有 slice 视为 missing 让上游全量重建。
        if (manifest.schemaVersion != ProjectSliceManifest.CURRENT_SCHEMA_VERSION) {
            return ArchitectureIndexPersistentRestoreResult(
                index = null,
                fragments = emptyList(),
                hits = 0,
                misses = manifest.slices.size,
                missingSliceIds = manifest.slices.mapTo(linkedSetOf(), ProjectSlice::id),
            )
        }
        val fragments = mutableListOf<ArchitectureIndexSliceFragment>()
        val missing = linkedSetOf<String>()
        var hits = 0
        var misses = 0
        manifest.slices.forEach { slice ->
            // 已失效的 slice 直接跳过
            if (slice.id in staleSliceIds) {
                misses += 1
                missing += slice.id
                return@forEach
            }
            // 没有完整指纹的 slice 也跳过（无法验证缓存一致性）
            if (!slice.hasCompleteContentFingerprints()) {
                misses += 1
                missing += slice.id
                return@forEach
            }
            // 从存储读取片段
            val fragment = store.read(cacheKeyForSlice(slice))
            if (fragment == null) {
                misses += 1
                missing += slice.id
            } else {
                hits += 1
                fragments += fragment
            }
        }
        // 全部命中才合并出完整索引；任一缺失都返回 null 让上游重建
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

    /**
     * 判断 slice 是否有完整的内容指纹。
     * 没有内容指纹的 slice 无法验证缓存一致性，必须跳过。
     */
    private fun ProjectSlice.hasCompleteContentFingerprints(): Boolean =
        files.isNotEmpty() && files.all { file -> !file.contentSha256.isNullOrBlank() }
}
