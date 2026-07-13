package com.charmnight.linkgraph.source

/** 同时受 LRU、TTL、条目数和总字节预算管理的内存缓存。 */
internal class ManagedDecompiledSourceCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeightBytes: Long,
    private val ttlMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val weightBytes: (V) -> Int,
) {
    private data class Entry<V>(
        val value: V,
        val weightBytes: Int,
        var lastAccessMillis: Long,
    )

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var totalWeightBytes: Long = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxWeightBytes >= 0) { "maxWeightBytes must be non-negative" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun get(key: K): V? {
        val now = clockMillis()
        pruneExpired(now)
        val entry = entries[key] ?: return null
        entry.lastAccessMillis = now
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        val now = clockMillis()
        pruneExpired(now)
        entries.remove(key)?.let { previous -> totalWeightBytes -= previous.weightBytes }
        val weight = weightBytes(value).coerceAtLeast(0)
        if (weight.toLong() > maxWeightBytes) {
            return
        }
        entries[key] = Entry(value = value, weightBytes = weight, lastAccessMillis = now)
        totalWeightBytes += weight
        pruneToBudget()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired(clockMillis())
        return entries.size
    }

    @Synchronized
    fun totalWeightBytes(): Long {
        pruneExpired(clockMillis())
        return totalWeightBytes
    }

    private fun pruneExpired(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.lastAccessMillis > ttlMillis) {
                totalWeightBytes -= entry.weightBytes
                iterator.remove()
            }
        }
    }

    private fun pruneToBudget() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || totalWeightBytes > maxWeightBytes) && iterator.hasNext()) {
            val entry = iterator.next().value
            totalWeightBytes -= entry.weightBytes
            iterator.remove()
        }
    }
}
