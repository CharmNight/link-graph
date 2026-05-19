package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArchitectureGraphCacheTest {
    @Test
    fun storesIndexAsPrimaryCachedObject() {
        val cache = ArchitectureGraphCache(clockMillis = { 123L })
        val key = ArchitectureGraphCacheKey(
            projectLocationHash = 1,
            projectRootModificationCount = 2L,
            psiModificationCount = 3L,
            includeTests = true,
            includeExternalLibraries = true,
            includeJdk = false,
            includeUserAttachedJars = true,
            maxProjectClasses = 10,
            maxExternalClasses = 20,
            maxMethods = 30,
            maxRelations = 40,
            attachedJars = listOf("/tmp/a.jar|/tmp/a-sources.jar"),
        )
        val index = ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())

        val cached = cache.put(key, index)

        assertEquals(index, cached.index)
        assertEquals(index.graph, cached.architectureGraph)
        assertEquals(123L, cached.createdAtMillis)
        assertNotNull(cache.get(key))
        cache.invalidate(key)
        assertNull(cache.get(key))
    }
}
