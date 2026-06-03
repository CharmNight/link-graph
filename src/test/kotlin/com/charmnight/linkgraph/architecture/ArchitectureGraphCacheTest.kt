package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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

    @Test
    fun coalescesConcurrentBuildsForSameKey() {
        val cache = ArchitectureGraphCache(clockMillis = { 123L })
        val key = ArchitectureGraphCacheKey(
            projectLocationHash = 1,
            projectRootModificationCount = 2L,
            psiModificationCount = 3L,
            includeTests = true,
            includeExternalLibraries = false,
            includeJdk = false,
            includeUserAttachedJars = false,
            maxProjectClasses = 10,
            maxExternalClasses = 20,
            maxMethods = 30,
            maxRelations = 40,
            attachedJars = emptyList(),
        )
        val buildCount = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        val futures = (1..4).map {
            executor.submit<CachedArchitectureGraph> {
                cache.getOrBuild(key) {
                    buildCount.incrementAndGet()
                    Thread.sleep(20L)
                    ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())
                }
            }
        }

        val results = futures.map { future -> future.get(2, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, buildCount.get())
        assertEquals(1, results.map { cached -> cached.index }.distinct().size)
    }

    @Test
    fun forceRebuildReplacesCachedIndexForSameKey() {
        val cache = ArchitectureGraphCache(clockMillis = { 123L })
        val key = ArchitectureGraphCacheKey(
            projectLocationHash = 1,
            projectRootModificationCount = 2L,
            psiModificationCount = 3L,
            includeTests = true,
            includeExternalLibraries = false,
            includeJdk = false,
            includeUserAttachedJars = false,
            maxProjectClasses = 10,
            maxExternalClasses = 20,
            maxMethods = 30,
            maxRelations = 40,
            attachedJars = emptyList(),
        )
        val buildCount = AtomicInteger()

        val first = cache.getOrBuild(key) {
            buildCount.incrementAndGet()
            ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())
        }
        val reused = cache.getOrBuild(key) {
            buildCount.incrementAndGet()
            ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())
        }
        val rebuilt = cache.getOrBuild(key, forceRebuild = true) {
            buildCount.incrementAndGet()
            ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())
        }

        assertEquals(2, buildCount.get())
        assertEquals(first.index, reused.index)
        assertNotSame(first.index, rebuilt.index)
        assertEquals(rebuilt.index, cache.get(key)?.index)
    }

    @Test
    fun invalidateDoesNotBlockBehindLongRunningBuildForSameKey() {
        val cache = ArchitectureGraphCache(clockMillis = { 123L })
        val key = ArchitectureGraphCacheKey(
            projectLocationHash = 1,
            projectRootModificationCount = 2L,
            psiModificationCount = 3L,
            includeTests = true,
            includeExternalLibraries = false,
            includeJdk = false,
            includeUserAttachedJars = false,
            maxProjectClasses = 10,
            maxExternalClasses = 20,
            maxMethods = 30,
            maxRelations = 40,
            attachedJars = emptyList(),
        )
        val builderStarted = CountDownLatch(1)
        val releaseBuilder = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val buildFuture = executor.submit<CachedArchitectureGraph> {
            cache.getOrBuild(key) {
                builderStarted.countDown()
                releaseBuilder.await(2, TimeUnit.SECONDS)
                ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex())
            }
        }
        assertEquals(true, builderStarted.await(1, TimeUnit.SECONDS))

        val invalidationExecutor = Executors.newSingleThreadExecutor()
        val invalidatedWithinDeadline = try {
            val invalidationFuture = invalidationExecutor.submit<Boolean> {
                cache.invalidate()
                true
            }
            invalidationFuture.get(200, TimeUnit.MILLISECONDS)
        } finally {
            invalidationExecutor.shutdownNow()
        }

        releaseBuilder.countDown()
        buildFuture.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertEquals(true, invalidatedWithinDeadline)
    }
}
