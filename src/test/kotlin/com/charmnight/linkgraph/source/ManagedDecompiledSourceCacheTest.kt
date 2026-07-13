package com.charmnight.linkgraph.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManagedDecompiledSourceCacheTest {
    @Test
    fun evictsLeastRecentlyUsedEntriesAndExpiresByTtl() {
        var now = 1_000L
        val cache = ManagedDecompiledSourceCache<String, String>(
            maxEntries = 2,
            maxWeightBytes = 16,
            ttlMillis = 100,
            clockMillis = { now },
            weightBytes = { value -> value.length },
        )
        cache.put("a", "aa")
        cache.put("b", "bb")
        assertEquals("aa", cache.get("a"))

        cache.put("c", "cc")

        assertNull(cache.get("b"))
        assertEquals("aa", cache.get("a"))
        now += 101
        assertNull(cache.get("a"))
        assertNull(cache.get("c"))
        assertEquals(0, cache.size())
    }

    @Test
    fun evictsEntriesUntilTotalWeightFitsBudget() {
        val cache = ManagedDecompiledSourceCache<String, String>(
            maxEntries = 10,
            maxWeightBytes = 4,
            ttlMillis = 1_000,
            weightBytes = { value -> value.length },
        )

        cache.put("a", "aaa")
        cache.put("b", "bb")

        assertNull(cache.get("a"))
        assertEquals("bb", cache.get("b"))
        assertEquals(2L, cache.totalWeightBytes())
    }
}
