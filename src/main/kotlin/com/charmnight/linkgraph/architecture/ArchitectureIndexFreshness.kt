package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness

/**
 * 索引新鲜度快照，反映当前索引状态、脏原因、待处理文件与时间戳信息。
 */
data class ArchitectureIndexFreshnessSnapshot(
    /** 状态字符串（FRESH/STALE/BUILDING）。 */
    val state: String = "FRESH",
    /** 导致脏的原因描述。 */
    val dirtyReason: String? = null,
    /** 待处理文件数量。 */
    val pendingFileCount: Int = 0,
    /** 待处理文件路径样例。 */
    val pendingFileSamples: List<String> = emptyList(),
    /** 最近一次成功索引时间。 */
    val lastIndexedAtEpochMillis: Long? = null,
    /** 首次变为陈旧的时间。 */
    val staleSinceEpochMillis: Long? = null,
)

/**
 * 索引新鲜度跟踪器，负责维护索引的脏/构建/新鲜状态及并发安全。
 */
class ArchitectureIndexFreshnessTracker(
    /** 时钟函数，便于测试替换。 */
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var state: String = "FRESH"
    private var dirtyReason: String? = null
    private var pendingFiles: Set<String> = emptySet()
    private var lastIndexedAtEpochMillis: Long? = null
    private var staleSinceEpochMillis: Long? = null
    private var dirtyGeneration: Long = 0

    /** 标记索引为脏，记录脏原因与相关文件路径。 */
    fun markDirty(dirtyReason: String, paths: List<String>) {
        synchronized(lock) {
            if (state != "BUILDING") {
                state = "STALE"
            }
            this.dirtyReason = dirtyReason
            pendingFiles = (pendingFiles + paths.filter(String::isNotBlank)).toSortedSet()
            if (staleSinceEpochMillis == null) {
                staleSinceEpochMillis = clockMillis()
            }
            dirtyGeneration += 1
        }
    }

    /** 标记开始构建，返回构建时点的脏代际（用于一致性校验）。 */
    fun markBuilding(): Long =
        synchronized(lock) {
            state = "BUILDING"
            dirtyGeneration
        }

    /** 标记索引完成；若期间发生新的脏代际则保持脏状态。 */
    fun markIndexed(buildToken: Long? = null) {
        synchronized(lock) {
            lastIndexedAtEpochMillis = clockMillis()
            if (buildToken != null && buildToken != dirtyGeneration) {
                state = "STALE"
                return
            }
            state = "FRESH"
            dirtyReason = null
            pendingFiles = emptySet()
            staleSinceEpochMillis = null
        }
    }

    /** 标记索引构建失败并将状态置为陈旧。 */
    fun markBuildFailed(buildToken: Long? = null) {
        synchronized(lock) {
            state = "STALE"
            if (dirtyReason == null) {
                dirtyReason = "INDEX_BUILD_FAILED"
            }
            if (staleSinceEpochMillis == null) {
                staleSinceEpochMillis = clockMillis()
            }
        }
    }

    /** 生成当前状态的快照。 */
    fun snapshot(): ArchitectureIndexFreshnessSnapshot =
        synchronized(lock) {
            ArchitectureIndexFreshnessSnapshot(
                state = state,
                dirtyReason = dirtyReason,
                pendingFileCount = pendingFiles.size,
                pendingFileSamples = pendingFiles.take(MAX_PENDING_FILE_SAMPLES),
                lastIndexedAtEpochMillis = lastIndexedAtEpochMillis,
                staleSinceEpochMillis = staleSinceEpochMillis,
            )
        }

    private companion object {
        /** 快照中保留的待处理文件样例上限。 */
        const val MAX_PENDING_FILE_SAMPLES = 5
    }
}

/** 把内部快照类型转换为对外暴露的 IndexedGraphFreshness 类型。 */
fun ArchitectureIndexFreshnessSnapshot.toIndexedFreshness(): IndexedGraphFreshness =
    IndexedGraphFreshness(
        state = state,
        dirtyReason = dirtyReason,
        pendingFileCount = pendingFileCount,
        pendingFileSamples = pendingFileSamples,
        lastIndexedAtEpochMillis = lastIndexedAtEpochMillis,
        staleSinceEpochMillis = staleSinceEpochMillis,
    )
