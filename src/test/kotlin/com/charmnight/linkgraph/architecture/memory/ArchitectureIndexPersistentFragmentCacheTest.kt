package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArchitectureIndexPersistentFragmentCacheTest {
    @Test
    fun restoresCompleteArchitectureIndexWhenEverySliceHitsPersistentCache() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java", contentSha256 = "hash-orders"))
        val key = cacheKey("slice:orders")
        store.write(
            key,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:orders",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderService",
                        qualifiedName = "com.example.orders.OrderService",
                        simpleName = "OrderService",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        packageName = "com.example.orders",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNotNull(result.index)
        assertEquals(1, result.hits)
        assertEquals(0, result.misses)
        assertEquals("com.example.orders.OrderService", result.index.symbolIndex.findClass("com.example.orders.OrderService")?.qualifiedName)
    }

    @Test
    fun doesNotRestoreCompleteIndexWhenAnySliceIsStaleOrMissing() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java"))

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = setOf("slice:orders"),
            cacheKeyForSlice = { cacheKey("slice:orders") },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:orders"), result.missingSliceIds)
    }

    @Test
    fun treatsProjectSlicesWithoutContentHashAsPersistentCacheMiss() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java", contentSha256 = null))
        val key = cacheKey("slice:orders")
        store.write(
            key,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:orders",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderService",
                        qualifiedName = "com.example.orders.OrderService",
                        simpleName = "OrderService",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        packageName = "com.example.orders",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:orders"), result.missingSliceIds)
    }

    @Test
    fun treatsProjectSlicesWithoutFilesAsPersistentCacheMiss() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(
            ProjectSlice(
                id = "slice:empty",
                moduleName = null,
                contentRoot = "/repo",
                sourceSet = "main",
                packagePrefix = "com.example.empty",
                kind = ProjectSliceKind.JVM_SOURCE.name,
                files = emptyList(),
            ),
        )
        val key = cacheKey("slice:empty")
        store.write(
            key,
            ArchitectureIndexSliceFragment(sliceId = "slice:empty"),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:empty"), result.missingSliceIds)
    }

    private fun manifest(slice: ProjectSlice): ProjectSliceManifest =
        ProjectSliceManifest(
            projectLocationHash = "project",
            slices = listOf(slice),
        )

    private fun slice(
        id: String,
        path: String,
        contentSha256: String? = "hash",
    ): ProjectSlice =
        ProjectSlice(
            id = id,
            moduleName = null,
            contentRoot = "/repo",
            sourceSet = "main",
            packagePrefix = "com.example.orders",
            kind = ProjectSliceKind.JVM_SOURCE.name,
            files = listOf(ProjectFileFingerprint(path, 1, 1, contentSha256)),
        )

    private fun cacheKey(sliceId: String): ArchitectureIndexFragmentCacheKey =
        ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = sliceId,
            fileHash = "file",
        )
}
